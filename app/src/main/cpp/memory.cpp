#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <new>

namespace {
struct Block { size_t capacity; unsigned char* data; };
Block* block(jlong handle) { return reinterpret_cast<Block*>(static_cast<uintptr_t>(handle)); }
bool valid(JNIEnv* env, Block* value, jint offset, jbyteArray data, jint data_offset, jint count) {
    if (value && offset >= 0 && data && data_offset >= 0 && count >= 0 &&
        static_cast<size_t>(offset) <= value->capacity && static_cast<size_t>(count) <= value->capacity - offset &&
        data_offset <= env->GetArrayLength(data) && count <= env->GetArrayLength(data) - data_offset) return true;
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid memory cache range");
    return false;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kazumibridge_runtime_NativeMemory_allocate(JNIEnv*, jclass, jint capacity) {
    if (capacity <= 0 || capacity > 65536) return 0;
    auto* value = new (std::nothrow) Block{static_cast<size_t>(capacity), nullptr};
    if (!value) return 0;
    value->data = static_cast<unsigned char*>(std::calloc(static_cast<size_t>(capacity), 1));
    if (!value->data) { delete value; return 0; }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kazumibridge_runtime_NativeMemory_read(JNIEnv* env, jclass, jlong handle, jint offset, jbyteArray data, jint data_offset, jint count) {
    auto* value = block(handle);
    if (valid(env, value, offset, data, data_offset, count)) env->SetByteArrayRegion(data, data_offset, count,
        reinterpret_cast<const jbyte*>(value->data + offset));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kazumibridge_runtime_NativeMemory_write(JNIEnv* env, jclass, jlong handle, jint offset, jbyteArray data, jint data_offset, jint count) {
    auto* value = block(handle);
    if (valid(env, value, offset, data, data_offset, count)) env->GetByteArrayRegion(data, data_offset, count,
        reinterpret_cast<jbyte*>(value->data + offset));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kazumibridge_runtime_NativeMemory_release(JNIEnv*, jclass, jlong handle) {
    auto* value = block(handle);
    if (value) { std::free(value->data); delete value; }
}
