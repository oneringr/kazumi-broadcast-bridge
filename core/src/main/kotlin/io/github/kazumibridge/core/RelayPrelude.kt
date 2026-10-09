package io.github.kazumibridge.core

import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

data class RelayTarget(val address: InetAddress, val port: Int, val sourceHeaders: Map<String, String> = emptyMap(), val playerSource: String? = null)

object RelayPrelude {
    const val SIZE = 61
    fun read(input: InputStream, token: ByteArray): RelayTarget {
        require(token.size == 32)
        val packet = HttpWire.exact(input, SIZE)
        require(packet.copyOfRange(0, 4).contentEquals("KBB2".toByteArray(Charsets.US_ASCII))) { "Invalid relay prelude" }
        require(MessageDigest.isEqual(packet.copyOfRange(4, 36), token)) { "Invalid relay token" }
        val port = ((packet[37].toInt() and 255) shl 8) or (packet[38].toInt() and 255)
        require(port in 1..65535)
        val address = when (packet[36].toInt()) {
            4 -> InetAddress.getByAddress(packet.copyOfRange(43, 47))
            6 -> {
                val scope = (39..42).fold(0) { value, i -> (value shl 8) or (packet[i].toInt() and 255) }
                Inet6Address.getByAddress(null, packet.copyOfRange(43, 59), scope)
            }
            else -> throw IllegalArgumentException("Unsupported address family")
        }
        require(address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 254) == 252)) { "Relay target is outside the local network" }
        val headerLength = ((packet[59].toInt() and 255) shl 8) or (packet[60].toInt() and 255)
        require(headerLength <= 8192) { "Source headers are too large" }
        var playerSource: String? = null
        val headers = buildMap {
            val text = HttpWire.exact(input, headerLength).toString(Charsets.UTF_8)
            for (line in text.split("\r\n")) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                require(colon > 0 && line.none { it.code < 32 || it.code == 127 }) { "Invalid source header" }
                if (line.substring(0, colon).equals("X-KBB-Player-Source", true)) {
                    val candidate = line.substring(colon + 1).trim()
                    val uri = runCatching { java.net.URI(candidate) }.getOrNull()
                    if (uri?.scheme in setOf("http", "https") && uri?.host != null && uri.userInfo == null) playerSource = candidate
                    continue
                }
                val name = when (line.substring(0, colon).lowercase(java.util.Locale.ROOT)) {
                    "user-agent" -> "User-Agent"; "referer" -> "Referer"; "cookie" -> "Cookie"; else -> continue
                }
                val value = line.substring(colon + 1).trim()
                if (value.isNotEmpty()) put(name, value)
            }
        }
        return RelayTarget(address, port, headers, playerSource)
    }
}
