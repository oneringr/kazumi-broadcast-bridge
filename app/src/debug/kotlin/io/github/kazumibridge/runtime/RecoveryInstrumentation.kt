package io.github.kazumibridge.runtime

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import io.github.kazumibridge.core.ByteRanges
import io.github.kazumibridge.core.CacheStorage
import io.github.kazumibridge.core.CastMode
import io.github.kazumibridge.core.HttpRequest
import io.github.kazumibridge.core.HttpResponse
import io.github.kazumibridge.core.HttpWire
import io.github.kazumibridge.core.MediaPolicy
import io.github.kazumibridge.core.RangeResult
import io.github.kazumibridge.core.RelayTarget
import io.github.kazumibridge.core.Soap
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real native preparation and local HTTP/DLNA recovery contracts; no goggles or external sources. */
class RecoveryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        Thread({
            val started = SystemClock.elapsedRealtime()
            val result = Bundle()
            val prefix = "recovery-validation-${UUID.randomUUID()}"
            val root = File(targetContext.cacheDir, prefix).apply { mkdirs() }
            val wrapper = object : ContextWrapper(targetContext) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    targetContext.getSharedPreferences("$prefix-$name", mode)
            }
            var passed = 0
            var currentCase = "native_fixture"
            var resultCode = Activity.RESULT_CANCELED
            try {
                Settings.initialize(wrapper)
                FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
                val fixtures = fixture(root)
                fun verify(name: String, storage: CacheStorage, block: (Scenario) -> Unit) {
                    currentCase = name
                    Settings.save(configuration(storage))
                    BridgeState.flow.value = BridgeSnapshot(running = true)
                    Scenario(wrapper, File(root, name), fixtures.first, fixtures.second).use(block)
                    passed++
                    result.putString(name, "PASS")
                }
                verify("retry_preserves_headers_and_orders_commands", CacheStorage.DISK) { test ->
                    test.set("/retry.mp4?fixture=full&token=value")
                    test.failed(force = false)
                    check(!test.controller.forceLast()) { "A source HTTP failure was forceable" }
                    test.server.retryAllowed = true
                    check(test.controller.retryLast())
                    val uri = test.playing()
                    check(BridgeState.flow.value.sourceUrl == test.server.url("/retry.mp4?fixture=full&token=value"))
                    test.verifyReadable(uri)
                    check(test.server.actions.take(2).map { it.action } == listOf("SetAVTransportURI", "Play"))
                    check(test.server.actions.take(2).all { it.instance == "7" }) { "Retry changed the AVTransport instance" }
                    test.stop()
                    check(test.directory.walkTopDown().none(File::isFile))
                }
                verify("unreadable_media_cannot_force", CacheStorage.MEMORY) { test ->
                    test.set("/invalid.mp4")
                    test.failed(force = false)
                    check(!test.controller.forceLast())
                    check(test.server.actions.isEmpty())
                    check(BridgeState.flow.value.memoryCacheBytes == 0L)
                }
                verify("disk_duration_candidate_force_and_stop", CacheStorage.DISK) { test ->
                    test.set("/mismatch.m3u8")
                    test.failed(force = true)
                    check(test.directory.listFiles().orEmpty().count { it.isFile && it.extension == "mp4" && it.length() > 0 } == 1)
                    check(test.directory.listFiles().orEmpty().none(File::isDirectory)) { "HLS cache survived finalization" }
                    check(test.controller.forceLast())
                    test.verifyReadable(test.playing())
                    test.stop()
                    check(test.directory.walkTopDown().none(File::isFile))
                    check(!test.controller.forceLast() && !test.controller.retryLast())
                }
                verify("ram_duration_candidate_force_and_stop", CacheStorage.MEMORY) { test ->
                    test.set("/mismatch.m3u8")
                    test.failed(force = true)
                    val retained = MemoryMediaProvider.outputs.values.toList()
                    check(retained.isNotEmpty() && retained.sumOf { it.budget.usedBytes } > 0)
                    check(test.directory.walkTopDown().none(File::isFile))
                    check(test.controller.forceLast())
                    test.verifyReadable(test.playing())
                    test.stop()
                    check(retained.all { it.budget.usedBytes == 0L }) { "Stop leaked the retained RAM output" }
                    check(BridgeState.flow.value.memoryCacheBytes == 0L)
                }
                verify("retry_releases_old_ram_candidate", CacheStorage.MEMORY) { test ->
                    test.set("/mismatch.m3u8")
                    test.failed(force = true)
                    val retained = MemoryMediaProvider.outputs.values.toList()
                    check(retained.isNotEmpty())
                    test.server.matchingDuration = true
                    check(test.controller.retryLast())
                    test.verifyReadable(test.playing())
                    check(retained.all { it.budget.usedBytes == 0L }) { "Retry leaked the previous RAM output" }
                    check(!BridgeState.flow.value.canForce && !BridgeState.flow.value.canRetry)
                    test.stop()
                }
                verify("replacement_rejects_old_callbacks_and_stop", CacheStorage.MEMORY) { test ->
                    test.set("/slow.mp4")
                    val oldTask = BridgeState.flow.value.taskId
                    check(test.server.slowRequested.await(5, TimeUnit.SECONDS))
                    test.set("/mismatch.m3u8", controlPath = "/control-new")
                    test.server.releaseSlow.countDown()
                    test.failed(force = true)
                    check(!test.controller.forceLast(oldTask) && !test.controller.retryLast(oldTask)) { "An obsolete UI task ID operated on the new candidate" }
                    check(BridgeState.flow.value.canForce)
                    val retained = MemoryMediaProvider.outputs.values.toList()
                    check(retained.isNotEmpty())
                    test.stop(controlPath = "/control")
                    check(BridgeState.flow.value.canForce && BridgeState.flow.value.canRetry) { "A late old-renderer Stop hid the active recovery controls" }
                    check(BridgeState.flow.value.sourceUrl == test.server.url("/mismatch.m3u8"))
                    check(test.server.actions.none { it.action == "SetAVTransportURI" }) { "The canceled source was published" }
                    test.set("/fresh.mp4", controlPath = "/control-new", play = true)
                    test.verifyReadable(test.playing())
                    check(retained.all { it.budget.usedBytes == 0L }) { "Replacement leaked the RAM candidate" }
                    test.stop(controlPath = "/control-new")
                }
                result.putString("status", "PASS")
                result.putInt("tests_passed", passed)
                result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                resultCode = Activity.RESULT_OK
            } catch (failure: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_case", currentCase)
                result.putInt("tests_passed", passed)
                result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                result.putString("failure_type", failure.javaClass.simpleName)
                result.putString("failure", failure.message.orEmpty().take(1500))
            } finally {
                Settings.initialize(targetContext)
                targetContext.deleteSharedPreferences("$prefix-settings")
                root.deleteRecursively()
            }
            finish(resultCode, result)
        }, "KazumiBridge-recovery-validation").start()
    }

    private fun configuration(storage: CacheStorage) = BridgeSettings(CastMode.COMPLETE, false, 1,
        "", "", "", hlsParallelism = 4, cacheMp4 = true, cacheStorage = storage, memoryCacheMiB = 64)

    private fun fixture(directory: File): Pair<ByteArray, ByteArray> {
        val mp4 = File(directory, "fixture.mp4")
        val ts = File(directory, "fixture.ts")
        val create = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-v", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=12", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
            "-t", "2.5", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "12",
            "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "96k", "-movflags", "+faststart", mp4.absolutePath))
        check(ReturnCode.isSuccess(create.returnCode)) { "Could not create native MP4 fixture" }
        val transport = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-v", "error", "-y", "-i", mp4.absolutePath,
            "-c", "copy", "-bsf:v", "h264_mp4toannexb", "-f", "mpegts", ts.absolutePath))
        check(ReturnCode.isSuccess(transport.returnCode)) { "Could not create HLS segment fixture" }
        return mp4.readBytes() to ts.readBytes()
    }

    private class Scenario(context: Context, val directory: File, mp4: ByteArray, ts: ByteArray) : AutoCloseable {
        val server = FixtureServer(mp4, ts)
        private val media = MediaServer()
        private val engine = MediaEngine(context, directory)
        val controller = RendererController(engine, media) {}
        private val target = RelayTarget(InetAddress.getByName("127.0.0.1"), server.port,
            mapOf("Referer" to "https://fixture.invalid/player", "Cookie" to "fixture=original"))

        fun set(path: String, controlPath: String = "/control", play: Boolean = false) {
            val uri = server.url(path)
            val fields = "<CurrentURI>${Soap.escape(uri)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
            val result = controller.handle(target.copy(playerSource = uri), request("SetAVTransportURI", fields, controlPath))
            check(result?.status == 200)
            if (play) check(controller.handle(target, request("Play", "<Speed>1</Speed>", controlPath))?.status == 200)
        }

        fun failed(force: Boolean) {
            await { BridgeState.flow.value.error != null && BridgeState.flow.value.canRetry }
            check(BridgeState.flow.value.canForce == force) { "Unexpected force eligibility: ${BridgeState.flow.value.error}" }
        }

        fun playing(): String {
            await { server.actions.any { it.action == "SetAVTransportURI" } && server.actions.any { it.action == "Play" } && BridgeState.flow.value.error == null }
            return requireNotNull(server.actions.last { it.action == "SetAVTransportURI" }.uri)
        }

        fun verifyReadable(uri: String) {
            check(uri.contains("/media/") && uri.endsWith("/video.mp4"))
            val connection = URL(uri).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Range", "bytes=0-255")
                check(connection.responseCode == 206) { "Recovery output did not support byte ranges" }
                check(MediaPolicy.isMp4(connection.inputStream.use { it.readBytes() })) { "Recovery URI lacked MP4 data" }
            } finally { connection.disconnect() }
        }

        fun stop(controlPath: String = "/control") {
            check(controller.handle(target, request("Stop", "", controlPath))?.status == 200)
        }

        override fun close() {
            server.releaseSlow.countDown()
            controller.close()
            media.close()
            engine.close()
            server.close()
        }

        private fun request(action: String, fields: String, path: String): HttpRequest {
            val namespace = "urn:schemas-upnp-org:service:AVTransport:1"
            val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:$action xmlns:u=\"$namespace\"><InstanceID>7</InstanceID>$fields</u:$action></s:Body></s:Envelope>".toByteArray()
            return HttpRequest("POST", path, mapOf("soapaction" to "\"$namespace#$action\"", "content-type" to "text/xml; charset=utf-8"), body)
        }

        private fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 35_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            check(condition()) { "Recovery did not finish: ${BridgeState.flow.value.stage} / ${BridgeState.flow.value.error}" }
        }
    }

    private data class Action(val action: String, val uri: String?, val instance: String)

    private class FixtureServer(private val mp4: ByteArray, private val ts: ByteArray) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        @Volatile private var closed = false
        @Volatile var retryAllowed = false
        @Volatile var matchingDuration = false
        val slowRequested = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val actions = CopyOnWriteArrayList<Action>()
        val port = listener.localPort
        fun url(path: String) = "http://127.0.0.1:$port$path"

        init {
            workers.execute {
                while (!closed) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    clients.add(socket)
                    workers.execute {
                        try { socket.use(::handle) } catch (_: Exception) { /* Canceled HTTP probes may close early. */ }
                        finally { clients.remove(socket) }
                    }
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 5000
            val request = HttpWire.readRequest(socket.getInputStream().buffered(), socket.getOutputStream()) ?: return
            if (request.path.startsWith("/control")) {
                val command = requireNotNull(Soap.parse(request))
                val instance = command.document.getElementsByTagName("InstanceID").item(0).textContent
                actions.add(Action(command.action, command.source, instance))
                Soap.success(command.action, command.namespace).writeTo(socket.getOutputStream())
                return
            }
            if (request.header("referer") != "https://fixture.invalid/player" || request.header("cookie") != "fixture=original") {
                HttpResponse(403, "Forbidden").writeTo(socket.getOutputStream())
                return
            }
            val path = request.path.substringBefore('?')
            if (path == "/retry.mp4" && !retryAllowed) { HttpResponse(503, "Unavailable").writeTo(socket.getOutputStream()); return }
            if (path == "/slow.mp4") { slowRequested.countDown(); releaseSlow.await(10, TimeUnit.SECONDS) }
            val bytes = when (path) {
                "/retry.mp4", "/fresh.mp4", "/slow.mp4" -> mp4
                "/clip.ts" -> ts
                "/mismatch.m3u8" -> "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:${if (matchingDuration) "2.5" else "20.0"},\n/clip.ts\n#EXT-X-ENDLIST\n".toByteArray()
                else -> "not a video".toByteArray()
            }
            val range = ByteRanges.parse(request.header("range"), bytes.size.toLong())
            if (range == RangeResult.Invalid) {
                HttpResponse(416, "Range Not Satisfiable", mapOf("content-range" to "bytes */${bytes.size}")).writeTo(socket.getOutputStream())
                return
            }
            val partial = range as? RangeResult.Partial
            val body = partial?.range?.let { bytes.copyOfRange(it.start.toInt(), it.end.toInt() + 1) } ?: bytes
            val type = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream"
            val headers = mapOf("content-type" to type, "accept-ranges" to "bytes") +
                (partial?.range?.let { mapOf("content-range" to "bytes ${it.start}-${it.end}/${bytes.size}") } ?: emptyMap())
            HttpResponse(if (partial == null) 200 else 206, "OK", headers, body).writeTo(socket.getOutputStream(), request.method == "HEAD")
        }

        override fun close() {
            closed = true
            releaseSlow.countDown()
            listener.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }
}
