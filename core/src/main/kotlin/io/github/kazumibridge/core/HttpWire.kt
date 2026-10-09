package io.github.kazumibridge.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    fun writeTo(output: OutputStream) = HttpWire.write(output, "$method $path HTTP/1.1", headers, body)
}

data class HttpResponse(val status: Int, val reason: String, val headers: Map<String, String> = emptyMap(), val body: ByteArray = byteArrayOf()) {
    fun writeTo(output: OutputStream, head: Boolean = false) =
        HttpWire.write(output, "HTTP/1.1 $status $reason", headers, body, head)
}

object HttpWire {
    private const val MAX_HEADERS = 65536
    private const val MAX_BODY = 2 * 1024 * 1024

    fun readRequest(input: InputStream, interim: OutputStream? = null): HttpRequest? {
        val first = line(input, 8192) ?: return null
        val parts = first.split(' ', limit = 3)
        require(parts.size == 3 && parts[2] in setOf("HTTP/1.0", "HTTP/1.1")) { "Invalid HTTP request" }
        require(parts[0].all { it in 'A'..'Z' }) { "Invalid HTTP method" }
        val headers = headers(input)
        if (headers["expect"]?.equals("100-continue", true) == true) {
            interim?.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            interim?.flush()
        }
        return HttpRequest(parts[0], parts[1], headers, body(input, headers))
    }

    fun readResponse(input: InputStream): HttpResponse {
        val first = line(input, 8192) ?: throw EOFException("Receiver closed the connection")
        val parts = first.split(' ', limit = 3)
        require(parts.size >= 2 && parts[0].startsWith("HTTP/1.")) { "Invalid receiver response" }
        val status = parts[1].toInt()
        val headers = headers(input)
        if (status in 100..199) return readResponse(input)
        return HttpResponse(status, parts.getOrElse(2) { "" }, headers,
            if (status == 204 || status == 304) byteArrayOf() else body(input, headers, untilEof = true))
    }

    private fun headers(input: InputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var bytes = 0
        while (true) {
            val text = line(input, 8192) ?: throw EOFException("Truncated HTTP headers")
            bytes += text.length + 2
            require(bytes <= MAX_HEADERS) { "HTTP headers too large" }
            if (text.isEmpty()) break
            val split = text.indexOf(':')
            require(split > 0 && !text.first().isWhitespace()) { "Invalid HTTP header" }
            val name = text.substring(0, split).lowercase(Locale.ROOT)
            val value = text.substring(split + 1).trim()
            require(!result.containsKey(name) || name !in setOf("content-length", "transfer-encoding", "host")) { "Duplicate framing header" }
            result[name] = if (result.containsKey(name)) "${result[name]}, $value" else value
        }
        require(!(result.containsKey("transfer-encoding") && result.containsKey("content-length"))) { "Ambiguous HTTP framing" }
        return result
    }

    private fun body(input: InputStream, headers: Map<String, String>, untilEof: Boolean = false): ByteArray {
        val encoding = headers["transfer-encoding"]
        if (encoding != null) {
            require(encoding.equals("chunked", true)) { "Unsupported HTTP transfer encoding" }
            val result = ByteArrayOutputStream()
            while (true) {
                val sizeText = line(input, 128) ?: throw EOFException("Missing chunk size")
                val size = sizeText.substringBefore(';').trim().toLong(16)
                require(size in 0..(MAX_BODY - result.size()).toLong()) { "HTTP body too large" }
                if (size == 0L) {
                    headers(input)
                    return result.toByteArray()
                }
                result.write(exact(input, size.toInt()))
                require(line(input, 2) == "") { "Missing chunk separator" }
            }
        }
        headers["content-length"]?.let {
            val count = it.toLong()
            require(count in 0..MAX_BODY.toLong()) { "HTTP body too large" }
            return exact(input, count.toInt())
        }
        if (!untilEof) return byteArrayOf()
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return result.toByteArray()
            require(result.size() + count <= MAX_BODY) { "HTTP body too large" }
            result.write(buffer, 0, count)
        }
    }

    fun line(input: InputStream, limit: Int): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (output.size() == 0) return null
                throw EOFException("Truncated HTTP line")
            }
            if (next == 10) {
                val bytes = output.toByteArray()
                require(bytes.isNotEmpty() && bytes.last() == 13.toByte()) { "HTTP lines require CRLF" }
                return String(bytes, 0, bytes.size - 1, StandardCharsets.ISO_8859_1)
            }
            require(output.size() < limit + 1) { "HTTP line too long" }
            output.write(next)
        }
    }

    fun exact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("Truncated HTTP body")
            offset += read
        }
        return result
    }

    fun write(output: OutputStream, first: String, headers: Map<String, String>, body: ByteArray, head: Boolean = false) {
        val text = buildString {
            append(first).append("\r\n")
            for ((name, value) in headers) {
                if (name.lowercase(Locale.ROOT) in setOf("content-length", "connection", "transfer-encoding", "expect")) continue
                require(!name.contains('\r') && !name.contains('\n') && !value.contains('\r') && !value.contains('\n'))
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(headers["content-length"] ?: body.size).append("\r\nConnection: close\r\n\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        if (!head) output.write(body)
        output.flush()
    }
}
