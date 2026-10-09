package io.github.kazumibridge.core

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/** A cast owns this loopback server and, optionally, bounded downloads for finite VOD media. */
class HlsSnapshot private constructor(private val headers: Map<String, String>, download: HlsDownloadCache.Options?, explicitBudget: MemoryBudget?, private val canceled: () -> Boolean) : AutoCloseable {
    private val memoryBudget = explicitBudget ?: download?.memoryBudget?.takeIf { download.storage == CacheStorage.MEMORY }
    data class MediaStats(val segmentCount: Int, val extinfDurationSum: Double, val gapCount: Int,
        val gapDuration: Double, val discontinuityCount: Int, val keyMethods: Set<String>, val parallel: Boolean)
    private class Entry(val source: String, val path: String, val ancestors: Set<String>) {
        var bytes: ByteArray? = null
    }
    @Volatile private var closed = false
    private val downloads = download?.let { HlsDownloadCache(it, headers) { closed || canceled() } }
    private val server = try {
        ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4) }
    } catch (error: Exception) { downloads?.close(); throw error }
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val entries = linkedMapOf<String, Entry>()
    private val finiteMedia = linkedMapOf<String, MediaStats>()
    private val paths = ConcurrentHashMap<String, Entry>()
    private val mediaPaths = ConcurrentHashMap<String, HlsDownloadCache.Resource>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val slots = Semaphore(4)
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "KazumiBridge-HLS").apply { isDaemon = true } }
    private var storedBytes = 0
    @Volatile private var manifestFailure: String? = null
    val failure: String? get() = memoryBudget?.failure ?: downloads?.failure ?: manifestFailure
    val downloadBytes: Long get() = downloads?.bytes ?: 0
    val downloadStats: HlsDownloadCache.Stats? get() = downloads?.stats
    val mediaStats: List<MediaStats> get() = synchronized(entries) { finiteMedia.values.toList() }
    lateinit var url: String
        private set

    companion object {
        private val uriAttribute = Regex("(?<=[:,])\\s*URI=(?:\"([^\"]*)\"|([^,]*))", RegexOption.IGNORE_CASE)
        private fun master(text: String) = text.lineSequence().any {
            it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                it.startsWith("#EXT-X-IMAGE-STREAM-INF:") || it.startsWith("#EXT-X-MEDIA:")
        }
        fun create(source: String, manifest: ByteArray, headers: Map<String, String>, download: HlsDownloadCache.Options? = null, memoryBudget: MemoryBudget? = null, canceled: () -> Boolean = { false }): HlsSnapshot? {
            require(MediaPolicy.isHls(manifest)) { "来源不是 HLS 清单。" }
            val text = manifest.toString(Charsets.UTF_8).trimStart('\uFEFF')
            if (!master(text) && !text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" }) return null
            val snapshot = HlsSnapshot(headers.toMap(), download, memoryBudget, canceled)
            try {
                val root = snapshot.register(source, emptySet())
                root.bytes = snapshot.rewrite(root, manifest)
                snapshot.account(root.bytes!!.size)
                snapshot.url = snapshot.local(root)
                snapshot.start()
                return snapshot
            } catch (error: Throwable) {
                snapshot.close()
                throw error
            }
        }
    }

    private fun local(entry: Entry) = "http://127.0.0.1:${server.localPort}${entry.path}"
    private fun local(resource: HlsDownloadCache.Resource): String {
        val path = "/$token/media${resource.path}"
        mediaPaths[path] = resource
        return "http://127.0.0.1:${server.localPort}$path"
    }
    private fun register(source: String, ancestors: Set<String>): Entry = synchronized(entries) {
        require(source !in ancestors && ancestors.size < 5) { "HLS 子清单循环引用或层级过深。" }
        entries[source]?.let { return@synchronized it }
        require(entries.size < 32) { "HLS 子清单数量超过上限。" }
        Entry(source, "/$token/${entries.size}.m3u8", ancestors + source).also { entries[source] = it; paths[it.path] = it }
    }

    private fun resolve(source: String, reference: String): String {
        val uri = runCatching {
            val base = URI(source)
            val relative = URI(reference)
            // java.net.URI.resolve drops the filename for query-only references.
            if (!relative.isAbsolute && relative.rawAuthority == null && relative.rawPath.orEmpty().isEmpty()) {
                val query = relative.rawQuery ?: base.rawQuery
                URI(base.toASCIIString().substringBefore('#').substringBefore('?') +
                    (query?.let { "?$it" } ?: "") + (relative.rawFragment?.let { "#$it" } ?: ""))
            } else base.resolve(relative)
        }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "HLS 引用必须是有效的 HTTP 或 HTTPS 地址。"
        }
        return uri.toASCIIString()
    }

    private fun rewrite(entry: Entry, bytes: ByteArray): ByteArray {
        require(MediaPolicy.isHls(bytes)) { "HLS 子清单格式无效。" }
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val isMaster = master(text)
        val lines = text.lineSequence().map(String::trim).toList()
        val finite = !isMaster && lines.any { it == "#EXT-X-ENDLIST" }
        // A shared large BYTERANGE resource needs range-aware caching, so keep this entire
        // playlist on the existing remote path, including KEY/MAP resources.
        val ranged = lines.any { it.startsWith("#EXT-X-BYTERANGE:") ||
            (it.startsWith("#EXT-X-MAP:") && Regex("(?:^|,)\\s*BYTERANGE=", RegexOption.IGNORE_CASE).containsMatchIn(it.substringAfter(':'))) }
        val cached = downloads != null && finite && !ranged
        if (downloads != null && finite && ranged) downloads.diagnostic("HLS parallel cache bypass: BYTERANGE playlist")
        val segments = mutableListOf<HlsDownloadCache.Resource>()
        var gap = false
        val result = text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> ""
                !line.startsWith('#') -> {
                    val address = resolve(entry.source, line)
                    when {
                        isMaster -> local(register(address, entry.ancestors))
                        cached && !gap -> local(downloads!!.register(address).also(segments::add))
                        else -> address
                    }.also { gap = false }
                }
                else -> {
                    if (line == "#EXT-X-GAP") gap = true
                    uriAttribute.replace(line) { match ->
                        val reference = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty().trim()
                        val address = resolve(entry.source, reference)
                        val child = line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || line.startsWith("#EXT-X-IMAGE-STREAM-INF:")
                        val resource = line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:")
                        val replacement = when {
                            child -> local(register(address, entry.ancestors))
                            cached && resource -> local(downloads!!.register(address))
                            else -> address
                        }
                        "URI=\"$replacement\""
                    }
                }
            }
        }
        if (cached) downloads!!.playlist(segments)
        if (finite) describe(entry.source, lines, cached)
        return (result + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun describe(source: String, lines: List<String>, parallel: Boolean) {
        var segmentCount = 0
        var durationSum = 0.0
        var gapCount = 0
        var gapDuration = 0.0
        var discontinuities = 0
        var pendingDuration = 0.0
        var gap = false
        val methods = linkedSetOf<String>()
        for (line in lines) when {
            line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',')
                .toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            line == "#EXT-X-GAP" -> gap = true
            line == "#EXT-X-DISCONTINUITY" -> discontinuities++
            line.startsWith("#EXT-X-KEY:") -> {
                val value = Regex("(?:^|,)\\s*METHOD=([^,]*)").find(line.substringAfter(':'))?.groupValues?.get(1)?.trim()
                // Only standard enum values enter diagnostics; arbitrary attributes never do.
                methods.add(value?.takeIf { it in setOf("NONE", "AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR") } ?: "OTHER")
            }
            line.isNotEmpty() && !line.startsWith('#') -> {
                segmentCount++
                durationSum += pendingDuration
                if (gap) { gapCount++; gapDuration += pendingDuration }
                pendingDuration = 0.0
                gap = false
            }
        }
        val counts = MediaStats(segmentCount, durationSum, gapCount, gapDuration, discontinuities, methods.toSet(), parallel)
        val index = synchronized(entries) {
            finiteMedia[source] = counts
            finiteMedia.keys.indexOf(source)
        }
        downloads?.diagnostic("HLS finite playlist=$index: $counts")
    }

    private fun account(count: Int) = synchronized(entries) {
        check(!closed && !canceled()) { "投屏已停止。" }
        require(storedBytes + count <= 8 * 1024 * 1024) { "HLS 清单缓存超过 8 MiB。" }
        memoryBudget?.reserve(count.toLong())
        storedBytes += count
    }

    private fun content(entry: Entry): ByteArray = synchronized(entry) {
        entry.bytes?.let { return@synchronized it }
        check(!closed && !canceled()) { "投屏已停止。" }
        var active: HttpURLConnection? = null
        val response = SourceProbe.readContent(entry.source, headers, { closed || canceled() }, connectionChanged = { connection ->
            active?.let(connections::remove)
            active = connection
            connection?.let { connections.add(it); if (closed || canceled()) it.disconnect() }
        })
        check(!closed && !canceled()) { "投屏已停止。" }
        val manifest = response.manifest ?: error("HLS 子清单格式无效。")
        // Relative URLs use the final response URL after redirects.
        val resolvedEntry = Entry(response.resolvedSource, entry.path, entry.ancestors + response.resolvedSource)
        val rewritten = rewrite(resolvedEntry, manifest)
        val text = manifest.toString(Charsets.UTF_8)
        if (master(text) || text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" }) {
            account(rewritten.size)
            entry.bytes = rewritten
        }
        rewritten
    }

    private fun start() { workers.execute {
        while (!closed) {
            val client = try { server.accept() } catch (_: Exception) { break }
            if (!slots.tryAcquire()) { client.close(); continue }
            clients.add(client)
            if (closed || canceled()) { clients.remove(client); client.close(); slots.release(); break }
            try {
                workers.execute {
                    try { client.use(::handle) } catch (_: Exception) { /* Native clients may close probes early. */ }
                    finally { clients.remove(client); slots.release() }
                }
            } catch (_: RejectedExecutionException) {
                clients.remove(client)
                client.close()
                slots.release()
                break
            }
        }
    } }

    private fun handle(client: Socket) {
        client.soTimeout = 15000
        val request = HttpWire.readRequest(client.getInputStream().buffered()) ?: return
        val output = client.getOutputStream().buffered()
        if (request.method !in setOf("GET", "HEAD")) { HttpResponse(405, "Method Not Allowed").writeTo(output); return }
        val path = request.path.substringBefore('?')
        val entry = paths[path]
        val resource = mediaPaths[path]
        if ((entry == null && resource == null) || closed || canceled()) { HttpResponse(404, "Not Found").writeTo(output); return }
        try {
            if (resource != null) { downloads!!.serve(resource, request, output); return }
            val bytes = content(entry!!)
            HttpResponse(200, "OK", mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Cache-Control" to "no-store"), bytes)
                .writeTo(output, request.method == "HEAD")
        } catch (error: Exception) {
            if (resource == null) manifestFailure = if (error is SourceProbe.HttpFailure) error.message else "HLS 子清单读取失败（${error.javaClass.simpleName}）。"
            HttpResponse(502, "Bad Gateway").writeTo(output, request.method == "HEAD")
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        connections.forEach(HttpURLConnection::disconnect)
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        downloads?.close()
        paths.clear()
        mediaPaths.clear()
        synchronized(entries) {
            entries.clear()
            finiteMedia.clear()
            memoryBudget?.release(storedBytes.toLong())
            storedBytes = 0
        }
    }
}
