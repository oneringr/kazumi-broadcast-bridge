package io.github.kazumibridge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ProtocolTest {
    @Test fun addressDiagnosticsKeepSecretsPrivateAndExposeLinkTiming() {
        val diagnostic = SourceProbe.addressDiagnostic("https://example.test/private-secret.m3u8?token=signature-secret&expires=1800000060", 1800000000000)
        assertFalse(diagnostic.contains("private-secret"))
        assertFalse(diagnostic.contains("signature-secret"))
        assertFalse(diagnostic.contains("1800000060"))
        assertTrue(diagnostic.contains("expires, 60"))
        val source = "https://example.test/private-secret.m3u8?token=signature-secret"
        val native = SourceProbe.redactLocations("Opening '$source' then https://cdn.test/hidden-segment.ts?token=segment-secret failed", source)
        assertTrue(native.contains("来源入口 host=example.test"))
        assertTrue(native.contains("媒体 host=cdn.test; suffix=ts"))
        assertTrue(SourceProbe.redactLocations("$source: Server returned 403", source).contains("来源入口 host=example.test"))
        for (secret in listOf("private-secret", "signature-secret", "hidden-segment", "segment-secret", "token=")) assertFalse(native.contains(secret))
    }
    @Test fun completeVideoRejectsTruncatedTracksDespiteLongChapterMetadata() {
        assertThrows(IllegalArgumentException::class.java) { MediaPolicy.completedDuration(1424.967, 94.8, 1424.967, 94.736939) }
        assertThrows(IllegalArgumentException::class.java) { MediaPolicy.completedDuration(1424.967, 1400.0) }
        assertThrows(IllegalArgumentException::class.java) { MediaPolicy.completedDuration(120.0, 120.0, 120.0, 80.0) }
        assertEquals(1424.967, MediaPolicy.completedDuration(1424.967, 1424.967, 1424.967, 1424.9), 0.001)
        assertEquals(94.8, MediaPolicy.completedDuration(94.8, 94.8, 94.736939, 94.736939), 0.001)
        assertEquals(120.0, MediaPolicy.completedDuration(null, 120.0), 0.001)
    }

    private fun cast(url: String): HttpRequest {
        val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><CurrentURI>${Soap.escape(url)}</CurrentURI><CurrentURIMetaData/></u:SetAVTransportURI></s:Body></s:Envelope>".toByteArray()
        return HttpRequest("POST", "/AVTransport/control", mapOf("soapaction" to "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""), body)
    }

    @Test fun soapPreservesSignedUrlAndReplacesBothUriAndMetadata() {
        val original = "https://example.test/play?token=a%2Bb&expires=123"
        val request = cast(original)
        val command = Soap.parse(request)!!
        assertEquals(original, command.source)
        val changed = Soap.replaceMedia(request, command, "http://192.168.1.2:1234/media/abc/video.mp4")
        val parsed = Soap.parse(changed)!!
        assertEquals("http://192.168.1.2:1234/media/abc/video.mp4", parsed.source)
        val metadata = parsed.document.getElementsByTagName("CurrentURIMetaData").item(0).textContent
        assertTrue(metadata.contains("http-get:*:video/mp4:*"))
        assertFalse(metadata.contains(original))
        val wire = ByteArrayOutputStream().also(changed::writeTo).toByteArray()
        val restored = HttpWire.readRequest(ByteArrayInputStream(wire))!!
        assertArrayEquals(changed.body, restored.body)
    }

    @Test fun chunkedSoapHandlesSplitReadsAndExtensions() {
        val request = cast("https://example.test/video.m3u8")
        val output = ByteArrayOutputStream()
        output.write("POST /control HTTP/1.1\r\nSOAPAction: urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
        request.body.toList().chunked(7).forEach { chunk ->
            output.write("${chunk.size.toString(16)};test=1\r\n".toByteArray()); output.write(chunk.toByteArray()); output.write("\r\n".toByteArray())
        }
        output.write("0\r\nTrailer: done\r\n\r\n".toByteArray())
        val parsed = HttpWire.readRequest(ByteArrayInputStream(output.toByteArray()))!!
        assertEquals("https://example.test/video.m3u8", Soap.parse(parsed)!!.source)
    }

    @Test fun rangesCoverReceiverHeadTailAndSeekRequests() {
        assertEquals(RangeResult.Partial(ByteRange(0, 0)), ByteRanges.parse("bytes=0-0", 100))
        assertEquals(RangeResult.Partial(ByteRange(90, 99)), ByteRanges.parse("bytes=-10", 100))
        assertEquals(RangeResult.Partial(ByteRange(10, 99)), ByteRanges.parse("bytes=10-", 100))
        assertEquals(RangeResult.Partial(ByteRange(0, 99)), ByteRanges.parse("bytes=0-999", 100))
        assertEquals(RangeResult.Invalid, ByteRanges.parse("bytes=100-", 100))
        assertEquals(RangeResult.Invalid, ByteRanges.parse("bytes=0-1,3-4", 100))
        assertEquals(RangeResult.Invalid, ByteRanges.parse("bytes=-0", 100))
        assertEquals(RangeResult.Invalid, ByteRanges.parse("bytes=9223372036854775808-", 100))
    }

    @Test fun lateConversionCannotRestartStoppedOrReplacedVideo() {
        val gate = CastGate()
        val first = gate.prepare()
        assertFalse(gate.play())
        gate.stop()
        assertFalse(gate.ready(first))
        val second = gate.prepare()
        gate.play()
        assertFalse(gate.ready(first))
        assertTrue(gate.ready(second))
        assertTrue(gate.pause())
        val third = gate.prepare()
        gate.play(); gate.pause()
        assertFalse(gate.ready(third))
        assertEquals(CastGate.Phase.READY, gate.phase)
    }

    @Test fun detectsMediaBytesRatherThanUrlSuffix() {
        val mp4 = byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray() + ByteArray(8)
        val hls = "#EXTM3U\n#EXTINF:5\nvideo.mp4\n".toByteArray()
        val compatible = ProbeResult("mov,mp4,m4a,3gp,3g2,mj2", "h264", "aac", "yuv420p", 2, 10.0)
        assertTrue(MediaPolicy.plan(compatible, mp4, false).passthrough)
        assertFalse(MediaPolicy.plan(compatible.copy(format = "hls"), hls, false).passthrough)
        assertFalse(MediaPolicy.plan(compatible.copy(videoCodec = "vp9"), mp4, false).copyVideo)
        assertFalse(MediaPolicy.plan(compatible.copy(audioCodec = "opus"), mp4, false).copyAudio)
        assertFalse(MediaPolicy.plan(compatible.copy(videoCodec = "hevc"), mp4, true).copyVideo)
        assertFalse(MediaPolicy.plan(compatible.copy(pixelFormat = "yuv420p10le"), mp4, false).copyVideo)
    }

    @Test fun fragmentIsReadyOnlyAfterCompleteMediaDataBox() {
        fun box(type: String, body: ByteArray = byteArrayOf()): ByteArray {
            val size = body.size + 8
            return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + type.toByteArray() + body
        }
        val file = File.createTempFile("fragment-", ".mp4")
        try {
            val complete = box("ftyp", "isom".toByteArray()) + box("moov") + box("moof") + box("mdat", ByteArray(100))
            file.writeBytes(complete.copyOf(complete.size - 1)); assertFalse(MediaPolicy.fragmentedReady(file))
            file.writeBytes(complete); assertTrue(MediaPolicy.fragmentedReady(file))
        } finally { file.delete() }
    }

    @Test fun rejectsExternalEntitiesAndAmbiguousHttpFraming() {
        val unsafe = cast("https://example.test/video").copy(body = "<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><x>&y;</x>".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { Soap.parse(unsafe) }
        assertThrows(IllegalArgumentException::class.java) {
            HttpWire.readRequest(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()))
        }
    }

    @Test fun relayPreludeAuthenticatesAndRetainsDestination() {
        val token = ByteArray(32) { it.toByte() }
        val packet = "KBB2".toByteArray() + token + byteArrayOf(4, 0x1f, 0x40) + ByteArray(4) + byteArrayOf(192.toByte(), 168.toByte(), 1, 10) + ByteArray(12) + byteArrayOf(0, 0)
        assertEquals(RelayPrelude.SIZE, packet.size)
        val target = RelayPrelude.read(ByteArrayInputStream(packet), token)
        assertEquals("192.168.1.10", target.address.hostAddress)
        assertEquals(8000, target.port)
        assertThrows(IllegalArgumentException::class.java) { RelayPrelude.read(ByteArrayInputStream(packet), ByteArray(32)) }
        val headers = "User-Agent: fixture-agent\r\nReferer: https://fixture.invalid/\r\nCookie: fixture=yes\r\n".toByteArray()
        val withHeaders = packet.copyOf().apply { this[59] = (headers.size shr 8).toByte(); this[60] = headers.size.toByte() } + headers
        assertEquals(mapOf("User-Agent" to "fixture-agent", "Referer" to "https://fixture.invalid/", "Cookie" to "fixture=yes"),
            RelayPrelude.read(ByteArrayInputStream(withHeaders), token).sourceHeaders)
        val playerMetadata = headers + "X-KBB-Player-Source: https://fixture.invalid/video.m3u8?expires=123&token=a%2Bb\r\n".toByteArray()
        val withPlayer = packet.copyOf().apply { this[59] = (playerMetadata.size shr 8).toByte(); this[60] = playerMetadata.size.toByte() } + playerMetadata
        val playerTarget = RelayPrelude.read(ByteArrayInputStream(withPlayer), token)
        assertEquals("https://fixture.invalid/video.m3u8?expires=123&token=a%2Bb", playerTarget.playerSource)
        assertFalse(playerTarget.sourceHeaders.containsKey("X-KBB-Player-Source"))
        val unsafe = "Cookie: a\nInjected: value\r\n".toByteArray()
        val injected = packet.copyOf().apply { this[60] = unsafe.size.toByte() } + unsafe
        assertThrows(IllegalArgumentException::class.java) { RelayPrelude.read(ByteArrayInputStream(injected), token) }
    }
}
