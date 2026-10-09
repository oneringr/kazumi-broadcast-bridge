package io.github.kazumibridge.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Uses small synthetic, locally hosted videos, never third-party video content. */
class MediaIntegrationTest {
    @Test fun playlistRejectingRangesUsesNormalGetWithAuthenticationHeaders() {
        val responses = mutableListOf<SourceProbe.Response>()
        val prefix = SourceProbe.readPrefix("$base/playlist-no-range", headers, response = responses::add)
        assertArrayEquals(File(directory, "fixture.m3u8").readBytes(), prefix)
        assertEquals(listOf(403, 200), responses.map { it.status })
        assertEquals(listOf(true, false), responses.map { it.ranged })
    }

    @Test fun forbiddenSourceIsNeverAcceptedAsVideo() {
        val error = assertThrows(SourceProbe.HttpFailure::class.java) { SourceProbe.readPrefix("$base/always-forbidden", headers) }
        assertEquals(403, error.status)
    }

    @Test fun interruptedHttpResumesAtByteOffsetAndKeepsWholeVideo() {
        val output = File(directory, "resumed.mp4")
        run("ffmpeg", MediaPolicy.arguments("$base/interrupted.mp4", output.absolutePath,
            ConversionPlan(false, true, true), CastMode.COMPLETE, MediaPolicy.networkOptions(headers)))
        val duration = run("ffprobe", listOf("-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", output.absolutePath)).trim().toDouble()
        assertTrue("A dropped input connection must not truncate the output", duration >= 1.95)
        assertTrue("The server must see a resumed byte-range request", resumedRequests.get() > 0)
    }

    @Test fun hlsTransportStreamsRemuxIntoCompleteAndFragmentedMp4WithHeaders() {
        for (mode in CastMode.entries) {
            val output = File(directory, "hls-${mode.name.lowercase()}.mp4")
            run("ffmpeg", MediaPolicy.arguments("$base/fixture.m3u8", output.absolutePath,
                MediaPolicy.plan(ProbeResult("hls", "h264", "aac", "yuv420p", 2, 2.0), "#EXTM3U".toByteArray(), false),
                mode, MediaPolicy.networkOptions(headers, hls = true)))
            assertEquals(setOf("h264", "aac"), codecs(output))
            assertTrue(MediaPolicy.isMp4(output.readBytes().take(65536).toByteArray()))
            if (mode == CastMode.STREAM) assertTrue(MediaPolicy.fragmentedReady(output))
            else assertTrue(output.readBytes().toString(Charsets.ISO_8859_1).indexOf("moov") < output.readBytes().toString(Charsets.ISO_8859_1).indexOf("mdat"))
        }
        assertTrue("HLS media segments must receive source authentication headers", segmentRequests.get() >= 2)
    }

    @Test fun expiringHlsRootIsFetchedOnceWhileNativeProbeAndRemuxReuseSnapshot() {
        val source = "$base/single-use.m3u8?token=fixture%2Bsignature"
        val content = SourceProbe.readContent(source, headers)
        val manifest = requireNotNull(content.manifest)
        val output = File(directory, "hls-single-use-complete.mp4")
        val segmentsBefore = segmentRequests.get()
        requireNotNull(HlsSnapshot.create(content.resolvedSource, manifest, headers)).use { snapshot ->
            val probe = run("ffprobe", listOf("-v", "error") + MediaPolicy.networkOptions(headers, hls = true) +
                listOf("-show_entries", "format=format_name", "-of", "default=noprint_wrappers=1:nokey=1", snapshot.url))
            assertTrue("The actual media probe must identify the cached playlist as HLS", probe.contains("hls"))
            run("ffmpeg", MediaPolicy.arguments(snapshot.url, output.absolutePath,
                MediaPolicy.plan(ProbeResult("hls", "h264", "aac", "yuv420p", 2, 2.0), content.prefix, false),
                CastMode.COMPLETE, MediaPolicy.networkOptions(headers, hls = true)))
        }

        assertEquals("Probe and conversion must not reopen the expiring root", 1, singleUseRootRequests.get())
        assertTrue("The authenticated source must still supply real HLS media segments", segmentRequests.get() > segmentsBefore)
        assertEquals(setOf("h264", "aac"), codecs(output))
        assertTrue(MediaPolicy.isMp4(output.readBytes().take(65536).toByteArray()))
        for (track in listOf("v:0", "a:0")) {
            val duration = run("ffprobe", listOf("-v", "error", "-select_streams", track, "-show_entries", "stream=duration",
                "-of", "csv=p=0", output.absolutePath)).trim().toDouble()
            assertTrue("The $track track must retain the complete synthetic clip", duration >= 1.95 && duration <= 2.15)
        }
    }

    @Test fun unsupportedVideoAndAudioTranscodeToH264Aac() {
        val output = File(directory, "transcoded.mp4")
        run("ffmpeg", MediaPolicy.arguments("$base/fixture.webm", output.absolutePath,
            ConversionPlan(false, false, false), CastMode.COMPLETE, MediaPolicy.networkOptions(headers)))
        assertEquals(setOf("h264", "aac"), codecs(output))
    }

    @Test fun networkProbeWorksForMp4WithMisleadingSuffix() {
        val result = run("ffprobe", listOf("-v", "error") + MediaPolicy.networkOptions(headers) +
            listOf("-show_entries", "format=format_name", "-of", "default=noprint_wrappers=1:nokey=1", "$base/video.txt"))
        assertTrue(result.contains("mp4"))
    }

    companion object {
        private val directory = File("build/test-media")
        private lateinit var server: HttpServer
        private lateinit var executor: java.util.concurrent.ExecutorService
        private lateinit var base: String
        private val segmentRequests = AtomicInteger()
        private val interrupted = AtomicBoolean()
        private val resumedRequests = AtomicInteger()
        private val singleUseRootRequests = AtomicInteger()
        private val headers = mapOf("User-Agent" to "KazumiBridge-fixture", "Referer" to "https://fixture.invalid/", "Cookie" to "fixture=yes")

        @JvmStatic @BeforeClass fun prepare() {
            singleUseRootRequests.set(0)
            directory.mkdirs()
            run("ffmpeg", listOf("-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "2",
                "-c:v", "libx264", "-preset", "ultrafast", "-threads", "1", "-g", "24", "-c:a", "aac", "-movflags", "+faststart", "-f", "mp4", File(directory, "video.txt").absolutePath))
            run("ffmpeg", listOf("-v", "error", "-y", "-i", File(directory, "video.txt").absolutePath, "-c", "copy", "-f", "hls", "-hls_time", "1", "-hls_list_size", "0",
                "-hls_segment_filename", File(directory, "segment-%02d.ts").absolutePath, File(directory, "fixture.m3u8").absolutePath))
            run("ffmpeg", listOf("-v", "error", "-y", "-i", File(directory, "video.txt").absolutePath, "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-threads", "1", "-c:a", "libopus", File(directory, "fixture.webm").absolutePath))
            executor = Executors.newCachedThreadPool()
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val name = exchange.requestURI.path.removePrefix("/")
                    val file = File(directory, when (name) { "interrupted.mp4" -> "video.txt"; "playlist-no-range", "single-use.m3u8" -> "fixture.m3u8"; else -> name })
                    if (headers.any { (key, value) -> exchange.requestHeaders.getFirst(key) != value }) {
                        exchange.sendResponseHeaders(403, -1); return@createContext
                    }
                    if (name == "always-forbidden" || (name == "playlist-no-range" && exchange.requestHeaders.getFirst("Range") != null)) {
                        exchange.sendResponseHeaders(403, -1); return@createContext
                    }
                    if (name == "single-use.m3u8" && singleUseRootRequests.incrementAndGet() > 1) {
                        exchange.sendResponseHeaders(403, -1); return@createContext
                    }
                    if (file.parentFile.canonicalFile != directory.canonicalFile || !file.isFile) {
                        exchange.sendResponseHeaders(404, -1); return@createContext
                    }
                    if (file.extension == "ts") segmentRequests.incrementAndGet()
                    val bytes = file.readBytes()
                    val range = ByteRanges.parse(if (name == "single-use.m3u8") null else exchange.requestHeaders.getFirst("Range"), bytes.size.toLong())
                    if (range == RangeResult.Invalid) { exchange.sendResponseHeaders(416, -1); return@createContext }
                    val selection = if (range is RangeResult.Partial) range.range else ByteRange(0, bytes.size.toLong() - 1)
                    if (name == "interrupted.mp4" && selection.start > 0) resumedRequests.incrementAndGet()
                    exchange.responseHeaders.set("Content-Type", when (file.extension) { "m3u8" -> "application/vnd.apple.mpegurl"; "ts" -> "video/mp2t"; "webm" -> "video/webm"; else -> "application/octet-stream" })
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    exchange.responseHeaders.set("Content-Length", selection.length.toString())
                    if (range is RangeResult.Partial) exchange.responseHeaders.set("Content-Range", "bytes ${selection.start}-${selection.end}/${bytes.size}")
                    val head = exchange.requestMethod == "HEAD"
                    exchange.sendResponseHeaders(if (range is RangeResult.Partial) 206 else 200, if (head) -1 else selection.length)
                    if (!head) {
                        val drop = name == "interrupted.mp4" && selection.start == 0L && interrupted.compareAndSet(false, true)
                        val count = if (drop) minOf(32768L, selection.length / 2).toInt() else selection.length.toInt()
                        exchange.responseBody.write(bytes, selection.start.toInt(), count)
                    }
                } finally { exchange.close() }
            }
            server.start()
            base = "http://127.0.0.1:${server.address.port}"
        }

        private fun codecs(file: File): Set<String> = run("ffprobe", listOf("-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", file.absolutePath)).trim().lines().filter(String::isNotBlank).toSet()

        private fun run(tool: String, arguments: List<String>): String {
            val log = File(directory, "${tool}-${System.nanoTime()}.log")
            val process = ProcessBuilder(listOf(tool) + arguments).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("$tool timed out") }
            val text = log.readText()
            assertEquals("$tool failed: ${text.takeLast(1800)}", 0, process.exitValue())
            return text
        }

        @JvmStatic @AfterClass fun finish() { if (::server.isInitialized) server.stop(0); if (::executor.isInitialized) executor.shutdownNow() }
    }
}
