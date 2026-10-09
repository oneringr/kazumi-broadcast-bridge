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
    private class Renderer(val target: RelayTarget, val path: String) {
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

    fun handle(target: RelayTarget, request: HttpRequest): HttpResponse? {
        val command = Soap.parse(request) ?: return null
        val key = "${target.address.hostAddress}:${target.port}${request.path}"
        val renderer = renderers.computeIfAbsent(key) { Renderer(target, request.path) }
        if (command.action == "SetAVTransportURI") replacementLock.lock()
        try {
        renderer.lock.withLock {
            if (command.action == "Play" && renderer.gate.phase in setOf(CastGate.Phase.EMPTY, CastGate.Phase.STOPPED, CastGate.Phase.FAILED)) {
                var remaining = TimeUnit.SECONDS.toNanos(3)
                while (remaining > 0 && renderer.gate.phase in setOf(CastGate.Phase.EMPTY, CastGate.Phase.STOPPED, CastGate.Phase.FAILED)) {
                    remaining = renderer.arrived.awaitNanos(remaining)
                }
            }
            when (command.action) {
                "SetAVTransportURI" -> {
                    android.util.Log.i("KazumiBridgeDiagnostics", "cast matches player=${target.playerSource == command.source}; cast ${SourceProbe.addressDiagnostic(command.source!!)}; player ${target.playerSource?.let { SourceProbe.addressDiagnostic(it) }}")
                    active?.takeIf { it !== renderer }?.let { old -> old.lock.withLock { old.gate.stop(); old.job?.cancel(); old.job?.let(media::remove) } }
                    renderer.job?.let { old -> old.cancel(); media.remove(old) }
                    active = renderer
                    onActive(true)
                    val generation = renderer.gate.prepare()
                    renderer.setRequest = request
                    renderer.playRequest = null
                    BridgeState.update { it.copy(receiver = target.address.hostAddress.orEmpty(), error = null) }
                    renderer.job = engine.prepare(command.source!!, Settings.read().withSourceHeaders(target.sourceHeaders),
                        current = { renderer.lock.withLock { renderer.gate.current(generation) } },
                        ready = { job -> renderer.lock.withLock {
                            if (job.canceled || !renderer.gate.current(generation)) return@withLock
                            val url = media.add(job, target)
                            val replacement = Soap.replaceMedia(request, command, url, duration = job.duration?.let(::durationText))
                            val reply = forward(target, replacement)
                            check(reply.status in 200..299) { "接收设备拒绝 MP4 地址（HTTP ${reply.status}）。" }
                            val play = renderer.gate.ready(generation)
                            BridgeState.update { it.copy(stage = if (job.direct) "MP4 直接投屏" else "MP4 投屏中", detail = if (job.complete) "已将 MP4 地址交给接收设备" else "正在生成并发送 MP4 分片") }
                            BridgeState.event("接收设备已接受 MP4 地址")
                            if (play) renderer.playRequest?.let { queued ->
                                val playReply = forward(target, queued)
                                check(playReply.status in 200..299) { "接收设备播放失败（HTTP ${playReply.status}）。" }
                                BridgeState.event("已按顺序发送播放命令")
                            }
                        } },
                        failed = { message -> renderer.lock.withLock {
                            if (renderer.gate.current(generation)) {
                                renderer.gate.fail(generation); renderer.job?.cancel(); renderer.job?.let(media::remove); onActive(false)
                                BridgeState.update { it.copy(memoryCacheBytes = 0) }
                                BridgeState.error(message)
                            }
                        } })
                    renderer.arrived.signalAll()
                    BridgeState.event("截获投屏请求；等待视频准备完成")
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
                    if (active === renderer) onActive(false)
                    BridgeState.update { it.copy(stage = "已停止投屏", progress = null, memoryCacheBytes = 0) }
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

    private fun durationText(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    override fun close() {
        renderers.values.forEach { renderer -> renderer.lock.withLock {
            val ready = renderer.gate.phase == CastGate.Phase.READY
            renderer.gate.stop()
            renderer.job?.cancel()
            if (ready) renderer.setRequest?.let { template -> runCatching {
                val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:Stop></s:Body></s:Envelope>".toByteArray()
                forward(renderer.target, template.copy(headers = (template.headers - "content-length") + ("soapaction" to "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\""), body = body))
            } }
        } }
        renderers.clear()
        onActive(false)
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
