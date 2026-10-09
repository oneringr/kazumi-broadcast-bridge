package io.github.kazumibridge.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException

/** A complete, bounded source download. The caller owns either disk or memory storage. */
object CachedMp4Download {
    data class Result(val bytes: Long)

    private data class Validator(val etag: String?, val modified: String?) {
        val ifRange: String? get() = etag ?: modified
    }
    private data class ContentRange(val start: Long, val end: Long, val total: Long)
    private class RetryableFailure(val delayMillis: Long) : IOException()

    /**
     * [maxBytes] is an exclusive threshold: reaching it stops the download.
     * [reset] clears previously written data; [write] must consume the buffer before returning.
     * Storage callback failures propagate directly and are never retried as network failures.
     */
    fun download(
        source: String,
        headers: Map<String, String>,
        maxBytes: Long,
        canceled: () -> Boolean = { false },
        connectionChanged: (HttpURLConnection?) -> Unit = {},
        reset: () -> Unit,
        write: (offset: Long, data: ByteArray, count: Int) -> Unit,
        progress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }
    ): Result {
        require(maxBytes > 0) { "MP4 缓存上限无效。" }
        validUri(source)
        require(headers.all { (key, value) ->
            key.isNotEmpty() && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" } &&
                value.none { (it < ' ' && it != '\t') || it == '\u007f' }
        }) { "MP4 请求头无效。" }
        var downloaded = 0L
        var total: Long? = null
        var expectedValidator: Validator? = null
        var retries = 0
        var resets = 0
        val buffer = ByteArray(64 * 1024)

        fun checkActive() {
            if (canceled() || Thread.currentThread().isInterrupted) {
                throw CancellationException("投屏已停止。")
            }
        }
        fun checkTotal(length: Long?) {
            if (length != null) {
                if (length <= 0) throw IOException("MP4 视频为空。")
                if (length >= maxBytes) throw IOException("MP4 缓存已达到设定上限。")
                if (total != null && total != length) throw IOException("MP4 来源长度已变化，请重新解析视频。")
                total = length
            }
        }
        fun retry(delay: Long) {
            checkActive()
            if (retries++ >= 3) throw IOException("MP4 视频下载不完整，重连次数已达上限。")
            var remaining = delay.coerceIn(0, 2000)
            while (remaining > 0) {
                checkActive()
                val pause = minOf(remaining, 50)
                try { Thread.sleep(pause) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    checkActive()
                }
                remaining -= pause
            }
        }

        checkActive()
        reset()
        progress(0, null)
        while (true) {
            checkActive()
            var connection: HttpURLConnection? = null
            var input: java.io.InputStream? = null
            var retryDelay: Long? = null
            try {
                // Only operations on the network are classified as retryable.
                val opened = try {
                    open(source, headers, downloaded, expectedValidator?.ifRange, canceled, connectionChanged)
                } catch (error: RetryableFailure) {
                    retryDelay = error.delayMillis
                    null
                } catch (_: IOException) {
                    checkActive()
                    retryDelay = 200
                    null
                }
                if (opened == null) {
                    retry(requireNotNull(retryDelay))
                    continue
                }
                connection = opened
                checkActive()
                val status = opened.responseCode
                if (status != 200 && status != 206) {
                    throw IOException("MP4 下载服务器返回 HTTP $status。")
                }
                if (opened.getHeaderField("Content-Encoding")?.let { !it.equals("identity", true) } == true) {
                    throw IOException("MP4 下载返回了不支持的内容编码。")
                }
                val currentValidator = validator(opened)
                if (expectedValidator != null && changed(requireNotNull(expectedValidator), currentValidator)) {
                    throw IOException("MP4 来源已变化，请重新解析视频。")
                }
                if (expectedValidator == null) expectedValidator = currentValidator
                if (downloaded > 0 && status == 200) {
                    if (resets++ >= 1) throw IOException("MP4 来源不支持断点续传，下载中断。")
                    // Ignoring Range is legal; its full body must replace the partial copy.
                    reset()
                    downloaded = 0
                    progress(0, total)
                }
                val contentLength = opened.getHeaderField("Content-Length")?.let { value ->
                    value.trim().toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw IOException("MP4 下载长度无效。")
                }
                val responseEnd: Long?
                if (status == 206) {
                    val range = parseRange(opened.getHeaderField("Content-Range"))
                    if (range.start != downloaded) throw IOException("MP4 断点续传位置不匹配。")
                    checkTotal(range.total)
                    if (contentLength != null && contentLength != range.end - range.start + 1) {
                        throw IOException("MP4 断点续传长度不匹配。")
                    }
                    responseEnd = range.end + 1
                } else {
                    checkTotal(contentLength)
                    responseEnd = contentLength
                }
                progress(downloaded, total)
                input = try { opened.inputStream } catch (_: IOException) {
                    checkActive()
                    retryDelay = 200
                    null
                }
                if (input != null) {
                    while (true) {
                        checkActive()
                        val count = try { input.read(buffer) } catch (_: IOException) {
                            checkActive()
                            retryDelay = 200
                            break
                        }
                        if (count < 0) {
                            if ((responseEnd != null && downloaded != responseEnd) ||
                                (total != null && downloaded != total)) {
                                retryDelay = 200
                            } else {
                                if (downloaded == 0L) throw IOException("MP4 视频为空。")
                                checkActive()
                                return Result(downloaded)
                            }
                            break
                        }
                        if (count == 0) continue
                        if (downloaded >= maxBytes - count) throw IOException("MP4 缓存已达到设定上限。")
                        if (responseEnd != null && downloaded > responseEnd - count) {
                            throw IOException("MP4 下载内容超过声明长度。")
                        }
                        checkActive()
                        write(downloaded, buffer, count)
                        downloaded += count
                        progress(downloaded, total)
                    }
                }
            } finally {
                runCatching { input?.close() }
                connection?.disconnect()
                if (connection != null) connectionChanged(null)
            }
            retry(requireNotNull(retryDelay))
        }
    }

    private fun validUri(source: String): URI {
        val uri = try { URI(source) } catch (_: Exception) {
            throw IllegalArgumentException("MP4 来源必须是有效的 HTTP 或 HTTPS 地址。")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null) {
            "MP4 来源必须是有效的 HTTP 或 HTTPS 地址。"
        }
        return uri
    }

    private fun resolve(source: String, location: String): String {
        val base = validUri(source)
        val relative = try { URI(location) } catch (_: Exception) {
            throw IOException("MP4 下载重定向地址无效。")
        }
        // URI.resolve otherwise drops the filename when Location contains only a query.
        val target = if (!relative.isAbsolute && relative.rawAuthority == null && relative.rawPath.orEmpty().isEmpty()) {
            base.toASCIIString().substringBefore('?') +
                ((relative.rawQuery ?: base.rawQuery)?.let { "?$it" } ?: "")
        } else base.resolve(relative).toASCIIString()
        return try { validUri(target).toASCIIString() } catch (_: IllegalArgumentException) {
            throw IOException("MP4 下载重定向地址无效。")
        }
    }

    private fun open(
        source: String, headers: Map<String, String>, offset: Long, ifRange: String?,
        canceled: () -> Boolean, changed: (HttpURLConnection?) -> Unit
    ): HttpURLConnection {
        var destination = source
        for (redirect in 0..5) {
            if (canceled() || Thread.currentThread().isInterrupted) throw CancellationException("投屏已停止。")
            val connection = validUri(destination).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                headers.filterKeys { key ->
                    !key.equals("Range", true) && !key.equals("If-Range", true) && !key.equals("Accept-Encoding", true)
                }.forEach(connection::setRequestProperty)
                connection.setRequestProperty("Accept-Encoding", "identity")
                // A captured media request must not constrain this complete download to its old range.
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                if (offset > 0 && ifRange != null) connection.setRequestProperty("If-Range", ifRange)
                changed(connection)
                if (canceled() || Thread.currentThread().isInterrupted) throw CancellationException("投屏已停止。")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == 5) throw IOException("MP4 下载重定向次数过多。")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("MP4 下载重定向缺少目标地址。")
                    destination = resolve(destination, location)
                    connection.disconnect()
                    changed(null)
                    continue
                }
                if (status == 429 || status in 500..599) {
                    val delay = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        ?.coerceIn(0, 2)?.times(1000) ?: 200L
                    throw RetryableFailure(delay)
                }
                return connection
            } catch (error: Exception) {
                connection.disconnect()
                changed(null)
                throw error
            }
        }
        throw IOException("MP4 下载重定向次数过多。")
    }

    private fun validator(connection: HttpURLConnection): Validator {
        val etag = connection.getHeaderField("ETag")?.trim()?.takeIf {
            it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/", true)
        }
        return Validator(etag, connection.getHeaderField("Last-Modified")?.trim()?.takeIf(String::isNotEmpty))
    }

    private fun changed(previous: Validator, current: Validator): Boolean = when {
        previous.etag != null && current.etag != null -> previous.etag != current.etag
        previous.modified != null && current.modified != null -> previous.modified != current.modified
        else -> false
    }

    private fun parseRange(value: String?): ContentRange {
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim().orEmpty()) ?: throw IOException("MP4 断点续传缺少有效的 Content-Range。")
        val numbers = match.groupValues.drop(1).map {
            it.toLongOrNull() ?: throw IOException("MP4 断点续传长度无效。")
        }
        val (start, end, total) = numbers
        if (start > end || total <= end) throw IOException("MP4 断点续传长度无效。")
        return ContentRange(start, end, total)
    }
}
