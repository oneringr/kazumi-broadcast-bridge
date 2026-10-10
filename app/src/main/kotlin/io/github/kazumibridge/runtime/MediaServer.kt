package io.github.kazumibridge.runtime

import io.github.kazumibridge.core.ByteRange
import io.github.kazumibridge.core.ByteRanges
import io.github.kazumibridge.core.HttpRequest
import io.github.kazumibridge.core.HttpResponse
import io.github.kazumibridge.core.HttpWire
import io.github.kazumibridge.core.RangeResult
import io.github.kazumibridge.core.RelayTarget
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class MediaServer : AutoCloseable {
    private val server = ServerSocket(0)
    private val jobs = ConcurrentHashMap<String, MediaJob>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(8)
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "KazumiBridge-video") }
    @Volatile private var closed = false

    init { workers.execute {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            if (!slots.tryAcquire()) { socket.close(); continue }
            clients.add(socket)
            workers.execute {
                try { socket.use(::handle) } catch (_: Exception) { /* The receiver can close Range/HEAD probes at any time. */ }
                finally { clients.remove(socket); slots.release() }
            }
        }
    } }

    fun add(job: MediaJob, target: RelayTarget): String {
        jobs[job.id] = job
        val host = DatagramSocket().use { route -> route.connect(InetSocketAddress(target.address, target.port)); route.localAddress.hostAddress!! }
        require(host != "0.0.0.0" && host != "::") { "无法确定连接投屏设备的本机地址。" }
        val authority = if (host.contains(':')) "[${host.replace("%", "%25")}]" else host
        return "http://$authority:${server.localPort}/media/${job.id}/video.mp4"
    }

    fun remove(job: MediaJob) { jobs.remove(job.id); if (job.session == null || job.complete) job.releaseMedia() }
    /** Hide a rejected duration candidate while its owner keeps the finalized output for review. */
    fun detach(job: MediaJob) { jobs.remove(job.id, job) }
    fun cacheBytes(): Long = jobs.values.sumOf { it.size }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10000
        val request = HttpWire.readRequest(socket.getInputStream().buffered(), socket.getOutputStream()) ?: return
        if (request.method !in setOf("GET", "HEAD")) { HttpResponse(405, "Method Not Allowed").writeTo(socket.getOutputStream()); return }
        val id = Regex("^/media/([a-f0-9]{32})/video\\.mp4(?:\\?.*)?$").matchEntire(request.path)?.groupValues?.get(1)
        val job = jobs[id]
        if (job == null || job.canceled || job.error != null) { HttpResponse(404, "Not Found").writeTo(socket.getOutputStream()); return }
        if (job.direct) serveDirect(socket, request, job) else serveFile(socket, request, job)
    }

    private fun commonHeaders(seekable: Boolean): Map<String, String> = mapOf(
        "content-type" to "video/mp4", "Cache-Control" to "no-store", "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")

    private fun serveFile(socket: Socket, request: HttpRequest, job: MediaJob) {
        val output = socket.getOutputStream().buffered()
        val head = request.method == "HEAD"
        if (job.complete) {
            val size = job.size
            val range = ByteRanges.parse(request.header("range"), size)
            if (range == RangeResult.Invalid) {
                HttpResponse(416, "Range Not Satisfiable", mapOf("content-range" to "bytes */$size")).writeTo(output, head)
                return
            }
            val selection = if (range is RangeResult.Partial) range.range else ByteRange(0, size - 1)
            val headers = commonHeaders(true) + mapOf("accept-ranges" to "bytes", "content-length" to selection.length.toString()) +
                (if (range is RangeResult.Partial) mapOf("content-range" to "bytes ${selection.start}-${selection.end}/$size") else emptyMap())
            HttpResponse(if (range is RangeResult.Partial) 206 else 200, if (range is RangeResult.Partial) "Partial Content" else "OK", headers).writeTo(output, true)
            if (!head) withMediaReader(job) { read ->
                var offset = selection.start
                var remaining = selection.length
                val buffer = ByteArray(65536)
                while (remaining > 0 && !closed && !job.canceled && job.error == null) {
                    val count = read(offset, buffer, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    offset += count
                    remaining -= count
                }
            }
            output.flush()
            return
        }
        val requestedRange = request.header("range")
        if (requestedRange != null && requestedRange !in setOf("bytes=0-", "bytes=0-0")) {
            HttpResponse(416, "Range Not Satisfiable", mapOf("content-range" to "bytes */${job.size}")).writeTo(output, head)
            BridgeState.event("接收设备请求拖动进度；分片生成期间不支持此操作。")
            return
        }
        val headers = commonHeaders(false) + mapOf("accept-ranges" to "none", "transfer-encoding" to "chunked", "connection" to "close")
        writeStreamingHeaders(output, 200, "OK", headers)
        if (head) { output.flush(); return }
        withMediaReader(job) { read ->
            val buffer = ByteArray(65536)
            var offset = 0L
            while (!job.canceled && job.error == null && !closed) {
                val count = read(offset, buffer, buffer.size)
                if (count > 0) {
                    output.write(count.toString(16).toByteArray(Charsets.US_ASCII)); output.write("\r\n".toByteArray())
                    output.write(buffer, 0, count); output.write("\r\n".toByteArray()); output.flush()
                    offset += count
                } else {
                    if (job.complete) break
                    synchronized(job.monitor) { if (!job.complete && !job.canceled) job.monitor.wait(250) }
                }
            }
            if (!job.canceled && job.error == null) { output.write("0\r\n\r\n".toByteArray()); output.flush() }
        }
    }

    private fun withMediaReader(job: MediaJob, action: ((Long, ByteArray, Int) -> Int) -> Unit) {
        val memory = job.memoryOutput?.buffer
        if (memory != null) {
            action { offset, bytes, count -> memory.read(offset, bytes, 0, count) }
        } else RandomAccessFile(job.file, "r").use { file ->
            action { offset, bytes, count -> file.seek(offset); file.read(bytes, 0, count) }
        }
    }

    private fun serveDirect(socket: Socket, request: HttpRequest, job: MediaJob) {
        val connection = URL(job.source).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = request.method
        job.settings.headers().forEach(connection::setRequestProperty)
        request.header("range")?.let { connection.setRequestProperty("Range", it) }
        request.header("if-range")?.let { connection.setRequestProperty("If-Range", it) }
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val status = connection.responseCode
            val headers = commonHeaders(true).toMutableMap()
            for (name in listOf("Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
                connection.getHeaderField(name)?.let { headers[name.lowercase(java.util.Locale.ROOT)] = it }
            }
            headers["connection"] = "close"
            val output = socket.getOutputStream().buffered()
            writeStreamingHeaders(output, status, connection.responseMessage ?: "OK", headers)
            if (request.method != "HEAD") {
                val input = if (status >= 400) connection.errorStream else connection.inputStream
                input?.use { stream ->
                    val buffer = ByteArray(65536)
                    while (!closed && !job.canceled) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            output.flush()
        } finally { connection.disconnect() }
    }

    private fun writeStreamingHeaders(output: OutputStream, status: Int, reason: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> require(!value.contains('\r') && !value.contains('\n')); append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    override fun close() {
        closed = true
        server.close()
        clients.forEach { runCatching { it.close() } }
        jobs.values.forEach { it.cancel(); remove(it) }
        workers.shutdownNow()
        jobs.clear()
    }
}
