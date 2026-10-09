package io.github.kazumibridge.runtime

import android.content.Context
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import io.github.kazumibridge.core.CastMode
import io.github.kazumibridge.core.CacheStorage
import io.github.kazumibridge.core.CachedMp4Download
import io.github.kazumibridge.core.MemoryBudget
import io.github.kazumibridge.core.HlsDownloadCache
import io.github.kazumibridge.core.HlsSnapshot
import io.github.kazumibridge.core.MediaPolicy
import io.github.kazumibridge.core.ProbeResult
import io.github.kazumibridge.core.SourceProbe
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaJob(val source: String, val directory: File, val settings: BridgeSettings, private val context: Context) {
    val id: String = UUID.randomUUID().toString().replace("-", "")
    val file = File(directory, "$id.mp4")
    val monitor = Object()
    val memoryBudget = if (settings.cacheStorage == CacheStorage.MEMORY) MemoryBudget(settings.cacheLimitBytes) else null
    @Volatile var memoryOutput: MemoryOutput? = null
        private set
    val size: Long get() = if (memoryBudget != null) memoryOutput?.buffer?.length ?: 0 else file.length()
    val memoryError: String? get() = memoryBudget?.failure ?: memoryOutput?.error
    @Synchronized fun memoryStore(): MemoryOutput {
        check(!canceled) { "投屏已停止。" }
        return memoryOutput ?: MemoryOutput(context, requireNotNull(memoryBudget)).also { memoryOutput = it }
    }
    fun outputParameter(): String = if (memoryBudget == null) file.absolutePath else memoryStore().parameter()
    @Synchronized fun releaseMedia() {
        memoryOutput?.close()
        if (memoryBudget == null) file.delete()
    }
    fun fragmentedReady(): Boolean = memoryOutput?.let { output ->
        MediaPolicy.fragmentedReady(output.buffer.length) { offset, data -> output.buffer.read(offset, data) }
    } ?: MediaPolicy.fragmentedReady(file)
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
        if (session == null || complete) releaseMedia()
        synchronized(monitor) { monitor.notifyAll() }
    }
}

class MediaEngine(private val context: Context, private val directory: File) : AutoCloseable {
    private val workers: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "KazumiBridge-media") }
    init {
        directory.mkdirs()
        MediaCache.clear(directory)
        FFmpegKitConfig.setSessionHistorySize(4)
        FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
    }

    fun prepare(source: String, settings: BridgeSettings, current: () -> Boolean, ready: (MediaJob) -> Unit, failed: (String) -> Unit): MediaJob {
        val job = MediaJob(source, directory, settings, context)
        workers.execute {
            try {
                if (job.canceled || !current()) return@execute
                BridgeState.update { it.copy(stage = "识别视频", detail = "读取容器和音视频编码", sourceHost = URI(source).host.orEmpty(), progress = null, convertedBytes = 0, memoryCacheBytes = 0, error = null) }
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
                        maxBytes = settings.cacheLimitBytes,
                        otherBytes = { if (job.memoryBudget == null) job.size else 0 }, diagnostic = ::diagnostic,
                        storage = settings.cacheStorage, memoryBudget = job.memoryBudget, memoryFactory = NativeMemoryChunks) else null
                    // Renderer replacement cancels the old job; keep its lock out of cache callbacks.
                    job.snapshot = HlsSnapshot.create(content.resolvedSource, manifest, settings.headers(), download, memoryBudget = job.memoryBudget) { job.canceled }
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
                    if (settings.cacheMp4) {
                        cacheMp4(job, current)
                        if (job.canceled || !current()) return@execute
                        validateOutput(job, expectedVideo, expectedAudio)
                        job.complete = true
                        ready(job)
                        if (current()) BridgeState.update { it.copy(detail = "MP4 完整缓存，支持接收设备按字节读取", progress = 1.0, convertedBytes = job.size, memoryCacheBytes = job.memoryBudget?.usedBytes ?: 0) }
                        return@execute
                    }
                    job.direct = true
                    job.complete = true
                    BridgeState.update { it.copy(stage = "MP4 直接投屏", detail = "通过本机转发原视频，无需转换", progress = null) }
                    ready(job)
                    return@execute
                }
                BridgeState.update { it.copy(stage = if (plan.copyVideo && plan.copyAudio) "重新封装 MP4" else "转换视频编码",
                    detail = if (settings.mode == CastMode.COMPLETE) "完整生成后开始投屏" else "首个完整视频分片可用后开始投屏", progress = null) }
                val finished = CountDownLatch(1)
                val output = job.outputParameter()
                job.session = FFmpegKit.executeWithArgumentsAsync(
                    MediaPolicy.arguments(inputSource, output, plan, settings.mode, inputOptions(settings, probe.format == "hls"),
                        logLevel = if (probe.format == "hls") "warning" else "error",
                        moovReserveBytes = if (job.memoryBudget != null && settings.mode == CastMode.COMPLETE) moovReserve(job.duration) else null).toTypedArray(),
                    { session ->
                        if (!ReturnCode.isSuccess(session.returnCode) && !job.canceled && job.error == null) {
                            diagnostic("native conversion failed; bytes=${job.size}; ${SourceProbe.addressDiagnostic(job.source)}; nativeLog=${sanitize(SourceProbe.redactLocations(session.allLogsAsString.orEmpty().takeLast(2000), job.source), settings)}")
                            job.error = job.memoryError ?: job.snapshot?.failure ?: sanitize(session.allLogsAsString.orEmpty().takeLast(1500), settings).ifBlank { "视频转换失败（返回码 ${session.returnCode}）。" }
                        }
                        job.complete = true
                        synchronized(job.monitor) { job.monitor.notifyAll() }
                        finished.countDown()
                    },
                    { /* FFmpeg retains diagnostics privately; raw media URLs are not printed. */ },
                    { statistics ->
                        if (current() && !job.canceled) {
                            val seconds = statistics.time.toDouble() / 1000.0
                            BridgeState.update { it.copy(progress = job.duration?.takeIf { total -> total > 0 }?.let { total -> (seconds / total).coerceIn(0.0, 1.0) }, convertedBytes = job.size, memoryCacheBytes = job.memoryBudget?.usedBytes ?: 0) }
                        }
                    })
                var announced = false
                while (!finished.await(250, TimeUnit.MILLISECONDS)) {
                    if (job.canceled || !current()) { job.session?.cancel(); continue }
                    job.snapshot?.failure?.let { message ->
                        if (job.error == null) { job.error = message; job.session?.cancel() }
                    }
                    if (job.memoryError != null) {
                        job.error = job.memoryError
                        job.session?.cancel()
                    } else if (job.memoryBudget == null && (job.size + (job.snapshot?.downloadBytes ?: 0L) >= settings.cacheLimitBytes || directory.usableSpace < 128L * 1024 * 1024)) {
                        job.error = "缓存达到上限或剩余空间不足；请清理空间或调整缓存上限。"
                        job.session?.cancel()
                    }
                    if (job.error == null && !announced && settings.mode == CastMode.STREAM && job.fragmentedReady()) {
                        announced = true
                        ready(job)
                    }
                    synchronized(job.monitor) { job.monitor.notifyAll() }
                }
                if (job.canceled || !current()) return@execute
                job.snapshot?.downloadStats?.let { diagnostic("HLS download summary=$it") }
                job.memoryError?.let { error(it) }
                job.error?.let { throw IllegalStateException(it) }
                job.snapshot?.failure?.let { error(it) }
                check(job.size > 0) { "转换未生成 MP4 文件。" }
                BridgeState.update { it.copy(detail = "校验生成文件的音视频时长") }
                try {
                    validateOutput(job, expectedVideo, expectedAudio)
                } catch (error: IllegalArgumentException) {
                    diagnostic("conversion validation failed; nativeLog=${sanitize(job.session?.allLogsAsString.orEmpty().takeLast(1500), settings)}")
                    throw error
                }
                if (!announced) ready(job)
                if (current()) BridgeState.update { it.copy(detail = if (settings.mode == CastMode.STREAM) "MP4 已生成；分片模式的进度拖动取决于接收设备。" else "MP4 已生成，支持接收设备按字节读取", progress = 1.0, convertedBytes = job.size, memoryCacheBytes = job.memoryBudget?.usedBytes ?: 0) }
            } catch (error: Throwable) {
                if (!job.canceled && current()) {
                    job.error = sanitize(job.memoryError ?: job.error ?: error.message ?: error.javaClass.simpleName, settings)
                    failed(job.error!!)
                }
            } finally {
                job.complete = true
                job.connection?.disconnect()
                job.snapshot?.close()
                synchronized(job.monitor) { job.monitor.notifyAll() }
                if (job.canceled || job.error != null || !current()) job.releaseMedia()
                if (current()) BridgeState.update { it.copy(memoryCacheBytes = job.memoryBudget?.usedBytes ?: 0) }
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

    private fun cacheMp4(job: MediaJob, current: () -> Boolean) {
        BridgeState.update { it.copy(stage = "缓存 MP4", detail = "完整下载后开始投屏", progress = 0.0) }
        val memory = job.memoryBudget?.let { job.memoryStore().buffer }
        val disk = if (memory == null) RandomAccessFile(job.file, "rw") else null
        try {
            CachedMp4Download.download(job.source, job.settings.headers(), job.settings.cacheLimitBytes,
                canceled = { job.canceled || !current() }, connectionChanged = { job.connection = it },
                reset = { if (memory != null) memory.reset() else disk!!.setLength(0) },
                write = { offset, data, count ->
                    if (memory != null) memory.write(offset, data, 0, count) else {
                        check(directory.usableSpace >= 128L * 1024 * 1024) { "剩余空间不足，已停止缓存。" }
                        disk!!.seek(offset); disk.write(data, 0, count)
                    }
                }, progress = { downloaded, total ->
                    if (!job.canceled && current()) BridgeState.update { it.copy(
                        progress = total?.let { bytes -> (downloaded.toDouble() / bytes).coerceIn(0.0, 1.0) },
                        convertedBytes = downloaded, memoryCacheBytes = job.memoryBudget?.usedBytes ?: 0) }
                })
        } finally { disk?.close() }
        check(job.size > 0) { "缓存 MP4 为空。" }
    }

    private fun validateOutput(job: MediaJob, expectedVideo: Double?, expectedAudio: Double?) {
        val outputProbe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams", "-of", "json", job.outputParameter()))
        check(ReturnCode.isSuccess(outputProbe.returnCode)) { job.memoryError ?: "生成的 MP4 无法读取。" }
        val streams = JSONObject(outputProbe.output).getJSONArray("streams")
        fun duration(type: String): Double? = (0 until streams.length()).map(streams::getJSONObject)
            .firstOrNull { it.optString("codec_type") == type }?.optString("duration")?.toDoubleOrNull()
        val video = duration("video")
        val audio = duration("audio")
        diagnostic("output bytes=${job.size}; videoDuration=$video; audioDuration=$audio")
        job.duration = MediaPolicy.completedDuration(expectedVideo, video, expectedAudio, audio)
    }

    // Keep moov before mdat without reopening the SAF writer for faststart.
    private fun moovReserve(duration: Double?): Int {
        val estimate = duration?.takeIf { it.isFinite() && it > 0 }?.let { it * 4096 + 524288 } ?: 8388608.0
        val bytes = estimate.coerceIn(1048576.0, 33554432.0).toLong()
        return (((bytes + 65535) / 65536) * 65536).toInt()
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
