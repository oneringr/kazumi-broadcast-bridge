package io.github.kazumibridge.runtime

import io.github.kazumibridge.core.HttpResponse
import io.github.kazumibridge.core.HttpWire
import io.github.kazumibridge.core.RelayPrelude
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class RelayServer(private val token: ByteArray, private val controller: RendererController) : AutoCloseable {
    private val servers = mutableListOf<ServerSocket>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "KazumiBridge-control") }
    private val slots = Semaphore(32)
    @Volatile private var closed = false
    val port: Int
    private val tokenText = token.joinToString("") { "%02x".format(it.toInt() and 255) }

    init {
        val ipv4 = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        servers.add(ipv4)
        port = ipv4.localPort
        runCatching { servers.add(ServerSocket(port, 32, InetAddress.getByName("::1"))) }
        servers.forEach { server -> workers.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { socket.close(); continue }
                clients.add(socket)
                workers.execute {
                    try { socket.use(::handle) } catch (_: Exception) { /* A failed/closed Dart socket is isolated to its connection. */ }
                    finally { clients.remove(socket); slots.release() }
                }
            }
        } }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15000
        val input = socket.getInputStream().buffered()
        input.mark(16)
        val prefix = HttpWire.exact(input, 4)
        input.reset()
        if (!prefix.contentEquals("KBB2".toByteArray(Charsets.US_ASCII))) {
            val request = HttpWire.readRequest(input, socket.getOutputStream()) ?: return
            if (request.path != "/hook/$tokenText" || request.method != "POST") { HttpResponse(403, "Forbidden").writeTo(socket.getOutputStream()); return }
            val data = JSONObject(request.body.toString(Charsets.UTF_8))
            BridgeState.update { it.copy(hookSeenAt = System.currentTimeMillis(), nativeStatus = data.optInt("nativeStatus", -1), redirects = data.optLong("redirects")) }
            HttpResponse(204, "No Content").writeTo(socket.getOutputStream())
            return
        }
        val target = RelayPrelude.read(input, token)
        input.mark(16)
        val start = ByteArray(5)
        var read = 0
        while (read < start.size) { val count = input.read(start, read, start.size - read); if (count < 0) break; read += count }
        input.reset()
        if (String(start, 0, read, Charsets.US_ASCII) == "POST ") {
            val request = HttpWire.readRequest(input, socket.getOutputStream()) ?: return
            try {
                val reply = controller.handle(target, request)
                if (reply != null) { reply.writeTo(socket.getOutputStream()); return }
            } catch (error: Exception) {
                BridgeState.error("投屏请求处理失败：${error.javaClass.simpleName}。请重试或检查接收设备连接。")
                io.github.kazumibridge.core.Soap.fault(501, "Bridge operation failed").writeTo(socket.getOutputStream())
                return
            }
            Socket().use { outbound ->
                outbound.connect(InetSocketAddress(target.address, target.port), 3000)
                request.writeTo(outbound.getOutputStream())
                tunnel(socket, outbound, input)
            }
        } else Socket().use { outbound ->
            outbound.connect(InetSocketAddress(target.address, target.port), 3000)
            tunnel(socket, outbound, input)
        }
    }

    private fun tunnel(inbound: Socket, outbound: Socket, input: java.io.InputStream) {
        clients.add(outbound)
        inbound.soTimeout = 0
        outbound.soTimeout = 0
        val copy = workers.submit {
            try { input.copyTo(outbound.getOutputStream()); outbound.shutdownOutput() }
            catch (_: Exception) { runCatching { outbound.close() } }
        }
        try { outbound.getInputStream().copyTo(inbound.getOutputStream()); inbound.getOutputStream().flush() }
        finally { copy.cancel(true); clients.remove(outbound); runCatching { outbound.close() } }
    }

    override fun close() {
        closed = true
        servers.forEach { it.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
