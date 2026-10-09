package io.github.kazumibridge.runtime

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import io.github.kazumibridge.core.CastMode
import io.github.kazumibridge.core.HlsDownloadCache
import io.github.kazumibridge.core.HlsSnapshot
import io.github.kazumibridge.core.MediaPolicy
import io.github.kazumibridge.core.ProbeResult
import io.github.kazumibridge.core.SourceProbe
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaJob(val source: String, val directory: File, val settings: BridgeSettings) {
    val id: String = UUID.randomUUID().toString().replace("-", "")
    val file = File(directory, "$id.mp4")
    val monitor = Object()
    @Volatile var canceled = false
    @Volatile var complete = false
    @Volatile var direct = false
    @Volatile var error: String? = null
    @Volatile var duration: Double? = null
    @Volatile var session: FFmpegSession? = null
    @Volatile var connection: HttpURLConnection? = null
    @Volatile var snapshot: HlsSnapshot? = null
    fun cancel() {
        canceled = true
        session?.cancel()
        connection?.disconnect()
        snapshot?.close()
        synchronized(monitor) { monitor.notifyAll() }
    }
}

class MediaEngine(private val directory: File) : AutoCloseable {
    private val workers: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "KazumiBridge-media") }
    init {
        directory.mkdirs()
        MediaCache.clear(directory)
        FFmpegKitConfig.setSessionHistorySize(4)
        FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
    }

    fun prepare(source: String, settings: BridgeSettings, current: () -> Boolean, ready: (MediaJob) -> Unit, failed: (String) -> Unit): MediaJob {
        val job = MediaJob(source, directory, settings)
        workers.execute {
            try {
                if (job.canceled || !current()) return@execute
                BridgeState.update { it.copy(stage = "识别视频", detail = "读取容器和音视频编码", sourceHost = URI(source).host.orEmpty(), progress = null, convertedBytes = 0, error = null) }
                val content = try {
                    sourceContent(job)
                } catch (error: SourceProbe.HttpFailure) {
                    if (error.status != 403 || error.expired) throw error
                    // The native player and Java HTTP stack can receive different responses.
                    // A successful native demux/probe is still required before accepting media.
                    diagnostic("Java source probe rejected with 403; trying native media probe with the same source headers")
                    SourceProbe.Content(byteArrayOf(), null, source)
                }
                if (job.canceled || !current()) return@execute
                val prefix = content.prefix
                content.manifest?.let { manifest ->
                    val download = if (settings.mode == CastMode.COMPLETE) HlsDownloadCache.Options(
                        directory = File(directory, "${job.id}.hls"), parallelism = settings.hlsParallelism,
                        maxBytes = settings.cacheGiB * 1024L * 1024 * 1024,
                        otherBytes = { job.file.length() }, diagnostic = ::diagnostic) else null
                    // Renderer replacement cancels the old job; keep its lock out of cache callbacks.
                    job.snapshot = HlsSnapshot.create(content.resolvedSource, manifest, settings.headers(), download) { job.canceled }
                    diagnostic("HLS manifest bytes=${manifest.size}; snapshot=${job.snapshot != null}")
                }
                if (job.canceled || !current()) return@execute
                val inputSource = job.snapshot?.url ?: source
                val hlsHint = MediaPolicy.isHls(prefix) || (prefix.isEmpty() && URI(source).path.orEmpty().lowercase().endsWith(".m3u8"))
                val args = listOf("-v", "error") + inputOptions(settings, hlsHint) + listOf("-show_format", "-show_streams", "-of", "json", inputSource)
                val probeSession = FFprobeKit.executeWithArguments(args.toTypedArray())
                if (job.canceled || !current()) return@execute
                job.snapshot?.failure?.let { error(it) }
                if (!ReturnCode.isSuccess(probeSession.returnCode)) diagnostic("native source probe failed; nativeLog=${sanitize(probeSession.allLogsAsString.orEmpty().takeLast(1500), settings)}")
                check(ReturnCode.isSuccess(probeSession.returnCode)) { job.snapshot?.failure ?: "无法识别视频，请检查网络或在高级设置中填写来源请求头。" }
                val data = JSONObject(probeSession.output)
                val streams = data.getJSONArray("streams")
                val video = (0 until streams.length()).map(streams::getJSONObject).firstOrNull { it.optString("codec_type") == "video" }
                    ?: error("视频地址不包含视频轨道。")
                val audio = (0 until streams.length()).map(streams::getJSONObject).firstOrNull { it.optString("codec_type") == "audio" }
                val format = data.getJSONObject("format")
                val probe = ProbeResult(format.optString("format_name"), video.optString("codec_name"), audio?.optString("codec_name"),
                    video.optString("pix_fmt"), audio?.optInt("channels", 2) ?: 0, format.optString("duration").toDoubleOrNull())
                val expectedVideo = video.optString("duration").toDoubleOrNull() ?: probe.duration
                val expectedAudio = audio?.optString("duration")?.toDoubleOrNull() ?: probe.duration?.takeIf { audio != null }
                job.duration = expectedVideo ?: probe.duration
                diagnostic("input format=${probe.format}; video=${probe.videoCodec}/${probe.pixelFormat}; formatDuration=${probe.duration}; videoDuration=$expectedVideo; audioDuration=$expectedAudio")
                val plan = MediaPolicy.plan(probe, prefix, settings.forceH264)
                BridgeState.event("识别 ${probe.format} / ${probe.videoCodec} / ${probe.audioCodec ?: "无音轨"}")
                if (plan.passthrough) {
                    job.direct = true
                    job.complete = true
                    BridgeState.update { it.copy(stage = "MP4 直接投屏", detail = "通过本机转发原视频，无需转换", progress = null) }
                    ready(job)
                    return@execute
                }
                BridgeState.update { it.copy(stage = if (plan.copyVideo && plan.copyAudio) "重新封装 MP4" else "转换视频编码",
                    detail = if (settings.mode == CastMode.COMPLETE) "完整生成后开始投屏" else "首个完整视频分片可用后开始投屏", progress = null) }
                val finished = CountDownLatch(1)
                job.session = FFmpegKit.executeWithArgumentsAsync(
                    MediaPolicy.arguments(inputSource, job.file.absolutePath, plan, settings.mode, inputOptions(settings, probe.format == "hls"),
                        logLevel = if (probe.format == "hls") "warning" else "error").toTypedArray(),
                    { session ->
                        if (!ReturnCode.isSuccess(session.returnCode) && !job.canceled && job.error == null) {
                            diagnostic("native conversion failed; bytes=${job.file.length()}; ${SourceProbe.addressDiagnostic(job.source)}; nativeLog=${sanitize(SourceProbe.redactLocations(session.allLogsAsString.orEmpty().takeLast(2000), job.source), settings)}")
                            job.error = job.snapshot?.failure ?: sanitize(session.allLogsAsString.orEmpty().takeLast(1500), settings).ifBlank { "视频转换失败（返回码 ${session.returnCode}）。" }
                        }
                        job.complete = true
                        synchronized(job.monitor) { job.monitor.notifyAll() }
                        finished.countDown()
                    },
                    { /* FFmpeg retains diagnostics privately; raw media URLs are not printed. */ },
                    { statistics ->
                        if (current() && !job.canceled) {
                            val seconds = statistics.time.toDouble() / 1000.0
                            BridgeState.update { it.copy(progress = job.duration?.takeIf { total -> total > 0 }?.let { total -> (seconds / total).coerceIn(0.0, 1.0) }, convertedBytes = job.file.length()) }
                        }
                    })
                var announced = false
                while (!finished.await(250, TimeUnit.MILLISECONDS)) {
                    if (job.canceled || !current()) { job.session?.cancel(); continue }
                    job.snapshot?.failure?.let { message ->
                        if (job.error == null) { job.error = message; job.session?.cancel() }
                    }
                    if (job.file.length() + (job.snapshot?.downloadBytes ?: 0L) > settings.cacheGiB * 1024L * 1024 * 1024 || directory.usableSpace < 128L * 1024 * 1024) {
                        job.error = "缓存达到上限或剩余空间不足；请清理空间或调整缓存上限。"
                        job.session?.cancel()
                    }
                    if (job.error == null && !announced && settings.mode == CastMode.STREAM && MediaPolicy.fragmentedReady(job.file)) {
                        announced = true
                        ready(job)
                    }
                    synchronized(job.monitor) { job.monitor.notifyAll() }
                }
                if (job.canceled || !current()) return@execute
                job.snapshot?.downloadStats?.let { diagnostic("HLS download summary=$it") }
                job.snapshot?.failure?.let { error(it) }
                job.error?.let { throw IllegalStateException(it) }
                check(job.file.length() > 0) { "转换未生成 MP4 文件。" }
                BridgeState.update { it.copy(detail = "校验生成文件的音视频时长") }
                val outputProbe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams", "-of", "json", job.file.absolutePath))
                check(ReturnCode.isSuccess(outputProbe.returnCode)) { "生成的 MP4 无法读取。" }
                val outputStreams = JSONObject(outputProbe.output).getJSONArray("streams")
                fun outputDuration(type: String): Double? = (0 until outputStreams.length()).map(outputStreams::getJSONObject)
                    .firstOrNull { it.optString("codec_type") == type }?.optString("duration")?.toDoubleOrNull()
                val actualVideo = outputDuration("video")
                val actualAudio = outputDuration("audio")
                diagnostic("output bytes=${job.file.length()}; videoDuration=$actualVideo; audioDuration=$actualAudio")
                try {
                    job.duration = MediaPolicy.completedDuration(expectedVideo, actualVideo, expectedAudio, actualAudio)
                } catch (error: IllegalArgumentException) {
                    diagnostic("conversion validation failed; nativeLog=${sanitize(job.session?.allLogsAsString.orEmpty().takeLast(1500), settings)}")
                    throw error
                }
                if (!announced) ready(job)
                if (current()) BridgeState.update { it.copy(detail = if (settings.mode == CastMode.STREAM) "MP4 已生成；分片模式的进度拖动取决于接收设备。" else "MP4 已生成，支持接收设备按字节读取", progress = 1.0, convertedBytes = job.file.length()) }
            } catch (error: Throwable) {
                if (!job.canceled && current()) {
                    job.error = sanitize(error.message ?: error.javaClass.simpleName, settings)
                    failed(job.error!!)
                }
            } finally {
                job.complete = true
                job.connection?.disconnect()
                job.snapshot?.close()
                synchronized(job.monitor) { job.monitor.notifyAll() }
                if (job.canceled || job.error != null) job.file.delete()
            }
        }
        return job
    }

    private fun sourceContent(job: MediaJob): SourceProbe.Content {
        val headers = job.settings.headers()
        diagnostic("source header lengths=${headers.mapValues { it.value.length }}")
        val refererHost = runCatching { URI(headers["Referer"].orEmpty()).host }.getOrNull()
        diagnostic("source scheme=${URI(job.source).scheme}; manifestSuffix=${URI(job.source).path.orEmpty().substringAfterLast('.').takeLast(12)}; refererHost=$refererHost")
        return SourceProbe.readContent(job.source, headers, canceled = { job.canceled }, connectionChanged = { job.connection = it }, response = {
            diagnostic("source HTTP=${it.status}; ranged=${it.ranged}; contentLength=${it.length}; contentRange=${it.range}; server=${it.server}; type=${it.type}; serverTimeOffsetSeconds=${it.serverTimeOffsetSeconds}; title=${sanitize(it.errorTitle.orEmpty(), job.settings)}")
        })
    }

    private fun inputOptions(settings: BridgeSettings, hls: Boolean): List<String> = MediaPolicy.networkOptions(settings.headers(), hls)

    private fun diagnostic(message: String) { android.util.Log.i("KazumiBridgeDiagnostics", message) }

    private fun sanitize(message: String, settings: BridgeSettings): String {
        var result = message.replace(Regex("https?://[^\\s\\\"']+"), "[视频地址]")
        for (secret in listOf(settings.cookie, settings.referer)) if (secret.isNotBlank()) result = result.replace(secret, "[已隐藏]")
        return result.takeLast(700)
    }

    override fun close() { workers.shutdownNow() }
}
