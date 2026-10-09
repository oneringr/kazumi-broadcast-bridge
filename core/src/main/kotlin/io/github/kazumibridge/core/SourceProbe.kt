package io.github.kazumibridge.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object SourceProbe {
    private const val MAX_MANIFEST = 1024 * 1024
    data class Content(val prefix: ByteArray, val manifest: ByteArray?, val resolvedSource: String)
    class HttpFailure(val status: Int, val expired: Boolean = false) : IllegalStateException(
        if (expired) "来源链接已过期；请在 Kazumi 重新解析当前剧集后再投屏。" else "视频来源返回 HTTP $status，请检查来源请求头。")
    data class Response(val status: Int, val ranged: Boolean, val length: Long, val range: String?, val server: String?, val type: String?, val errorTitle: String?, val serverTimeOffsetSeconds: Long? = null)

    fun readPrefix(source: String, headers: Map<String, String>, canceled: () -> Boolean = { false },
        connectionChanged: (HttpURLConnection?) -> Unit = {}, response: (Response) -> Unit = {}): ByteArray =
        readSource(source, headers, canceled, connectionChanged, response, false).prefix

    fun readContent(source: String, headers: Map<String, String>, canceled: () -> Boolean = { false },
        connectionChanged: (HttpURLConnection?) -> Unit = {}, response: (Response) -> Unit = {}): Content =
        readSource(source, headers, canceled, connectionChanged, response, true)

    private fun readSource(source: String, headers: Map<String, String>, canceled: () -> Boolean,
        connectionChanged: (HttpURLConnection?) -> Unit, response: (Response) -> Unit, captureManifest: Boolean): Content {
        val manifestHint = captureManifest && java.net.URI(source).path.orEmpty().endsWith(".m3u8", true)
        for (ranged in if (manifestHint) listOf(false) else listOf(true, false)) {
            val connection = URL(source).openConnection() as HttpURLConnection
            connectionChanged(connection)
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            headers.forEach(connection::setRequestProperty)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (ranged) connection.setRequestProperty("Range", "bytes=0-65535")
            try {
                val status = connection.responseCode
                val errorBody = if (status >= 400) connection.errorStream?.use { read(it, 2048, canceled) }?.toString(Charsets.UTF_8).orEmpty() else ""
                val title = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(errorBody)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.take(120)
                    ?: errorBody.takeIf { !it.contains('<') }?.replace(Regex("\\s+"), " ")?.take(120)
                response(Response(status, ranged, connection.contentLengthLong, connection.getHeaderField("Content-Range"),
                    connection.getHeaderField("Server"), connection.contentType, title,
                    connection.getHeaderFieldDate("Date", -1).takeIf { it >= 0 }?.let { it / 1000 - System.currentTimeMillis() / 1000 }))
                if (ranged && status in setOf(400, 403, 416) && !canceled()) continue
                if (status !in 200..299) throw HttpFailure(status, errorBody.contains("已经过期") || errorBody.contains("链接已过期"))
                return connection.inputStream.use { input ->
                    val prefix = read(input, 65536, canceled)
                    if (!captureManifest || !MediaPolicy.isHls(prefix)) return@use Content(prefix, null, connection.url.toString())
                    if (status == 206) {
                        if (ranged) return@use null
                        error("HLS 清单响应不完整，无法复用。")
                    }
                    val remainder = read(input, MAX_MANIFEST - prefix.size + 1, canceled)
                    require(prefix.size + remainder.size <= MAX_MANIFEST) { "HLS 清单超过 1 MiB，无法复用。" }
                    Content(prefix, prefix + remainder, connection.url.toString())
                } ?: continue
            } finally {
                connection.disconnect()
                connectionChanged(null)
            }
        }
        error("无法读取视频来源。")
    }

    /** Safe diagnostics: no path, query values, credentials or signed URLs. */
    fun addressDiagnostic(source: String, nowMillis: Long = System.currentTimeMillis()): String {
        val uri = java.net.URI(source)
        val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        val keys = query.map { it.first.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32) }.take(12)
        val remaining = query.mapNotNull { (key, value) ->
            if (key.lowercase() !in setOf("expires", "expire", "exp", "timestamp", "time", "t")) return@mapNotNull null
            val epoch = value.toLongOrNull()?.takeIf { it > 1_000_000_000L } ?: return@mapNotNull null
            key to (if (epoch > 1_000_000_000_000L) epoch / 1000 else epoch) - nowMillis / 1000
        }
        return "host=${uri.host}; pathLength=${uri.rawPath.orEmpty().length}; queryKeys=$keys; timeOffsets=$remaining"
    }

    /** Keep a failed native request's role without exposing paths or signed queries. */
    fun redactLocations(message: String, source: String): String = message.replace(Regex("https?://[^\\s\\\"']+")) { match ->
        val candidate = match.value.trimEnd(']', ')', ',', ';', ':')
        val uri = runCatching { java.net.URI(candidate) }.getOrNull()
        val host = uri?.host ?: "unknown"
        val suffix = uri?.path.orEmpty().substringAfterLast('.', "").replace(Regex("[^a-zA-Z0-9]"), "_").take(12)
        if (candidate == source) "[来源入口 host=$host]" else "[媒体 host=$host; suffix=$suffix]"
    }

    private fun read(input: InputStream, limit: Int, canceled: () -> Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() < limit && !canceled()) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
