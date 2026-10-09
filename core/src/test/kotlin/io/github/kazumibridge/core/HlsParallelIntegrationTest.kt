package io.github.kazumibridge.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Delayed synthetic HLS proves concurrency, ordering, authentication and cancellation. */
class HlsParallelIntegrationTest {
    private lateinit var upstream: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var base: String
    private lateinit var cacheDirectory: File
    private val snapshots = mutableListOf<HlsSnapshot>()
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private val receivedQueries = ConcurrentHashMap<String, String>()
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val unauthenticated = AtomicInteger()
    private val rangedMedia = AtomicInteger()
    private val completionOrder = Collections.synchronizedList(mutableListOf<Int>())
    private val firstFour = CountDownLatch(4)
    private val blockedStarted = CountDownLatch(1)
    private val releaseBlocked = CountDownLatch(1)
    private val headers = mapOf(
        "User-Agent" to "KazumiBridge-parallel-fixture",
        "Referer" to "https://fixture.invalid/player/",
        "Cookie" to "fixture=parallel",
    )

    @Before fun startUpstream() {
        cacheDirectory = Files.createTempDirectory(directory.toPath(), "cache-").toFile()
        executor = Executors.newCachedThreadPool()
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = executor
        upstream.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            receivedQueries[path] = exchange.requestURI.rawQuery.orEmpty()
            requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            try {
                if (headers.any { (name, value) -> exchange.requestHeaders.getFirst(name) != value }) {
                    unauthenticated.incrementAndGet()
                    exchange.sendResponseHeaders(403, -1)
                    return@createContext
                }
                if (path == "/failure/segment-2.ts") {
                    exchange.sendResponseHeaders(403, -1)
                    return@createContext
                }
                if (path.startsWith("/blocked/")) {
                    exchange.sendResponseHeaders(200, 4096)
                    exchange.responseBody.write(ByteArray(128) { 7 })
                    exchange.responseBody.flush()
                    blockedStarted.countDown()
                    releaseBlocked.await(10, TimeUnit.SECONDS)
                    exchange.responseBody.write(ByteArray(3968) { 9 })
                    return@createContext
                }
                val bytes = when {
                    path.startsWith("/synthetic/") || path.startsWith("/failure/") ->
                        ByteArray(256) { path.substringAfterLast("segment-").substringBefore('.').toInt().toByte() }
                    else -> {
                        val file = File(directory, path.removePrefix("/"))
                        if (!file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) || !file.isFile) {
                            exchange.sendResponseHeaders(404, -1)
                            return@createContext
                        }
                        file.readBytes()
                    }
                }
                val isMedia = path.endsWith(".ts") || path.endsWith(".m4s")
                if (isMedia) {
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    try {
                        if (path.startsWith("/aes/")) {
                            val index = path.substringAfterLast("segment-").substringBefore('.').toInt()
                            if (index < 4) {
                                firstFour.countDown()
                                if (!firstFour.await(5, TimeUnit.SECONDS)) {
                                    exchange.sendResponseHeaders(504, -1)
                                    return@createContext
                                }
                                // Finish the last listed segment first; playback must still retain list order.
                                Thread.sleep((3 - index) * 90L)
                            }
                        } else Thread.sleep(35)
                        val range = ByteRanges.parse(exchange.requestHeaders.getFirst("Range"), bytes.size.toLong())
                        if (range == RangeResult.Invalid) {
                            exchange.sendResponseHeaders(416, -1)
                            return@createContext
                        }
                        val selected = if (range is RangeResult.Partial) range.range else ByteRange(0, bytes.size.toLong() - 1)
                        if (range is RangeResult.Partial) {
                            rangedMedia.incrementAndGet()
                            exchange.responseHeaders.set("Content-Range", "bytes ${selected.start}-${selected.end}/${bytes.size}")
                        }
                        exchange.responseHeaders.set("Content-Type", if (path.endsWith(".ts")) "video/mp2t" else "video/mp4")
                        exchange.responseHeaders.set("Accept-Ranges", "bytes")
                        exchange.responseHeaders.set("Content-Length", selected.length.toString())
                        exchange.sendResponseHeaders(if (range is RangeResult.Partial) 206 else 200, selected.length)
                        exchange.responseBody.write(bytes, selected.start.toInt(), selected.length.toInt())
                        if (path.startsWith("/aes/")) completionOrder.add(path.substringAfterLast("segment-").substringBefore('.').toInt())
                    } finally { active.decrementAndGet() }
                } else {
                    exchange.responseHeaders.set("Content-Type", if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            } catch (_: IOException) {
                // Closing the snapshot disconnects upstream sockets, including the cancellation fixture.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally { exchange.close() }
        }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
    }

    @After fun closeServers() {
        releaseBlocked.countDown()
        snapshots.forEach(HlsSnapshot::close)
        upstream.stop(0)
        executor.shutdownNow()
    }

    @Test fun fourWorkersKeepEncryptedSegmentsInPlaybackOrderAndReuseAuthenticatedDownloads() {
        val source = "$base/master/index.m3u8?token=root%2Bsignature"
        val content = SourceProbe.readContent(source, headers)
        val snapshot = create(source, requireNotNull(content.manifest).toString(Charsets.UTF_8))
        val probe = run("ffprobe", listOf("-v", "error") + MediaPolicy.networkOptions(headers, hls = true) +
            listOf("-show_entries", "format=format_name", "-of", "csv=p=0", snapshot.url))
        assertTrue(probe.contains("hls"))
        val output = remux(snapshot, "encrypted-parallel")

        assertEquals("The delayed origin must observe all four download workers simultaneously", 4, peak.get())
        assertEquals("All first four segments must arrive before the origin releases any of them", 0L, firstFour.count)
        assertTrue("This fixture must finish segments in a different order from playback", completionOrder.toList() != listOf(0, 1, 2, 3))
        for (index in 0..3) assertEquals("Probe and remux must share each cached segment", 1, count("/aes/segment-%02d.ts".format(index)))
        assertEquals("The signed root must not be reopened by native tools", 1, count("/master/index.m3u8"))
        assertEquals("The selected VOD child must also be reused between native tools", 1, count("/aes/index.m3u8"))
        assertEquals("Shared AES-128 keys must keep source headers and be fetched once", 1, count("/aes/key.bin"))
        assertEquals("token=root%2Bsignature", receivedQueries["/master/index.m3u8"])
        assertEquals("token=child%2Bsignature", receivedQueries["/aes/index.m3u8"])
        assertEquals("token=key%2Bsignature", receivedQueries["/aes/key.bin"])
        assertEquals(0, unauthenticated.get())
        assertNull(snapshot.failure)
        assertWholeClip(output)
        assertSameDecodedFrames(output)
        assertTrue(snapshot.downloadBytes > 0)
        val downloadStats = requireNotNull(snapshot.downloadStats)
        assertEquals("Download diagnostics must agree with the observed four-worker origin", 4, downloadStats.peakActive)
        assertEquals(5, downloadStats.registered) // Four encrypted segments and one shared key.
        assertEquals(downloadStats.registered, downloadStats.completed)
        assertEquals(0, downloadStats.failed)
        assertEquals(0, downloadStats.pending)
        val mediaStats = snapshot.mediaStats.single()
        assertEquals(4, mediaStats.segmentCount)
        assertEquals(4.0, mediaStats.extinfDurationSum, 0.001)
        assertEquals(0, mediaStats.gapCount)
        assertEquals(setOf("AES-128"), mediaStats.keyMethods)
        assertTrue(mediaStats.parallel)
        snapshot.close()
        assertNoCachedFiles()
    }

    @Test fun oneWorkerHonorsTheSelectedLimitWithoutChangingSegmentOrder() {
        val snapshot = create("$base/synthetic/index.m3u8", syntheticVod("segment-"), parallelism = 1)
        val endpoints = mediaUrls(get(snapshot.url))
        assertEquals(4, endpoints.size)
        endpoints.forEachIndexed { index, endpoint ->
            assertArrayEquals(ByteArray(256) { index.toByte() }, getBytes(endpoint))
        }
        assertEquals("Selecting one worker must serialize upstream requests", 1, peak.get())
        for (index in 0..3) assertEquals("token=segment%2B$index", receivedQueries["/synthetic/segment-$index.ts"])
        assertEquals(0, unauthenticated.get())
        assertNull(snapshot.failure)
    }

    @Test fun forbiddenSegmentStaysInThePlaylistAndFailsTheJobRatherThanBeingDropped() {
        val snapshot = create("$base/failure/index.m3u8", syntheticVod("segment-"))
        val endpoints = mediaUrls(get(snapshot.url))
        assertEquals("A failed download must never shorten the playlist", 4, endpoints.size)
        val connection = URL(endpoints[2]).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        try { assertEquals("A failed segment must be visible to the native reader", 502, connection.responseCode) }
        finally { connection.disconnect() }
        awaitCondition { snapshot.failure != null }
        assertNotNull("Conversion must be rejected even if a native reader skips this segment", snapshot.failure)
        snapshot.close()
        assertNoCachedFiles()
    }

    @Test fun combinedMp4AndSegmentBudgetRejectsAnOversizedDownloadAndCleansUp() {
        val snapshot = create("$base/synthetic/index.m3u8", syntheticVod("segment-"), maxBytes = 1024, otherBytes = { 900 })
        val endpoint = mediaUrls(get(snapshot.url)).first()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        try { assertEquals(502, connection.responseCode) }
        finally { connection.disconnect() }
        awaitCondition { snapshot.failure != null }
        assertNotNull("The segment cache and existing MP4 must share the configured budget", snapshot.failure)
        assertTrue("Partial files must not overrun the remaining 124-byte budget", cachedBytes() <= 124)
        snapshot.close()
        assertNoCachedFiles()
    }

    @Test fun closeCancelsBlockedUpstreamReadsAndDeletesPartialSegments() {
        val snapshot = create("$base/blocked/index.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n")
        val endpoint = mediaUrls(get(snapshot.url)).first()
        val closing = Executors.newFixedThreadPool(2)
        val reading = closing.submit<Boolean> { runCatching { getBytes(endpoint) }.isFailure }
        try {
            assertTrue("A real upstream body must be open before cancellation", blockedStarted.await(5, TimeUnit.SECONDS))
            closing.submit { snapshot.close() }.get(4, TimeUnit.SECONDS)
            assertTrue("Cancellation must stop the local reader", reading.get(4, TimeUnit.SECONDS))
        }
        finally { closing.shutdownNow(); releaseBlocked.countDown() }
        assertThrows(IOException::class.java) { getBytes(snapshot.url) }
        awaitCondition { cachedBytes() == 0L }
        assertNoCachedFiles()
    }

    @Test fun byteRangePlaylistFallsBackWithoutPrefetchingTheWholeSharedMediaFile() {
        val manifest = File(directory, "range/index.m3u8").readText()
        assertTrue("The fixture must use one media file with HLS byte ranges", manifest.contains("#EXT-X-BYTERANGE:"))
        val snapshot = create("$base/range/index.m3u8", manifest)
        val endpoints = mediaUrls(get(snapshot.url))
        assertTrue(endpoints.isNotEmpty())
        assertTrue("Byte-range resources must retain their authenticated upstream URLs", endpoints.all { URI(it).port == upstream.address.port })
        assertEquals("Creation must not download the shared file as an ordinary segment", 0, count("/range/all.ts"))
        assertEquals(0L, snapshot.downloadBytes)
        assertNoCachedFiles()

        val output = remux(snapshot, "byterange-fallback")
        assertTrue("The native reader must keep real Range requests in the fallback", rangedMedia.get() > 0)
        assertEquals(0, unauthenticated.get())
        assertNull(snapshot.failure)
        assertWholeClip(output)
        assertSameDecodedFrames(output)
    }

    @Test fun fragmentedMp4InitMapKeepsHeadersAndProducesCompleteAudioAndVideo() {
        val manifest = File(directory, "fmp4/index.m3u8").readText().replace("URI=\"init.mp4\"", "URI=\"init.mp4?token=map%2Bsignature\"")
        val snapshot = create("$base/fmp4/index.m3u8", manifest)
        val output = remux(snapshot, "init-map-parallel")

        assertEquals("The authenticated initialization map must be downloaded exactly once", 1, count("/fmp4/init.mp4"))
        assertEquals("token=map%2Bsignature", receivedQueries["/fmp4/init.mp4"])
        assertEquals(0, unauthenticated.get())
        assertNull(snapshot.failure)
        assertWholeClip(output)
        assertSameDecodedFrames(output)
    }

    private fun create(source: String, manifest: String, parallelism: Int = 4, maxBytes: Long = 8L * 1024 * 1024,
        otherBytes: () -> Long = { 0 }): HlsSnapshot = requireNotNull(HlsSnapshot.create(
        source, manifest.toByteArray(Charsets.UTF_8), headers,
        download = HlsDownloadCache.Options(cacheDirectory, parallelism, maxBytes, otherBytes, reserveFreeBytes = 0),
    )).also { snapshots.add(it) }

    private fun syntheticVod(stem: String) = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n" +
        (0..3).joinToString("") { "#EXTINF:1,\n$stem$it.ts?token=segment%2B$it\n" } + "#EXT-X-ENDLIST\n"

    private fun remux(snapshot: HlsSnapshot, name: String): File {
        val output = File(directory, "$name-${System.nanoTime()}.mp4")
        run("ffmpeg", MediaPolicy.arguments(snapshot.url, output.absolutePath,
            ConversionPlan(false, true, true, stripAdts = true), CastMode.COMPLETE, MediaPolicy.networkOptions(headers, hls = true)))
        return output
    }

    private fun assertWholeClip(output: File) {
        assertTrue(MediaPolicy.isMp4(output.readBytes().take(65536).toByteArray()))
        val codecs = run("ffprobe", listOf("-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", output.absolutePath)).trim().lines().toSet()
        assertEquals(setOf("h264", "aac"), codecs)
        for (track in listOf("v:0", "a:0")) {
            val duration = run("ffprobe", listOf("-v", "error", "-select_streams", track, "-show_entries", "stream=duration", "-of", "csv=p=0", output.absolutePath)).trim().toDouble()
            assertTrue("$track must retain all four seconds of source media, got $duration", duration >= 3.95 && duration <= 4.2)
        }
    }

    private fun assertSameDecodedFrames(output: File) {
        val decoded = File(directory, "decoded-${System.nanoTime()}.rgb")
        run("ffmpeg", listOf("-v", "error", "-y", "-i", output.absolutePath, "-map", "0:v:0", "-pix_fmt", "rgb24", "-fps_mode", "passthrough", "-f", "rawvideo", decoded.absolutePath))
        assertArrayEquals("Out-of-order downloads must preserve every decoded frame in playback order", File(directory, "reference.rgb").readBytes(), decoded.readBytes())
    }

    private fun count(path: String) = requests[path]?.get() ?: 0
    private fun cachedBytes() = cacheDirectory.walkTopDown().filter(File::isFile).sumOf(File::length)
    private fun assertNoCachedFiles() = assertFalse("Closing the cast must remove all segment and partial files", cacheDirectory.walkTopDown().any(File::isFile))
    private fun mediaUrls(playlist: String) = playlist.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
    private fun get(url: String) = getBytes(url).toString(Charsets.UTF_8)
    private fun getBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 6000
        try {
            assertEquals(200, connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("The asynchronous operation did not settle before the deadline", condition())
    }

    companion object {
        private val directory = File("build/test-hls-parallel")

        @JvmStatic @BeforeClass fun prepareSyntheticMedia() {
            directory.mkdirs()
            listOf("aes", "fmp4", "range", "master").forEach { File(directory, it).mkdirs() }
            File(directory, "master/index.m3u8").writeText("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000\n../aes/index.m3u8?token=child%2Bsignature\n")
            val source = File(directory, "source.mp4")
            run("ffmpeg", listOf("-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=8", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "4",
                "-c:v", "libx264", "-preset", "ultrafast", "-threads", "1", "-g", "8", "-sc_threshold", "0", "-c:a", "aac", "-movflags", "+faststart", source.absolutePath))
            run("ffmpeg", listOf("-v", "error", "-y", "-i", source.absolutePath, "-map", "0:v:0", "-pix_fmt", "rgb24", "-fps_mode", "passthrough", "-f", "rawvideo", File(directory, "reference.rgb").absolutePath))
            val key = File(directory, "aes/key.bin").apply { writeBytes(ByteArray(16) { (it + 1).toByte() }) }
            val keyInfo = File(directory, "aes/key-info.txt").apply { writeText("key.bin?token=key%2Bsignature\n${key.absolutePath}\n") }
            run("ffmpeg", listOf("-v", "error", "-y", "-i", source.absolutePath, "-c", "copy", "-f", "hls", "-hls_time", "1", "-hls_list_size", "0", "-hls_key_info_file", keyInfo.absolutePath,
                "-hls_segment_filename", File(directory, "aes/segment-%02d.ts").absolutePath, File(directory, "aes/index.m3u8").absolutePath))
            run("ffmpeg", listOf("-v", "error", "-y", "-i", source.absolutePath, "-c", "copy", "-f", "hls", "-hls_time", "1", "-hls_list_size", "0", "-hls_segment_type", "fmp4",
                "-hls_segment_filename", File(directory, "fmp4/segment-%02d.m4s").absolutePath, File(directory, "fmp4/index.m3u8").absolutePath), workingDirectory = File(directory, "fmp4"))
            assertTrue("The fixture initialization map must be hosted beside its media playlist", File(directory, "fmp4/init.mp4").isFile)
            run("ffmpeg", listOf("-v", "error", "-y", "-i", source.absolutePath, "-c", "copy", "-f", "hls", "-hls_time", "1", "-hls_list_size", "0", "-hls_flags", "single_file",
                "-hls_segment_filename", File(directory, "range/all.ts").absolutePath, File(directory, "range/index.m3u8").absolutePath))
        }

        private fun run(tool: String, arguments: List<String>, workingDirectory: File? = null): String {
            val log = File(directory, "$tool-${System.nanoTime()}.log").absoluteFile
            val process = ProcessBuilder(listOf(tool) + arguments).directory(workingDirectory).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("$tool timed out") }
            val output = log.readText()
            assertEquals("$tool failed: ${output.takeLast(1800)}", 0, process.exitValue())
            return output
        }
    }
}
