package io.github.kazumibridge.runtime

import io.github.kazumibridge.core.CastGate
import io.github.kazumibridge.core.HttpRequest
import io.github.kazumibridge.core.HttpResponse
import io.github.kazumibridge.core.HttpWire
import io.github.kazumibridge.core.RelayTarget
import io.github.kazumibridge.core.Soap
import io.github.kazumibridge.core.SourceProbe
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RendererController(private val engine: MediaEngine, private val media: MediaServer, private val onActive: (Boolean) -> Unit) : AutoCloseable {
    private class Renderer(var target: RelayTarget, val path: String) {
        val lock = ReentrantLock()
        val arrived = lock.newCondition()
        val gate = CastGate()
        var job: MediaJob? = null
        var setRequest: HttpRequest? = null
        var playRequest: HttpRequest? = null
    }
    private val renderers = ConcurrentHashMap<String, Renderer>()
    private val replacementLock = ReentrantLock()
    @Volatile private var active: Renderer? = null
    @Volatile private var closed = false

    fun handle(target: RelayTarget, request: HttpRequest): HttpResponse? {
        val command = Soap.parse(request) ?: return null
        val key = "${target.address.hostAddress}:${target.port}${request.path}"
        val renderer = renderers.computeIfAbsent(key) { Renderer(target, request.path) }
        if (command.action == "SetAVTransportURI") replacementLock.lock()
        try {
        renderer.lock.withLock {
            if (closed) return Soap.fault(701, "Bridge is stopped")
            if (command.action == "Play" && renderer.gate.phase in setOf(CastGate.Phase.EMPTY, CastGate.Phase.STOPPED, CastGate.Phase.FAILED)) {
                var remaining = TimeUnit.SECONDS.toNanos(3)
                while (remaining > 0 && renderer.gate.phase in setOf(CastGate.Phase.EMPTY, CastGate.Phase.STOPPED, CastGate.Phase.FAILED)) {
                    remaining = renderer.arrived.awaitNanos(remaining)
                }
            }
            when (command.action) {
                "SetAVTransportURI" -> {
                    android.util.Log.i("KazumiBridgeDiagnostics", "cast matches player=${target.playerSource == command.source}; cast ${SourceProbe.addressDiagnostic(command.source!!)}; player ${target.playerSource?.let { SourceProbe.addressDiagnostic(it) }}")
                    begin(renderer, target, request, command, autoPlay = false)
                    return Soap.success(command.action, command.namespace)
                }
                "Play" -> if (renderer.gate.phase != CastGate.Phase.EMPTY) {
                    if (renderer.gate.phase in setOf(CastGate.Phase.FAILED, CastGate.Phase.STOPPED)) return Soap.fault(701, "Media is not ready")
                    renderer.playRequest = request
                    if (!renderer.gate.play()) return Soap.success("Play", command.namespace)
                }
                "Pause" -> if (renderer.gate.phase != CastGate.Phase.EMPTY && !renderer.gate.pause()) return Soap.success("Pause", command.namespace)
                "Stop" -> {
                    val wasPending = renderer.gate.phase == CastGate.Phase.PREPARING
                    renderer.gate.stop()
                    renderer.job?.cancel()
                    renderer.job?.let(media::remove)
                    if (active === renderer) {
                        onActive(false)
                        BridgeState.update { it.copy(stage = "已停止投屏", progress = null, memoryCacheBytes = 0, canRetry = false, canForce = false, taskId = "") }
                    }
                    if (wasPending) return Soap.success("Stop", command.namespace)
                }
                "Seek" -> if (renderer.gate.phase == CastGate.Phase.PREPARING || renderer.job?.let { it.settings.mode == io.github.kazumibridge.core.CastMode.STREAM && !it.complete } == true) {
                    return Soap.fault(710, "Seek is unavailable while preparing fragmented MP4")
                }
                "GetTransportInfo" -> if (renderer.gate.phase == CastGate.Phase.PREPARING) {
                    return Soap.success(command.action, command.namespace, "<CurrentTransportState>TRANSITIONING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>")
                }
            }
            return forward(target, request)
        }
        } finally { if (command.action == "SetAVTransportURI") replacementLock.unlock() }
    }

    /** A manual retry prepares a new task using current settings and the original source headers. */
    fun retryLast(expectedTaskId: String? = null): Boolean = replacementLock.withLock {
        if (closed) return false
        val renderer = active ?: return false
        renderer.lock.withLock {
            if (expectedTaskId != null && renderer.job?.id != expectedTaskId) return false
            if (renderer.gate.phase != CastGate.Phase.FAILED) return false
            val request = renderer.setRequest ?: return false
            val command = Soap.parse(request) ?: return false
            begin(renderer, renderer.target, request, command, autoPlay = true)
            BridgeState.event("手动重试：重新准备视频并开始播放")
            true
        }
    }

    /** This bypasses only a finalized output's source-duration comparison. */
    fun forceLast(expectedTaskId: String? = null): Boolean = replacementLock.withLock {
        if (closed) return false
        val renderer = active ?: return false
        renderer.lock.withLock {
            val job = renderer.job ?: return false
            if (expectedTaskId != null && job.id != expectedTaskId) return false
            val mismatch = job.durationMismatch ?: return false
            if (renderer.gate.phase != CastGate.Phase.FAILED || job.canceled || !job.complete || job.size <= 0 || job.memoryError != null) return false
            val request = renderer.setRequest ?: return false
            val command = Soap.parse(request) ?: return false
            val generation = renderer.gate.prepare()
            renderer.playRequest = actionRequest(request, "Play")
            renderer.gate.play()
            job.duration = mismatch.completedDuration
            job.durationMismatch = null
            job.error = null
            onActive(true)
            BridgeState.update { it.copy(error = null, canRetry = false, canForce = false, detail = "使用已生成 MP4 的实际时长投屏，视频结尾可能不完整。") }
            try {
                publishPrepared(renderer, request, command, generation, job)
                BridgeState.update { it.copy(detail = "已强制投屏；只忽略与来源预估时长的差异，结尾可能不完整。", progress = 1.0) }
                BridgeState.event("已强制投屏：使用生成文件的实际时长")
            } catch (error: Exception) {
                fail(renderer, generation, job, "投屏请求发送失败：${error.javaClass.simpleName}，请检查接收设备连接。")
            }
            true
        }
    }

    /** Called with replacementLock and this renderer's lock held. */
    private fun begin(renderer: Renderer, target: RelayTarget, request: HttpRequest, command: io.github.kazumibridge.core.CastCommand, autoPlay: Boolean) {
        active?.takeIf { it !== renderer }?.let { old -> old.lock.withLock {
            old.gate.stop(); old.job?.cancel(); old.job?.let(media::remove); old.arrived.signalAll()
        } }
        renderer.job?.let { old -> old.cancel(); media.remove(old) }
        renderer.target = target
        active = renderer
        val generation = renderer.gate.prepare()
        renderer.setRequest = request
        renderer.playRequest = if (autoPlay) actionRequest(request, "Play") else null
        if (autoPlay) renderer.gate.play()
        BridgeState.update { it.copy(receiver = target.address.hostAddress.orEmpty(), sourceUrl = command.source.orEmpty(), sourceHost = java.net.URI(command.source!!).host.orEmpty(),
            error = null, canRetry = false, canForce = false, memoryCacheBytes = 0, convertedBytes = 0, progress = null) }
        onActive(true)
        engine.prepare(command.source!!, Settings.read().withSourceHeaders(target.sourceHeaders),
            created = { job -> renderer.job = job; BridgeState.update { it.copy(taskId = job.id) } },
            current = { renderer.lock.withLock { !closed && active === renderer && renderer.gate.current(generation) } },
            ready = { job -> renderer.lock.withLock { publishPrepared(renderer, request, command, generation, job) } },
            failed = { job, message -> renderer.lock.withLock { fail(renderer, generation, job, message) } })
        renderer.arrived.signalAll()
        BridgeState.event("截获投屏请求；等待视频准备完成")
    }

    /** All callbacks recheck both ownership and generation while holding the renderer lock. */
    private fun publishPrepared(renderer: Renderer, request: HttpRequest, command: io.github.kazumibridge.core.CastCommand, generation: Long, job: MediaJob) {
        if (closed || active !== renderer || renderer.job !== job || job.canceled || !renderer.gate.current(generation)) return
        val url = media.add(job, renderer.target)
        val replacement = Soap.replaceMedia(request, command, url, duration = job.duration?.let(::durationText))
        val reply = forward(renderer.target, replacement)
        check(reply.status in 200..299) { "接收设备拒绝 MP4 地址（HTTP ${reply.status}）。" }
        val play = renderer.gate.ready(generation)
        BridgeState.update { it.copy(stage = if (job.direct) "MP4 直接投屏" else "MP4 投屏中",
            detail = if (job.complete) "已将 MP4 地址交给接收设备" else "正在生成并发送 MP4 分片", error = null, canRetry = false, canForce = false) }
        BridgeState.event("接收设备已接受 MP4 地址")
        if (play) renderer.playRequest?.let { queued ->
            val playReply = forward(renderer.target, queued)
            check(playReply.status in 200..299) { "接收设备播放失败（HTTP ${playReply.status}）。" }
            BridgeState.event("已按顺序发送播放命令")
        }
    }

    private fun fail(renderer: Renderer, generation: Long, job: MediaJob, message: String) {
        if (closed || active !== renderer || renderer.job !== job || !renderer.gate.current(generation)) return
        renderer.gate.fail(generation)
        val retained = job.durationMismatch != null && job.complete && !job.canceled && job.size > 0 && job.memoryError == null
        if (retained) media.detach(job) else { job.cancel(); media.remove(job) }
        onActive(false)
        BridgeState.update { it.copy(canRetry = true, canForce = retained, memoryCacheBytes = job.memoryBudget?.usedBytes ?: 0) }
        BridgeState.error(message)
    }

    private fun actionRequest(template: HttpRequest, action: String): HttpRequest {
        val parsed = requireNotNull(Soap.parse(template))
        val instance = parsed.document.getElementsByTagName("InstanceID").item(0)?.textContent?.trim().orEmpty().ifBlank { "0" }
        val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:$action xmlns:u=\"${Soap.escape(parsed.namespace)}\"><InstanceID>${Soap.escape(instance)}</InstanceID>${if (action == "Play") "<Speed>1</Speed>" else ""}</u:$action></s:Body></s:Envelope>".toByteArray()
        return template.copy(headers = (template.headers - setOf("content-length", "transfer-encoding", "expect")) +
            ("soapaction" to "\"${parsed.namespace}#$action\""), body = body)
    }

    private fun durationText(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    override fun close() = replacementLock.withLock {
        closed = true
        renderers.values.forEach { renderer -> renderer.lock.withLock {
            val ready = renderer.gate.phase == CastGate.Phase.READY
            renderer.gate.stop()
            renderer.job?.cancel()
            renderer.job?.let(media::remove)
            if (ready) renderer.setRequest?.let { template -> runCatching {
                forward(renderer.target, actionRequest(template, "Stop"))
            } }
        } }
        renderers.clear()
        active = null
        onActive(false)
        BridgeState.update { it.copy(canRetry = false, canForce = false, memoryCacheBytes = 0, taskId = "") }
    }

    companion object {
        fun forward(target: RelayTarget, request: HttpRequest): HttpResponse = Socket().use { socket ->
            socket.connect(InetSocketAddress(target.address, target.port), 3000)
            socket.soTimeout = 7000
            request.writeTo(socket.getOutputStream())
            HttpWire.readResponse(socket.getInputStream().buffered())
        }
    }
}
