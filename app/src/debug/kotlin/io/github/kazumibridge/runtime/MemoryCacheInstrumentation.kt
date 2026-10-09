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
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import io.github.kazumibridge.core.ByteRange
import io.github.kazumibridge.core.ByteRanges
import io.github.kazumibridge.core.CacheStorage
import io.github.kazumibridge.core.CastMode
import io.github.kazumibridge.core.HttpResponse
import io.github.kazumibridge.core.HttpRequest
import io.github.kazumibridge.core.HttpWire
import io.github.kazumibridge.core.MemoryBudget
import io.github.kazumibridge.core.MemoryMediaBuffer
import io.github.kazumibridge.core.RangeResult
import io.github.kazumibridge.core.RelayTarget
import io.github.kazumibridge.core.Soap
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory

/** Standalone native/provider validation, present only in the debug APK. */
class MemoryCacheInstrumentation : Instrumentation() {
    private var mp4Fixture: ByteArray? = null
    private var rendererDescription: String? = null
    private var receiverOnly = false
    private var receiverStorage = CacheStorage.MEMORY
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        rendererDescription = arguments?.getString("rendererDescription")?.takeIf(String::isNotBlank)
        receiverOnly = arguments?.getString("receiverOnly").equals("true", true)
        receiverStorage = if (arguments?.getString("receiverStorage").equals("disk", true)) CacheStorage.DISK else CacheStorage.MEMORY
        start()
    }

    override fun onStart() {
        Thread({
            val started = SystemClock.elapsedRealtime()
            val result = Bundle()
            var passed = 0
            try {
                FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
                FFmpegKitConfig.setSessionHistorySize(2)
                val before = cacheFiles()
                verifyIsolatedPreferences()
                result.putString("preferences", "PASS: isolated defaults and MEMORY/32 settings round trip; user preferences preserved")
                if (!receiverOnly) {
                    nativeSparseAndCrossChunk()
                    result.putString("native_memory", "PASS: zero-fill, sparse holes, cross-chunk I/O, release")
                    passed++
                    result.putAll(nativeMp4())
                    passed++
                    quotaStopsNativeWriter()
                    result.putString("quota", "PASS: provider reports threshold failure; budget released")
                    passed++
                    result.putAll(runtimeSmoke())
                    passed += 4
                } else check(rendererDescription != null) { "receiverOnly requires rendererDescription" }
                mp4Fixture = null
                rendererDescription?.let {
                    result.putAll(receiverSmoke(it))
                    passed++
                }
                val added = cacheFiles() - before
                check(added.isEmpty()) { "Validation left cache files: ${added.joinToString().take(300)}" }
                result.putString("disk_cache", "PASS: no cache files remain; RAM test never creates media files")
                result.putInt("tests_passed", passed)
                result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                result.putString("status", "PASS")
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                mp4Fixture = null
                result.putInt("tests_passed", passed)
                result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                result.putString("status", "FAIL")
                result.putString("failure_type", failure.javaClass.simpleName)
                result.putString("failure", failure.message.orEmpty().takeLast(1800))
                finish(Activity.RESULT_CANCELED, result)
            }
        }, "KazumiBridge-memory-validation").start()
    }

    private fun verifyIsolatedPreferences() {
        val prefix = "memory-validation-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences("$prefix-$name", mode)
        }
        try {
            Settings.initialize(wrapper)
            val defaults = Settings.read()
            check(!defaults.cacheMp4 && defaults.cacheStorage == CacheStorage.DISK &&
                defaults.memoryCacheMiB == 512 && defaults.hlsParallelism == 4) { "Cache defaults were unexpected" }
            val changed = defaults.copy(cacheMp4 = true, cacheStorage = CacheStorage.MEMORY,
                memoryCacheMiB = 1024, hlsParallelism = 32)
            Settings.save(changed)
            check(Settings.read() == changed) { "Cache settings did not survive the isolated preferences round trip" }
        } finally {
            Settings.initialize(targetContext)
            check(targetContext.getSharedPreferences("$prefix-settings", Context.MODE_PRIVATE).edit().clear().commit()) {
                "Could not flush isolated test preferences"
            }
            check(targetContext.deleteSharedPreferences("$prefix-settings")) { "Could not delete isolated test preferences" }
        }
    }

    private fun nativeSparseAndCrossChunk() {
        val chunkSize = MemoryMediaBuffer.CHUNK_SIZE
        NativeMemoryChunks.allocate(chunkSize).use { chunk ->
            val zeros = ByteArray(256) { 1 }
            chunk.read(91, zeros, 0, zeros.size)
            check(zeros.all { it == 0.toByte() }) { "Native allocation was not zero-filled" }
            val input = byteArrayOf(99, 31, 12, 7, 3, 1)
            chunk.write(32, input, 1, 4)
            val output = ByteArray(8) { 11 }
            chunk.read(32, output, 2, 4)
            check(output.copyOfRange(2, 6).contentEquals(input.copyOfRange(1, 5))) { "Native array offsets were not preserved" }
            check(output[0] == 11.toByte() && output[7] == 11.toByte()) { "Native read overwrote unrelated array bytes" }
        }
        val budget = MemoryBudget(chunkSize.toLong() * 8)
        val buffer = MemoryMediaBuffer(budget, NativeMemoryChunks)
        try {
            val sparseOffset = chunkSize.toLong() * 3 + 1234
            val sparse = ByteArray(511) { ((it * 67) % 251).toByte() }
            buffer.write(sparseOffset, sparse)
            check(buffer.length == sparseOffset + sparse.size) { "Sparse write length was not preserved" }
            check(buffer.allocatedBytes == chunkSize.toLong() && budget.usedBytes == chunkSize.toLong()) {
                "Sparse holes consumed cache budget"
            }
            val holes = ByteArray(chunkSize + 127) { 2 }
            check(buffer.read(chunkSize.toLong() * 2 - 63, holes) == holes.size) { "Sparse hole read was incomplete" }
            check(holes.all { it == 0.toByte() }) { "Sparse holes were not zero-filled" }
            val sparseRead = ByteArray(sparse.size)
            check(buffer.read(sparseOffset, sparseRead) == sparse.size && sparseRead.contentEquals(sparse)) {
                "Sparse native bytes changed"
            }
            val offset = chunkSize.toLong() - 29
            val crossing = ByteArray(103) { ((it * 17) % 239).toByte() }
            buffer.write(offset, crossing)
            check(buffer.allocatedBytes == chunkSize.toLong() * 3 && budget.usedBytes == chunkSize.toLong() * 3) {
                "Cross-chunk allocation did not match budget"
            }
            val crossingRead = ByteArray(crossing.size)
            check(buffer.read(offset, crossingRead) == crossing.size && crossingRead.contentEquals(crossing)) {
                "Cross-chunk native bytes changed"
            }
            buffer.write(offset + 10, byteArrayOf(4, 5, 6))
            check(budget.usedBytes == chunkSize.toLong() * 3) { "Overwriting allocated chunks changed budget" }
            buffer.reset()
            check(buffer.length == 0L && buffer.allocatedBytes == 0L && budget.usedBytes == 0L) {
                "Reset did not release native memory"
            }
            buffer.write(0, byteArrayOf(8))
        } finally {
            buffer.close()
            buffer.close()
            check(budget.usedBytes == 0L) { "Closing native buffer leaked its budget" }
        }
        var rejected = false
        try { buffer.read(0, ByteArray(1)) } catch (_: IOException) { rejected = true }
        check(rejected) { "Closed native buffer was still readable" }
    }

    private fun nativeMp4(): Bundle {
        val budget = MemoryBudget(8L * 1024 * 1024)
        val memory = MemoryOutput(targetContext, budget)
        try {
            val outputParameter = memory.parameter()
            val session = FFmpegKit.executeWithArguments(arguments(outputParameter))
            check(ReturnCode.isSuccess(session.returnCode)) {
                memory.error ?: "Native RAM conversion failed: ${session.allLogsAsString.orEmpty().takeLast(1200)}"
            }
            check(memory.error == null && budget.failure == null) { memory.error ?: budget.failure.orEmpty() }
            check(memory.buffer.length > 0 && budget.usedBytes > 0 && budget.usedBytes < budget.limitBytes) {
                "Native MP4 did not occupy its bounded RAM buffer"
            }
            // A new SAF parameter must open a fresh FD, rather than inherit the writer's offset.
            val probeParameter = memory.parameter()
            val probe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_format", "-show_streams", "-of", "json", probeParameter))
            check(ReturnCode.isSuccess(probe.returnCode)) {
                "FFprobe could not read native RAM output: ${probe.allLogsAsString.orEmpty().takeLast(1200)}"
            }
            val json = JSONObject(probe.output)
            val duration = json.getJSONObject("format").optString("duration").toDoubleOrNull()
            check(duration != null && duration.isFinite() && duration in 1.9..2.2) { "RAM MP4 duration was $duration" }
            val streams = json.getJSONArray("streams")
            val codecs = (0 until streams.length()).map(streams::getJSONObject)
            check(codecs.any { it.optString("codec_type") == "video" && it.optString("codec_name") == "h264" }) {
                "RAM MP4 video track was missing"
            }
            check(codecs.any { it.optString("codec_type") == "audio" && it.optString("codec_name") == "aac" }) {
                "RAM MP4 audio track was missing"
            }
            val boxes = topLevelBoxes(memory.buffer)
            val ftyp = boxes.indexOf("ftyp")
            val moov = boxes.indexOf("moov")
            val mdat = boxes.indexOf("mdat")
            check(ftyp >= 0 && moov > ftyp && mdat > moov) { "RAM MP4 box order was ${boxes.joinToString()}" }
            check(memory.buffer.length <= 2L * 1024 * 1024) { "Synthetic MP4 fixture was unexpectedly large" }
            mp4Fixture = ByteArray(memory.buffer.length.toInt()).also {
                check(memory.buffer.read(0, it) == it.size) { "Could not copy the small MP4 fixture" }
            }
            return Bundle().apply {
                putString("native_mp4", "PASS: FFmpeg writes SAF; fresh FD FFprobe reads H.264/AAC")
                putDouble("duration_seconds", requireNotNull(duration))
                putLong("mp4_bytes", memory.buffer.length)
                putLong("allocated_bytes", budget.usedBytes)
                putString("box_order", boxes.joinToString(","))
            }
        } finally {
            memory.close()
            check(budget.usedBytes == 0L) { "Native MP4 close leaked cache budget" }
        }
    }

    private fun quotaStopsNativeWriter() {
        val budget = MemoryBudget(MemoryMediaBuffer.CHUNK_SIZE.toLong() * 2)
        val memory = MemoryOutput(targetContext, budget)
        try {
            val session = FFmpegKit.executeWithArguments(arguments(memory.parameter()))
            check(!ReturnCode.isSuccess(session.returnCode)) { "Native writer exceeded its RAM quota without stopping" }
            check(budget.failure != null && memory.error == budget.failure) { "Provider did not report the shared quota failure" }
            check(budget.usedBytes < budget.limitBytes) { "Native allocations reached or exceeded the exclusive quota" }
            val used = budget.usedBytes
            var rejected = false
            try { memory.buffer.write(0, byteArrayOf(7)) } catch (_: IOException) { rejected = true }
            check(rejected && budget.usedBytes == used) { "Failed budget allowed another write" }
        } finally {
            memory.close()
            check(budget.usedBytes == 0L) { "Failed native writer leaked cache budget" }
        }
    }

    private fun arguments(output: String, seconds: Int = 2): Array<String> = arrayOf(
        "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc=size=160x90:rate=12:duration=$seconds",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$seconds",
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "ultrafast",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "160k", "-ac", "2", "-ar", "48000",
        "-af", "volume=4", "-t", seconds.toString(),
        "-moov_size", "1048576", "-f", "mp4", output
    )

    private fun decodeAudioEnergy(input: String): Double {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-loglevel", "info", "-i", input,
            "-map", "0:a:0", "-vn", "-af", "astats=metadata=1:reset=0", "-f", "null", "-"))
        check(ReturnCode.isSuccess(session.returnCode)) {
            "Receiver audio decode failed: ${session.allLogsAsString.orEmpty().takeLast(1200)}"
        }
        val levels = Regex("RMS level dB:\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)")
            .findAll(session.allLogsAsString.orEmpty()).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter(Double::isFinite).toList()
        val rms = levels.maxOrNull()
        check(rms != null && rms > -80.0) { "Receiver audio contained no measurable nonzero energy" }
        return requireNotNull(rms)
    }

    private data class Receiver(val control: URI, val service: String)

    /** Optional real receiver exercise; never part of the production casting entry point. */
    private fun receiverSmoke(description: String): Bundle {
        val receiver = inspectReceiver(description)
        val settings = BridgeSettings(CastMode.COMPLETE, true, 1, "", "", "KazumiBridge-receiver-fixture",
            cacheStorage = receiverStorage, memoryCacheMiB = 64)
        val directory = File(targetContext.cacheDir, "receiver-validation-${UUID.randomUUID()}")
        if (receiverStorage == CacheStorage.DISK) check(directory.mkdir()) { "Could not create receiver disk test directory" }
        val job = MediaJob("http://127.0.0.1/receiver-fixture", directory, settings, targetContext)
        val mediaServer = MediaServer()
        val budget = job.memoryBudget
        var receiverArmed = false
        val result = Bundle()
        try {
            val session = FFmpegKit.executeWithArguments(arguments(job.outputParameter(), 90))
            job.session = session
            job.complete = true
            check(ReturnCode.isSuccess(session.returnCode)) {
                job.memoryError ?: "Receiver conversion failed: ${session.allLogsAsString.orEmpty().takeLast(1200)}"
            }
            val probe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_format", "-show_streams", "-of", "json", job.outputParameter()))
            check(ReturnCode.isSuccess(probe.returnCode)) { "Receiver output failed native duration probe" }
            val data = JSONObject(probe.output)
            val duration = data.getJSONObject("format").optString("duration").toDoubleOrNull()
            check(duration != null && duration in 89.0..91.0) { "Receiver test duration was $duration" }
            job.duration = duration
            val codecs = data.getJSONArray("streams").let { streams -> (0 until streams.length()).map(streams::getJSONObject) }
            check(codecs.any { it.optString("codec_type") == "video" && it.optString("codec_name") == "h264" } &&
                codecs.any { it.optString("codec_type") == "audio" && it.optString("codec_name") == "aac" }) {
                "Receiver test lacked H.264/AAC tracks"
            }
            val audio = codecs.first { it.optString("codec_type") == "audio" }
            val audioDuration = audio.optString("duration").toDoubleOrNull()
            check(audio.optInt("channels") == 2 && audio.optString("sample_rate") == "48000" &&
                // This Android build reports AV_PROFILE_AAC_LOW as its numeric value (1).
                audio.optString("profile") in setOf("LC", "1") && audioDuration != null && audioDuration in 89.0..91.0) {
                "Receiver test AAC parameters were unexpected: ${audio.toString().take(1500)}"
            }
            val audioRms = decodeAudioEnergy(job.outputParameter())
            val boxes = job.memoryOutput?.let { topLevelBoxes(it.buffer) } ?: RandomAccessFile(job.file, "r").use { file ->
                topLevelBoxes(file.length()) { offset, bytes, destination, count ->
                    file.seek(offset)
                    file.readFully(bytes, destination, count)
                    count
                }
            }
            check(boxes.indexOf("ftyp") >= 0 && boxes.indexOf("moov") > boxes.indexOf("ftyp") &&
                boxes.indexOf("mdat") > boxes.indexOf("moov")) { "Receiver test MP4 metadata was not before media" }
            check(job.size > 0 && (if (receiverStorage == CacheStorage.MEMORY) !job.file.exists() else job.file.isFile)) {
                "Receiver test did not use the selected storage backend"
            }
            val target = RelayTarget(InetAddress.getByName(receiver.control.host),
                receiver.control.port.takeIf { it > 0 } ?: 80)
            val url = mediaServer.add(job, target)
            verifyHeadAndRange(url, job, job.size)
            val base = soapBody(receiver.service, "SetAVTransportURI", "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${Soap.escape(url)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
            val request = HttpRequest("POST", receiver.control.rawPath, mapOf("soapaction" to "\"${receiver.service}#SetAVTransportURI\""), base)
            val command = requireNotNull(Soap.parse(request))
            val setUri = Soap.replaceMedia(request, command, url, "KazumiBridge ${receiverStorage.name} Test", "00:01:30")
            receiverArmed = true
            receiverRequest(receiver, "SetAVTransportURI", setUri.body)
            receiverRequest(receiver, "Play", soapBody(receiver.service, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"))
            var state = ""
            repeat(10) {
                if (state != "PLAYING") {
                    state = transportState(receiver)
                    if (state != "PLAYING") Thread.sleep(500)
                }
            }
            check(state == "PLAYING") { "Receiver did not enter PLAYING: $state" }
            sendStatus(0, Bundle().apply {
                putString("receiver_state", "PLAYING")
                putString("receiver_host", receiver.control.host)
                putInt("receiver_port", target.port)
                putString("media_url", url)
                putString("receiver_storage", receiverStorage.name)
                putLong("ram_bytes", budget?.usedBytes ?: 0L)
                putString("receiver_audio_codec", audio.optString("codec_name"))
                putString("receiver_audio_profile", audio.optString("profile"))
                putInt("receiver_audio_channels", audio.optInt("channels"))
                putString("receiver_audio_sample_rate", audio.optString("sample_rate"))
                putDouble("receiver_audio_duration_seconds", requireNotNull(audioDuration))
                putDouble("receiver_audio_rms_db", audioRms)
                putString("receiver_audio_decode", "PASS: decoded nonzero audio energy")
                putInt("hold_seconds", 90)
                putString("stream", "${receiverStorage.name} test is playing: moving color pattern with decoded stereo 440 Hz tone; 90-second confirmation window.\n")
            })
            val until = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < until) Thread.sleep(1000)
            receiverRequest(receiver, "Stop", soapBody(receiver.service, "Stop", "<InstanceID>0</InstanceID>"))
            receiverArmed = false
            result.putString("receiver_state", transportState(receiver))
            result.putString("receiver_initial_state", "PLAYING")
            result.putString("receiver_smoke", "PASS: 90-second complete ${receiverStorage.name} MP4 served to real receiver; Stop sent")
            result.putString("receiver_storage", receiverStorage.name)
            result.putString("receiver_audio_codec", audio.optString("codec_name"))
            result.putString("receiver_audio_profile", audio.optString("profile"))
            result.putInt("receiver_audio_channels", audio.optInt("channels"))
            result.putString("receiver_audio_sample_rate", audio.optString("sample_rate"))
            result.putDouble("receiver_audio_duration_seconds", requireNotNull(audioDuration))
            result.putDouble("receiver_audio_rms_db", audioRms)
            result.putString("receiver_audio_decode", "PASS: decoded nonzero audio energy")
            result.putLong("receiver_mp4_bytes", job.size)
            result.putLong("receiver_ram_bytes", budget?.usedBytes ?: 0L)
            result.putDouble("receiver_duration_seconds", requireNotNull(duration))
            result.putString("receiver_box_order", boxes.joinToString(","))
            return result
        } finally {
            try {
                if (receiverArmed) receiverRequest(receiver, "Stop", soapBody(receiver.service, "Stop", "<InstanceID>0</InstanceID>"))
            } finally {
                job.complete = true
                job.cancel()
                mediaServer.remove(job)
                mediaServer.close()
                check((budget?.usedBytes ?: 0L) == 0L && !job.file.exists()) { "Receiver test leaked RAM or left disk media" }
                directory.delete()
                result.putLong("receiver_budget_after_close", budget?.usedBytes ?: 0L)
            }
        }
    }

    private fun privateHttpUri(value: String): URI {
        val uri = try { URI(value) } catch (_: Exception) { throw IOException("Invalid renderer HTTP address") }
        val parts = uri.host.orEmpty().split('.')
        check(uri.scheme == "http" && parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
        } && uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "Renderer address must use private IPv4 HTTP"
        }
        val address = InetAddress.getByName(uri.host)
        check(address.isSiteLocalAddress || address.isLinkLocalAddress) { "Renderer address is outside the local network" }
        return uri
    }

    private fun xmlDocument(bytes: ByteArray): org.w3c.dom.Document {
        val text = bytes.toString(Charsets.UTF_8)
        check(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "Renderer XML declarations are forbidden" }
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            check(output.size() <= 256 * 1024 - count) { "Renderer HTTP response exceeded its limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun inspectReceiver(description: String): Receiver {
        val location = privateHttpUri(description)
        val connection = location.toURL().openConnection() as HttpURLConnection
        val document = try {
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Renderer description returned HTTP ${connection.responseCode}" }
            xmlDocument(connection.inputStream.use(::readBounded))
        } finally { connection.disconnect() }
        val bases = document.getElementsByTagNameNS("*", "URLBase")
        val base = if (bases.length > 0 && bases.item(0).textContent.isNotBlank()) {
            privateHttpUri(location.resolve(bases.item(0).textContent.trim()).toASCIIString())
        } else location
        val services = document.getElementsByTagNameNS("*", "service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? Element ?: continue
            val type = service.getElementsByTagNameNS("*", "serviceType").item(0)?.textContent?.trim().orEmpty()
            if (!Regex("urn:schemas-upnp-org:service:AVTransport:[1-9][0-9]*").matches(type)) continue
            val control = service.getElementsByTagNameNS("*", "controlURL").item(0)?.textContent?.trim().orEmpty()
            check(control.isNotEmpty()) { "Renderer AVTransport control URL was missing" }
            return Receiver(privateHttpUri(base.resolve(control).toASCIIString()), type)
        }
        error("Renderer description did not contain AVTransport")
    }

    private fun soapBody(service: String, action: String, fields: String): ByteArray =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${Soap.escape(service)}\">" +
            "$fields</u:$action></s:Body></s:Envelope>").toByteArray()

    private fun receiverRequest(receiver: Receiver, action: String, body: ByteArray): ByteArray {
        val connection = receiver.control.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"${receiver.service}#$action\"")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode in 200..299) { "Renderer $action returned HTTP ${connection.responseCode}" }
            return connection.inputStream.use(::readBounded)
        } finally { connection.disconnect() }
    }

    private fun transportState(receiver: Receiver): String {
        val bytes = receiverRequest(receiver, "GetTransportInfo",
            soapBody(receiver.service, "GetTransportInfo", "<InstanceID>0</InstanceID>"))
        val document = xmlDocument(bytes)
        val states = document.getElementsByTagNameNS("*", "CurrentTransportState")
        check(states.length == 1) { "Renderer transport state was missing" }
        return states.item(0).textContent.trim()
    }

    private fun tsFixture(): ByteArray {
        val budget = MemoryBudget(4L * 1024 * 1024)
        val memory = MemoryOutput(targetContext, budget)
        try {
            val parameter = memory.parameter()
            val args = (arguments(parameter).dropLast(5) + listOf("-f", "mpegts", parameter)).toTypedArray()
            val session = FFmpegKit.executeWithArguments(args)
            check(ReturnCode.isSuccess(session.returnCode)) {
                memory.error ?: "Synthetic TS conversion failed: ${session.allLogsAsString.orEmpty().takeLast(1000)}"
            }
            check(memory.buffer.length in 1..512L * 1024) { "Synthetic TS fixture was empty or unexpectedly large" }
            return ByteArray(memory.buffer.length.toInt()).also {
                check(memory.buffer.read(0, it) == it.size) { "Could not read small TS fixture" }
            }
        } finally {
            memory.close()
            check(budget.usedBytes == 0L) { "Synthetic TS output leaked its budget" }
        }
    }

    private fun runtimeSmoke(): Bundle {
        val results = Bundle()
        val ts = tsFixture()
        val directory = File(targetContext.cacheDir, "memory-validation-${UUID.randomUUID()}")
        val engine = MediaEngine(targetContext, directory)
        val mediaServer = MediaServer()
        val fixture = FixtureServer(requireNotNull(mp4Fixture), ts)
        val settings = BridgeSettings(CastMode.COMPLETE, false, 1,
            "https://fixture.invalid/player/", "fixture=yes", "KazumiBridge-RAM-fixture",
            hlsParallelism = 32, cacheStorage = CacheStorage.MEMORY, memoryCacheMiB = 64)
        val jobs = ArrayList<MediaJob>()
        try {
            fun verify(path: String, configuration: BridgeSettings, expectedDirect: Boolean) {
                val finished = CountDownLatch(1)
                val error = AtomicReference<String?>()
                val current = AtomicBoolean(true)
                val job = engine.prepare(fixture.url(path), configuration, current::get,
                    ready = { finished.countDown() }, failed = { error.set(it); finished.countDown() })
                jobs.add(job)
                check(finished.await(35, TimeUnit.SECONDS)) { "Runtime $path preparation timed out" }
                check(error.get() == null) { "Runtime $path preparation failed: ${error.get()}" }
                check(job.complete && job.direct == expectedDirect && job.error == null) { "Runtime $path selected an unexpected media mode" }
                if (expectedDirect) {
                    check(job.memoryOutput == null && !job.file.exists()) { "MP4 caching disabled still created cache media" }
                } else if (configuration.cacheStorage == CacheStorage.MEMORY) {
                    check(job.memoryOutput != null && job.size > 0 && !job.file.exists()) { "RAM job created disk media or lacked memory output" }
                    check(directory.walkTopDown().none { it.isFile }) { "RAM runtime wrote cache files" }
                } else {
                    check(job.memoryOutput == null && job.file.isFile && job.size > 0) { "Disk MP4 cache was not created" }
                }
                val url = mediaServer.add(job, RelayTarget(InetAddress.getByName("127.0.0.1"), fixture.port))
                val length = if (expectedDirect) requireNotNull(mp4Fixture).size.toLong() else job.size
                verifyHeadAndRange(url, job, length)
                current.set(false)
                job.cancel()
                mediaServer.remove(job)
                check((job.memoryBudget?.usedBytes ?: 0L) == 0L) { "Runtime cache budget remained after cancel/remove" }
                check(!job.file.exists()) { "Runtime disk cache remained after cancel/remove" }
                val removed = URL(url).openConnection() as HttpURLConnection
                try {
                    removed.requestMethod = "HEAD"
                    removed.connectTimeout = 3000
                    removed.readTimeout = 3000
                    check(removed.responseCode == 404) { "Removed media URL was still accessible" }
                } finally { removed.disconnect() }
            }
            verify("/video.m3u8", settings, expectedDirect = false)
            results.putString("runtime_hls", "PASS: MEMORY/COMPLETE, 32 configured workers, native duration validation, HEAD/Range")
            verify("/video.mp4", settings.copy(cacheMp4 = true), expectedDirect = false)
            results.putString("runtime_mp4_memory", "PASS: complete RAM cache, HEAD/Range, cancel/remove releases budget")
            verify("/video.mp4", settings.copy(cacheMp4 = false), expectedDirect = true)
            results.putString("runtime_mp4_direct", "PASS: caching off preserves source forwarding without media cache")
            verify("/video.mp4", settings.copy(cacheMp4 = true, cacheStorage = CacheStorage.DISK), expectedDirect = false)
            results.putString("runtime_mp4_disk", "PASS: complete disk cache, HEAD/Range, cancel/remove deletes media")
            return results
        } finally {
            jobs.forEach { it.cancel(); mediaServer.remove(it) }
            mediaServer.close()
            fixture.close()
            engine.close()
            check(directory.walkTopDown().none { it.isFile }) { "Runtime smoke left cache files" }
            directory.delete()
        }
    }

    private fun verifyHeadAndRange(url: String, job: MediaJob, length: Long) {
        fun request(method: String, range: String? = null, inspect: (HttpURLConnection) -> Unit) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                range?.let { connection.setRequestProperty("Range", it) }
                inspect(connection)
            } finally { connection.disconnect() }
        }
        request("HEAD") {
            check(it.responseCode == 200 && it.contentLengthLong == length) { "MediaServer HEAD returned a wrong size" }
            check(it.getHeaderField("Content-Type") == "video/mp4") { "MediaServer HEAD lacked MP4 type" }
        }
        request("GET", "bytes=0-511") {
            check(it.responseCode == 206 && it.getHeaderField("Content-Range") == "bytes 0-511/$length") {
                "MediaServer did not honor byte Range"
            }
            val bytes = it.inputStream.use { input -> input.readBytes() }
            val expected = ByteArray(512)
            val memory = job.memoryOutput?.buffer
            if (job.direct) requireNotNull(mp4Fixture).copyInto(expected, 0, 0, 512)
            else if (memory != null) check(memory.read(0, expected) == expected.size)
            else job.file.inputStream().use { input ->
                var offset = 0
                while (offset < expected.size) {
                    val count = input.read(expected, offset, expected.size - offset)
                    check(count > 0) { "Disk cached MP4 prefix was incomplete" }
                    offset += count
                }
            }
            check(bytes.contentEquals(expected)) { "MediaServer byte Range did not match cached media" }
        }
    }

    private class FixtureServer(private val mp4: ByteArray, private val ts: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        @Volatile private var closed = false
        val port: Int get() = server.localPort
        private val playlist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
            "#EXTINF:2.000,\nsegment.ts?key=fixture%2Bsignature\n#EXT-X-ENDLIST\n").toByteArray()
        init {
            workers.execute {
                while (!closed) {
                    val client = try { server.accept() } catch (_: IOException) { break }
                    clients.add(client)
                    workers.execute {
                        try { client.use(::serve) } catch (_: IOException) { /* A probe may stop after its prefix. */ }
                        finally { clients.remove(client) }
                    }
                }
            }
        }
        fun url(path: String): String = "http://127.0.0.1:$port$path?key=fixture%2Bsignature"
        private fun serve(socket: Socket) {
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            val request = HttpWire.readRequest(socket.getInputStream().buffered(), output) ?: return
            val uri = URI(request.path)
            if (request.method !in setOf("GET", "HEAD") || uri.rawQuery != "key=fixture%2Bsignature" ||
                request.header("user-agent") != "KazumiBridge-RAM-fixture" ||
                request.header("referer") != "https://fixture.invalid/player/" || request.header("cookie") != "fixture=yes") {
                HttpResponse(403, "Forbidden").writeTo(output)
                return
            }
            val bytes = when (uri.path) {
                "/video.mp4" -> mp4
                "/video.m3u8" -> playlist
                "/segment.ts" -> ts
                else -> { HttpResponse(404, "Not Found").writeTo(output); return }
            }
            val range = ByteRanges.parse(request.header("range"), bytes.size.toLong())
            if (range == RangeResult.Invalid) {
                HttpResponse(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${bytes.size}")).writeTo(output)
                return
            }
            val selection = if (range is RangeResult.Partial) range.range else ByteRange(0, bytes.size.toLong() - 1)
            val headers = mapOf("Content-Type" to when (uri.path) {
                "/video.mp4" -> "video/mp4"
                "/video.m3u8" -> "application/vnd.apple.mpegurl"
                else -> "video/mp2t"
            }, "Accept-Ranges" to "bytes", "ETag" to "\"fixture-stable\"", "Content-Length" to selection.length.toString()) +
                if (range is RangeResult.Partial) mapOf("Content-Range" to "bytes ${selection.start}-${selection.end}/${bytes.size}") else emptyMap()
            HttpResponse(if (range is RangeResult.Partial) 206 else 200,
                if (range is RangeResult.Partial) "Partial Content" else "OK", headers,
                bytes.copyOfRange(selection.start.toInt(), selection.end.toInt() + 1)).writeTo(output, request.method == "HEAD")
        }
        override fun close() {
            closed = true
            server.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    private fun topLevelBoxes(buffer: MemoryMediaBuffer): List<String> = topLevelBoxes(buffer.length, buffer::read)

    private fun topLevelBoxes(length: Long, read: (Long, ByteArray, Int, Int) -> Int): List<String> {
        val boxes = ArrayList<String>()
        var position = 0L
        while (position < length) {
            check(boxes.size < 128 && length - position >= 8) { "Invalid MP4 box header" }
            val header = ByteArray(16)
            check(read(position, header, 0, 8) == 8) { "MP4 box read was incomplete" }
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            var size = bytes.int.toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                check(read(position + 8, header, 8, 8) == 8) { "Extended MP4 box header was incomplete" }
                size = bytes.getLong(8)
                headerSize = 16
            } else if (size == 0L) size = length - position
            check(size >= headerSize && size <= length - position) { "Invalid MP4 box size" }
            boxes.add(type)
            position += size
        }
        return boxes
    }

    private fun cacheFiles(): Set<String> = buildSet {
        val roots = listOf(targetContext.cacheDir) + targetContext.externalCacheDirs.filterNotNull()
        roots.distinctBy(File::getAbsolutePath).forEachIndexed { index, root ->
            if (root.exists()) root.walkTopDown().filter(File::isFile).forEach {
                add("$index/${it.relativeTo(root).invariantSeparatorsPath}")
            }
        }
    }
}
