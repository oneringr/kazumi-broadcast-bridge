package io.github.kazumibridge.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class GitHubRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val publishedDate: String,
    val pageUrl: String,
)

internal object GitHubReleases {
    const val REPOSITORY_URL = "https://github.com/oneringr/kazumi-broadcast-bridge"
    const val LATEST_PAGE_URL = "$REPOSITORY_URL/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/oneringr/kazumi-broadcast-bridge/releases/latest"
    private const val RELEASE_PATH = "/oneringr/kazumi-broadcast-bridge/releases/tag/"
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_NOTES_CHARS = 20_000
    // Cancellation can originate on the UI thread. Reuse one IO worker for cleanup.
    private val disconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    suspend fun latest(): GitHubRelease = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "KazumiBroadcastBridge")
            }
            val disconnected = AtomicBoolean(false)
            fun disconnectOnce() {
                if (disconnected.compareAndSet(false, true)) runCatching { connection.disconnect() }
            }
            continuation.invokeOnCancellation { disconnectScope.launch { disconnectOnce() } }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> Unit
                    HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("暂时没有已发布的稳定版本。")
                    HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw IOException("GitHub 请求受限，请稍后重试。")
                    else -> throw IOException("GitHub 暂时不可用，请稍后重试。")
                }
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("更新信息过大，请直接查看 GitHub Release。")
                val response = ByteArrayOutputStream().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (continuation.isActive) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("更新信息过大，请直接查看 GitHub Release。")
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                if (continuation.isActive) continuation.resume(parse(response))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                disconnectOnce()
            }
        }
    }

    internal fun parse(response: String): GitHubRelease {
        val json = JSONObject(response)
        if (json.optBoolean("draft", true) || json.optBoolean("prerelease", true)) {
            throw IOException("暂时没有可用的稳定版本。")
        }
        val tag = json.optString("tag_name").trim()
        val pageUrl = json.optString("html_url")
        if (tag.isEmpty() || tag.length > 128 || !isReleasePage(pageUrl)) throw IOException("更新信息格式异常。")
        val notes = (if (json.isNull("body")) "" else json.optString("body")).ifBlank { "此版本暂无更新说明。" }
        return GitHubRelease(
            tag = tag,
            name = (if (json.isNull("name")) "" else json.optString("name")).ifBlank { tag }.take(160),
            notes = if (notes.length > MAX_NOTES_CHARS) notes.take(MAX_NOTES_CHARS) + "\n\n更多内容请查看 GitHub Release。" else notes,
            publishedDate = json.optString("published_at").take(10),
            pageUrl = pageUrl,
        )
    }

    internal fun isReleasePage(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.query == null && uri.fragment == null &&
            uri.path.startsWith(RELEASE_PATH) && uri.path.length > RELEASE_PATH.length &&
            uri.normalize().path == uri.path
    }.getOrDefault(false)
}

/** Compares numeric release segments without treating 1.0.12 as a decimal number. */
internal fun compareReleaseVersions(latest: String, current: String): Int? {
    fun segments(value: String): List<String>? {
        val numeric = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:\\+[0-9A-Za-z.-]+)?$")
            .matchEntire(value.trim())?.groupValues?.get(1) ?: return null
        return numeric.split('.').takeIf { it.size <= 16 }?.map { it.trimStart('0').ifEmpty { "0" } }
    }
    val left = segments(latest) ?: return null
    val right = segments(current) ?: return null
    repeat(maxOf(left.size, right.size)) { index ->
        val a = left.getOrElse(index) { "0" }
        val b = right.getOrElse(index) { "0" }
        val comparison = a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
        if (comparison != 0) return comparison
    }
    return 0
}
