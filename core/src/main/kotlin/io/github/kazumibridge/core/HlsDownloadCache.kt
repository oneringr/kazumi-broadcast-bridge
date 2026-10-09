package io.github.kazumibridge.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded raw-resource downloads; FFmpeg still owns demuxing, decryption and ordering. */
class HlsDownloadCache internal constructor(
    private val options: Options,
    private val headers: Map<String, String>,
    private val canceled: () -> Boolean
) : AutoCloseable {
    data class Options(
        val directory: File,
        val parallelism: Int = 4,
        val maxBytes: Long,
        val otherBytes: () -> Long = { 0 },
        val reserveFreeBytes: Long = 128L * 1024 * 1024,
        val diagnostic: (String) -> Unit = {}
    )

    /** URL-free outcomes; totalBytes includes bytes received on incomplete/retried attempts. */
    data class Stats(val registered: Int, val completed: Int, val failed: Int, val totalBytes: Long,
        val peakActive: Int, val active: Int, val pending: Int)

    internal class Resource(val source: String, val path: String, val file: File) {
        val partial = File(file.parentFile, "${file.name}.part")
        var task: FutureTask<File>? = null
        var accounted = 0L
        var completed = false
        var failed = false
        var lookahead: Pair<List<Resource>, Int>? = null
    }

    private class HttpFailure(val status: Int, val retryAfterMillis: Long) : IOException()
    private class CacheFailure(message: String) : IOException(message)
    private val lock = Any()
    private val directory = File(options.directory, "hls-${UUID.randomUUID()}")
    private val resources = linkedMapOf<String, Resource>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val outputs = ConcurrentHashMap.newKeySet<FileOutputStream>()
    private val workers = Executors.newFixedThreadPool(options.parallelism) { task ->
        Thread(task, "KazumiBridge-HLS-download").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile var failure: String? = null
        private set
    private var used = 0L
    private var totalBytes = 0L
    private var active = 0
    private var peakActive = 0
    val bytes: Long get() = synchronized(lock) { used }
    val stats: Stats get() = synchronized(lock) {
        val completed = resources.values.count { it.completed }
        val failed = resources.values.count { it.failed }
        Stats(resources.size, completed, failed, totalBytes, peakActive, active, resources.size - completed - failed)
    }

    init {
        require(options.parallelism in setOf(1, 2, 4, 8)) { "HLS 下载并发数必须为 1、2、4 或 8。" }
        require(options.maxBytes > 0 && options.reserveFreeBytes >= 0) { "HLS 缓存限额无效。" }
        check((options.directory.isDirectory || options.directory.mkdirs()) && directory.mkdir()) { "无法创建 HLS 分片缓存。" }
    }

    internal fun register(source: String): Resource = synchronized(lock) {
        checkActive()
        resources[source]?.let { return@synchronized it }
        require(resources.size < 32768) { "HLS 媒体资源数量超过上限。" }
        val uri = URI(source)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "HLS 媒体资源必须是有效的 HTTP 或 HTTPS 地址。"
        }
        val suffix = uri.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "bin")
            .takeIf { it.length in 1..12 && it.all(Char::isLetterOrDigit) } ?: "bin"
        val name = "${resources.size}.$suffix"
        Resource(source, "/$name", File(directory, name)).also { resources[source] = it }
    }

    internal fun playlist(items: List<Resource>) = synchronized(lock) {
        items.forEachIndexed { index, resource ->
            if (resource.lookahead == null) resource.lookahead = items to index
        }
    }

    internal fun diagnostic(message: String) { runCatching { options.diagnostic(message) } }

    private fun checkActive() {
        if (closed || canceled() || Thread.currentThread().isInterrupted) throw CancellationException("投屏已停止。")
        failure?.let { throw CacheFailure(it) }
    }

    private fun schedule(resource: Resource): FutureTask<File> = synchronized(lock) {
        checkActive()
        resource.task?.let { return@synchronized it }
        FutureTask<File> { download(resource) }.also { task ->
            resource.task = task
            workers.execute(task)
        }
    }

    private fun acquire(resource: Resource, prefetch: Boolean): File {
        val task = schedule(resource)
        if (prefetch) {
            val plan = synchronized(lock) { resource.lookahead }
            plan?.let { (items, index) ->
                // A demand request opens at most two windows per worker, never the whole episode.
                val end = minOf(items.size, index + options.parallelism * 2)
                for (next in index + 1 until end) schedule(items[next])
            }
        }
        while (true) {
            checkActive()
            try {
                return task.get(250, TimeUnit.MILLISECONDS).also { checkActive() }
            } catch (_: TimeoutException) {
                // Recheck cancellation even for a queued resource canceled during shutdown.
            } catch (_: ExecutionException) {
                throw CacheFailure(failure ?: "HLS 媒体资源下载失败。")
            }
        }
    }

    internal fun serve(resource: Resource, request: HttpRequest, output: OutputStream) {
        val head = request.method == "HEAD"
        val file = acquire(resource, prefetch = !head)
        val size = file.length()
        val range = ByteRanges.parse(request.header("range"), size)
        if (range == RangeResult.Invalid) {
            HttpResponse(416, "Range Not Satisfiable", mapOf("content-range" to "bytes */$size")).writeTo(output, head)
            return
        }
        val selection = if (range is RangeResult.Partial) range.range else ByteRange(0, size - 1)
        val responseHeaders = mapOf("content-type" to "application/octet-stream", "Cache-Control" to "no-store",
            "accept-ranges" to "bytes", "content-length" to selection.length.toString()) +
            if (range is RangeResult.Partial) mapOf("content-range" to "bytes ${selection.start}-${selection.end}/$size") else emptyMap()
        HttpResponse(if (range is RangeResult.Partial) 206 else 200,
            if (range is RangeResult.Partial) "Partial Content" else "OK", responseHeaders).writeTo(output, true)
        if (!head) RandomAccessFile(file, "r").use { input ->
            input.seek(selection.start)
            var remaining = selection.length
            val buffer = ByteArray(65536)
            while (remaining > 0) {
                checkActive()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw CacheFailure("HLS 分片缓存不完整。")
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        output.flush()
    }

    private fun reserve(resource: Resource, count: Int) = synchronized(lock) {
        checkActive()
        val other = options.otherBytes().coerceAtLeast(0)
        if (other > options.maxBytes || used > options.maxBytes - other - count) {
            throw CacheFailure("HLS 分片与视频缓存合计已达到设定上限。")
        }
        if (directory.usableSpace < options.reserveFreeBytes + count) {
            throw CacheFailure("手机存储剩余空间不足，已停止 HLS 下载。")
        }
        resource.accounted += count
        used += count
    }

    private fun discard(resource: Resource) {
        resource.partial.delete()
        resource.file.delete()
        synchronized(lock) { used -= resource.accounted; resource.accounted = 0 }
    }

    private fun connection(source: String): HttpURLConnection {
        val uri = URI(source)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "HLS 资源重定向必须是 HTTP 或 HTTPS 地址。"
        }
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = false
            headers.forEach(::setRequestProperty)
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun redirect(source: String, location: String): String {
        val base = URI(source)
        val relative = URI(location)
        // Match manifest resolution: a query-only Location retains the resource filename.
        val target = if (!relative.isAbsolute && relative.rawAuthority == null && relative.rawPath.orEmpty().isEmpty()) {
            URI(base.toASCIIString().substringBefore('#').substringBefore('?') +
                ((relative.rawQuery ?: base.rawQuery)?.let { "?$it" } ?: "") +
                (relative.rawFragment?.let { "#$it" } ?: ""))
        } else base.resolve(relative)
        require(target.scheme in setOf("http", "https") && !target.host.isNullOrBlank() && target.rawUserInfo == null) {
            "HLS 资源重定向必须是 HTTP 或 HTTPS 地址。"
        }
        return target.toASCIIString()
    }

    private fun downloadAttempt(resource: Resource): File {
        var source = resource.source
        for (redirect in 0..5) {
            checkActive()
            val active = connection(source)
            connections.add(active)
            try {
                checkActive()
                val status = active.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val target = active.getHeaderField("Location") ?: throw CacheFailure("HLS 资源重定向缺少地址。")
                    if (redirect == 5) throw CacheFailure("HLS 资源重定向次数超过上限。")
                    source = redirect(source, target)
                    continue
                }
                if (status != 200) {
                    val delay = active.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, 2)?.times(1000) ?: 0
                    throw HttpFailure(status, delay)
                }
                val expected = active.contentLengthLong
                active.inputStream.use { input ->
                    val output = synchronized(lock) {
                        checkActive()
                        FileOutputStream(resource.partial).also(outputs::add)
                    }
                    try {
                        val buffer = ByteArray(65536)
                        var received = 0L
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            synchronized(lock) { totalBytes += count }
                            reserve(resource, count)
                            output.write(buffer, 0, count)
                            received += count
                        }
                        if (expected >= 0 && received != expected) throw IOException("Truncated HLS resource")
                        if (received == 0L) throw CacheFailure("HLS 媒体资源为空。")
                    } finally {
                        outputs.remove(output)
                        output.close()
                    }
                }
                checkActive()
                // Completed resources appear atomically; clients never receive a partial download.
                try {
                    Files.move(resource.partial.toPath(), resource.file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    // A resource stays unavailable until this task completes, including this fallback.
                    Files.move(resource.partial.toPath(), resource.file.toPath())
                }
                checkActive()
                return resource.file
            } finally {
                connections.remove(active)
                active.disconnect()
            }
        }
        throw CacheFailure("HLS 资源重定向次数超过上限。")
    }

    private fun download(resource: Resource): File {
        synchronized(lock) {
            checkActive()
            active++
            peakActive = maxOf(peakActive, active)
        }
        try {
            for (attempt in 0..2) {
                checkActive()
                try {
                    return downloadAttempt(resource).also { synchronized(lock) { resource.completed = true } }
                } catch (error: Exception) {
                    discard(resource)
                    checkActive()
                    val retryable = when (error) {
                        is HttpFailure -> error.status == 429 || error.status in 500..599
                        is CacheFailure, is CancellationException, is IllegalArgumentException -> false
                        else -> error is IOException
                    }
                    if (!retryable || attempt == 2) throw error
                    val pause = maxOf((attempt + 1) * 500L, (error as? HttpFailure)?.retryAfterMillis ?: 0)
                    diagnostic("HLS resource retry=${attempt + 1}; status=${(error as? HttpFailure)?.status ?: "network"}")
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pause)
                    while (System.nanoTime() < until) { checkActive(); Thread.sleep(50) }
                }
            }
            throw CacheFailure("HLS 媒体资源下载失败。")
        } catch (error: Exception) {
            if (!closed && !canceled() && error !is CancellationException && failure == null) {
                fail(resource, when (error) {
                    is HttpFailure -> "HLS 媒体资源返回 HTTP ${error.status}，已停止转换。"
                    is CacheFailure -> error.message ?: "HLS 媒体资源下载失败。"
                    else -> "HLS 媒体资源下载失败（${error.javaClass.simpleName}）。"
                })
            }
            throw error
        } finally {
            synchronized(lock) { active-- }
            if (closed || canceled() || failure != null) discard(resource)
            if (closed) directory.delete()
        }
    }

    private fun fail(resource: Resource, message: String) {
        synchronized(lock) { if (failure != null) return; resource.failed = true; failure = message }
        diagnostic(message)
        interruptTransfers()
        synchronized(lock) { resources.values.forEach { it.task?.cancel(true) } }
        workers.shutdownNow()
    }

    override fun close() {
        closed = true
        interruptTransfers()
        synchronized(lock) { resources.values.forEach { it.task?.cancel(true) } }
        workers.shutdownNow()
        // Output handles close before waiting, allowing immediate cleanup even if an HTTP read stalls.
        runCatching { workers.awaitTermination(2, TimeUnit.SECONDS) }
        synchronized(lock) { resources.values.toList() }.forEach(::discard)
        directory.delete()
        diagnostic("HLS download close: $stats")
    }

    private fun interruptTransfers() {
        synchronized(lock) { outputs.toList() }.forEach { runCatching { it.close() } }
        val active = connections.toList()
        if (active.isNotEmpty()) Thread({
            active.forEach { runCatching { it.disconnect() } }
        }, "KazumiBridge-HLS-disconnect").apply { isDaemon = true; start() }
    }
}
