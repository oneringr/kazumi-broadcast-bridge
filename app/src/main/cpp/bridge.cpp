#include <jni.h>
#include <shadowhook.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <link.h>
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <array>
#include <cstring>
#include <cstddef>
#include <cctype>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex config_mutex;
std::array<uint8_t, 32> relay_token{};
std::atomic<int> relay_port{0};
std::atomic<bool> enabled{false};
std::atomic<uint64_t> redirected{0};
void* hook_stub = nullptr;
void* original_connect = nullptr;
std::mutex header_hook_mutex;
std::atomic<void*> header_hook_stub{nullptr};
std::atomic<int> header_hook_status{0};
std::atomic<uint64_t> header_calls{0};
std::atomic<int> header_format{-1};
std::atomic<int> header_node_format{-1};
std::string header_library;
void* original_set_property = nullptr;
std::string source_headers;
using GetPlayerString = char* (*)(void*, const char*);
using FreePlayerValue = void (*)(void*);
std::atomic<GetPlayerString> get_player_string{nullptr};
std::atomic<FreePlayerValue> free_player_value{nullptr};
std::string install_diagnostic = "stage=not-started";

void describe_install(const char* stage, int status, const char* loader_error = nullptr) {
    Dl_info library{};
    dladdr(reinterpret_cast<void*>(shadowhook_init), &library);
    install_diagnostic = std::string("stage=") + stage + "; " + shadowhook_get_version() + "; status=" +
        std::to_string(status) + " (" + shadowhook_to_errmsg(status) + "); library=" +
        (library.dli_fname ? library.dli_fname : "unknown");
    if (loader_error) install_diagnostic += std::string("; dlerror=") + loader_error;
    if (status) {
        char* records = shadowhook_get_records(SHADOWHOOK_RECORD_ITEM_OP | SHADOWHOOK_RECORD_ITEM_LIB_NAME |
            SHADOWHOOK_RECORD_ITEM_SYM_NAME | SHADOWHOOK_RECORD_ITEM_ERRNO);
        if (records) { install_diagnostic += std::string("; records=") + records; std::free(records); }
    }
}

// libmpv's public mpv_node ABI, verified against Kazumi's pinned media_kit bindings.
struct MpvNodeList;
union MpvValue { char* string; int flag; int64_t integer; double number; MpvNodeList* list; void* bytes; };
struct MpvNode { MpvValue value; int format; };
struct MpvNodeList { int count; MpvNode* values; char** keys; };
static_assert(sizeof(MpvNode) == 16 && offsetof(MpvNode, format) == 8);
static_assert(sizeof(MpvNodeList) == 24 && offsetof(MpvNodeList, values) == 8);

bool supported_headers(int format, const void* data, std::string& result) {
    if (format != 6 || !data) return false;  // MPV_FORMAT_NODE
    const auto* node = static_cast<const MpvNode*>(data);
    if (node->format == 0) return true;  // MPV_FORMAT_NONE clears headers at end of playback.
    if (node->format != 7 || !node->value.list) return false;  // MPV_FORMAT_NODE_ARRAY
    const auto* list = node->value.list;
    if (list->count < 0 || list->count > 64 || (list->count && !list->values)) return false;
    for (int i = 0; i < list->count; ++i) {
        const auto& item = list->values[i];
        if (item.format != 1 || !item.value.string) continue;  // MPV_FORMAT_STRING
        const size_t length = strnlen(item.value.string, 8193);
        if (length > 8192) continue;
        std::string line(item.value.string, length);
        auto colon = line.find(':');
        if (colon == std::string::npos || colon > 32) continue;
        std::string name = line.substr(0, colon);
        for (char& ch : name) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
        if (name != "user-agent" && name != "referer" && name != "cookie") continue;
        bool valid = true;
        for (unsigned char ch : line) if (ch < 32 || ch == 127) { valid = false; break; }
        if (valid && result.size() + line.size() + 2 <= 8192) result += line + "\r\n";
    }
    return true;
}

int bridge_set_property(void* context, const char* name, int format, void* data) {
    SHADOWHOOK_STACK_SCOPE();
    if (name && std::strcmp(name, "http-header-fields") == 0) {
        header_calls.fetch_add(1, std::memory_order_relaxed);
        header_format.store(format);
        header_node_format.store(format == 6 && data ? static_cast<const MpvNode*>(data)->format : -1);
    }
    std::string headers;
    const bool capture = name && std::strcmp(name, "http-header-fields") == 0 && supported_headers(format, data, headers);
    const int result = SHADOWHOOK_CALL_PREV(bridge_set_property, context, name, format, data);
    if (capture && result >= 0) {
        // media_kit reads the same public property during on_load before setting headers.
        const auto get_string = get_player_string.load();
        const auto release = free_player_value.load();
        if (format == 6 && data && static_cast<const MpvNode*>(data)->format == 7 && get_string && release) {
            char* path = get_string(context, "path");
            if (path) {
                const size_t length = strnlen(path, 8193);
                if (length <= 8192 && (std::strncmp(path, "https://", 8) == 0 || std::strncmp(path, "http://", 7) == 0)) {
                    bool valid = true;
                    for (size_t i = 0; i < length; ++i) if (static_cast<unsigned char>(path[i]) < 32 || path[i] == 127) { valid = false; break; }
                    if (valid && headers.size() + length + 24 <= 8192) headers += std::string("X-KBB-Player-Source: ") + path + "\r\n";
                }
                release(path);
            }
        }
        std::lock_guard<std::mutex> guard(config_mutex);
        source_headers = std::move(headers);
    }
    return result;
}

void inspect_player_library(const char* name) {
    if (!name || !std::strstr(name, "/data/") || header_hook_stub.load()) return;
    std::unique_lock<std::mutex> guard(header_hook_mutex, std::try_to_lock);
    if (!guard.owns_lock() || header_hook_stub.load()) return;
    void* handle = shadowhook_dlopen(name);
    if (!handle) return;
    void* symbol = shadowhook_dlsym_dynsym(handle, "mpv_set_property");
    if (symbol) {
        get_player_string.store(reinterpret_cast<GetPlayerString>(shadowhook_dlsym_dynsym(handle, "mpv_get_property_string")));
        free_player_value.store(reinterpret_cast<FreePlayerValue>(shadowhook_dlsym_dynsym(handle, "mpv_free")));
        header_library = name;
        header_hook_stub.store(shadowhook_hook_sym_addr_2(symbol, reinterpret_cast<void*>(bridge_set_property),
            &original_set_property, SHADOWHOOK_HOOK_WITH_SHARED_MODE));
        header_hook_status.store(header_hook_stub.load() ? 0 : shadowhook_get_errno());
    }
    shadowhook_dlclose(handle);
}

void player_library_loaded(dl_phdr_info* info, size_t, void*) { if (info) inspect_player_library(info->dlpi_name); }
int inspect_loaded_library(dl_phdr_info* info, size_t, void*) {
    if (info) inspect_player_library(info->dlpi_name);
    return header_hook_stub.load() ? 1 : 0;
}

bool private_v4(const uint8_t* a) {
    return a[0] == 10 || (a[0] == 172 && a[1] >= 16 && a[1] <= 31) ||
        (a[0] == 192 && a[1] == 168) || (a[0] == 169 && a[1] == 254);
}

bool local_target(const sockaddr* address, socklen_t length) {
    if (address->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
        auto* a = reinterpret_cast<const sockaddr_in*>(address);
        return a->sin_port != 0 && private_v4(reinterpret_cast<const uint8_t*>(&a->sin_addr));
    }
    if (address->sa_family == AF_INET6 && length >= sizeof(sockaddr_in6)) {
        auto* a = reinterpret_cast<const sockaddr_in6*>(address);
        if (!a->sin6_port) return false;
        if (IN6_IS_ADDR_V4MAPPED(&a->sin6_addr)) return private_v4(a->sin6_addr.s6_addr + 12);
        return IN6_IS_ADDR_LINKLOCAL(&a->sin6_addr) || ((a->sin6_addr.s6_addr[0] & 0xfe) == 0xfc);
    }
    return false;
}

bool send_prelude(int fd, const std::vector<uint8_t>& packet) {
    size_t offset = 0;
    while (offset < packet.size()) {
        ssize_t sent = send(fd, packet.data() + offset, packet.size() - offset, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (sent > 0) { offset += static_cast<size_t>(sent); continue; }
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd event{fd, POLLOUT, 0};
            if (poll(&event, 1, 500) > 0) continue;
        }
        return false;
    }
    return true;
}

int bridge_connect(int fd, const sockaddr* address, socklen_t length) {
    SHADOWHOOK_STACK_SCOPE();
    const int saved_errno = errno;
    Dl_info caller{};
    const bool from_flutter = dladdr(SHADOWHOOK_RETURN_ADDRESS(), &caller) && caller.dli_fname &&
        std::strstr(caller.dli_fname, "/libflutter.so");
    int type = 0;
    socklen_t type_length = sizeof(type);
    if (!enabled.load(std::memory_order_acquire) || !address || !from_flutter || !local_target(address, length) ||
        getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_length) || type != SOCK_STREAM) {
        errno = saved_errno;
        return SHADOWHOOK_CALL_PREV(bridge_connect, fd, address, length);
    }
    std::vector<uint8_t> packet(61);
    std::memcpy(packet.data(), "KBB2", 4);
    int port;
    {
        std::lock_guard<std::mutex> guard(config_mutex);
        port = relay_port.load();
        std::memcpy(packet.data() + 4, relay_token.data(), relay_token.size());
        packet[59] = static_cast<uint8_t>(source_headers.size() >> 8);
        packet[60] = static_cast<uint8_t>(source_headers.size());
        packet.insert(packet.end(), source_headers.begin(), source_headers.end());
    }
    if (port <= 0) { errno = saved_errno; return SHADOWHOOK_CALL_PREV(bridge_connect, fd, address, length); }
    sockaddr_storage redirected_address{};
    socklen_t redirected_length;
    if (address->sa_family == AF_INET) {
        auto* target = reinterpret_cast<const sockaddr_in*>(address);
        packet[36] = 4;
        std::memcpy(packet.data() + 37, &target->sin_port, 2);
        std::memcpy(packet.data() + 43, &target->sin_addr, 4);
        auto* loopback = reinterpret_cast<sockaddr_in*>(&redirected_address);
        loopback->sin_family = AF_INET;
        loopback->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        loopback->sin_port = htons(static_cast<uint16_t>(port));
        redirected_length = sizeof(sockaddr_in);
    } else {
        auto* target = reinterpret_cast<const sockaddr_in6*>(address);
        const bool mapped = IN6_IS_ADDR_V4MAPPED(&target->sin6_addr);
        packet[36] = mapped ? 4 : 6;
        std::memcpy(packet.data() + 37, &target->sin6_port, 2);
        uint32_t scope = htonl(target->sin6_scope_id);
        std::memcpy(packet.data() + 39, &scope, 4);
        std::memcpy(packet.data() + 43, target->sin6_addr.s6_addr + (mapped ? 12 : 0), mapped ? 4 : 16);
        auto* loopback = reinterpret_cast<sockaddr_in6*>(&redirected_address);
        loopback->sin6_family = AF_INET6;
        loopback->sin6_port = htons(static_cast<uint16_t>(port));
        if (mapped) inet_pton(AF_INET6, "::ffff:127.0.0.1", &loopback->sin6_addr);
        else loopback->sin6_addr = in6addr_loopback;
        redirected_length = sizeof(sockaddr_in6);
    }
    errno = saved_errno;
    int result = SHADOWHOOK_CALL_PREV(bridge_connect, fd, reinterpret_cast<sockaddr*>(&redirected_address), redirected_length);
    int result_errno = errno;
    if (result < 0 && result_errno == EINPROGRESS) {
        pollfd event{fd, POLLOUT, 0};
        int ready;
        do { ready = poll(&event, 1, 500); } while (ready < 0 && errno == EINTR);
        int error = 0;
        socklen_t error_size = sizeof(error);
        if (ready <= 0 || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &error_size) || error) {
            errno = error ? error : ETIMEDOUT;
            return -1;
        }
    } else if (result < 0) { errno = result_errno; return result; }
    if (!send_prelude(fd, packet)) { errno = EPIPE; return -1; }
    redirected.fetch_add(1, std::memory_order_relaxed);
    errno = result_errno;
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kazumibridge_hook_NativeRuntime_install(JNIEnv*, jobject) {
    if (hook_stub) return 0;
    // Keep initialization details available for framework logs; disable verbose output
    // immediately afterwards so playback does not produce per-connection native logs.
    shadowhook_set_recordable(true);
    int status = shadowhook_init(SHADOWHOOK_MODE_SHARED, true);
    std::string loader_error;
    if (status) { const char* error = dlerror(); if (error) loader_error = error; }
    shadowhook_set_debuggable(false);
    if (status) { describe_install("shadowhook-init", status, loader_error.empty() ? nullptr : loader_error.c_str()); return status; }
    hook_stub = shadowhook_hook_sym_name_2("libc.so", "connect", reinterpret_cast<void*>(bridge_connect),
        &original_connect, SHADOWHOOK_HOOK_WITH_SHARED_MODE);
    if (hook_stub) {
        // Flutter code assets can rename libmpv; resolve its exported symbol in each app library.
        shadowhook_register_dl_init_callback(nullptr, player_library_loaded, nullptr);
        dl_iterate_phdr(inspect_loaded_library, nullptr);
    }
    status = hook_stub ? 0 : shadowhook_get_errno();
    describe_install("connect-hook", status);
    shadowhook_set_recordable(false);
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kazumibridge_hook_NativeRuntime_configure(JNIEnv* env, jobject, jint port, jbyteArray token, jboolean active) {
    enabled.store(false, std::memory_order_release);
    if (!token || env->GetArrayLength(token) != 32 || port <= 0 || port > 65535 || !active) return;
    {
        std::lock_guard<std::mutex> guard(config_mutex);
        env->GetByteArrayRegion(token, 0, 32, reinterpret_cast<jbyte*>(relay_token.data()));
        relay_port.store(port);
    }
    enabled.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kazumibridge_hook_NativeRuntime_redirectCount(JNIEnv*, jobject) {
    return static_cast<jlong>(redirected.load());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kazumibridge_hook_NativeRuntime_diagnostic(JNIEnv* env, jobject) {
    std::string player;
    { std::lock_guard<std::mutex> guard(header_hook_mutex); player = header_library; }
    size_t header_bytes;
    { std::lock_guard<std::mutex> guard(config_mutex); header_bytes = source_headers.size(); }
    const auto diagnostic = install_diagnostic + "; headerHook=" + (header_hook_stub.load() ? "installed" : "not-found") +
        "; headerHookStatus=" + std::to_string(header_hook_status.load()) + "; headerCalls=" + std::to_string(header_calls.load()) +
        "; headerFormat=" + std::to_string(header_format.load()) + "; headerNodeFormat=" + std::to_string(header_node_format.load()) +
        "; headerBytes=" + std::to_string(header_bytes) + "; playerLibrary=" + (player.empty() ? "unknown" : player);
    return env->NewStringUTF(diagnostic.c_str());
}
