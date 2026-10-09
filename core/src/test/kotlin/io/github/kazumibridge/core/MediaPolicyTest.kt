package io.github.kazumibridge.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class MediaPolicyTest {
    @Test fun reservedMoovKeepsCompleteOutputSeekableWithoutFaststartSecondPass() {
        val arguments = MediaPolicy.arguments("input", "output", ConversionPlan(false, true, true),
            CastMode.COMPLETE, moovReserveBytes = 65536)
        assertEquals("65536", arguments[arguments.indexOf("-moov_size") + 1])
        assertFalse("RAM output cannot reopen the same SAF URL for a faststart second pass", arguments.contains("+faststart"))
        assertFalse(arguments.contains("-movflags"))
        assertEquals(listOf("-f", "mp4", "output"), arguments.takeLast(3))
        assertThrows(IllegalArgumentException::class.java) {
            MediaPolicy.arguments("input", "output", ConversionPlan(false, true, true), CastMode.COMPLETE, moovReserveBytes = 0)
        }
    }

    @Test fun diskCompleteAndStreamKeepTheirExistingMuxingModes() {
        val complete = MediaPolicy.arguments("input", "output", ConversionPlan(false, true, true), CastMode.COMPLETE)
        assertEquals("+faststart", complete[complete.indexOf("-movflags") + 1])
        assertFalse(complete.contains("-moov_size"))
        val stream = MediaPolicy.arguments("input", "output", ConversionPlan(false, true, true),
            CastMode.STREAM, moovReserveBytes = 65536)
        assertFalse(stream.contains("-moov_size"))
        assertEquals("+frag_keyframe+empty_moov+default_base_moof", stream[stream.indexOf("-movflags") + 1])
    }

    @Test fun ramFragmentChecksOnlyHeadersAndWaitsForCompleteMediaData() {
        val header = box("ftyp", "isom".toByteArray()) + box("moov") + box("moof")
        val fragment = header + box("mdat", ByteArray(1024 * 1024))
        val budget = MemoryBudget(2L * 1024 * 1024)
        MemoryMediaBuffer(budget).use { memory ->
            memory.write(0, fragment)
            var requested = 0
            val read: (Long, ByteArray) -> Int = { offset, bytes -> requested += bytes.size; memory.read(offset, bytes) }
            assertFalse(MediaPolicy.fragmentedReady(memory.length - 1, read))
            assertTrue(MediaPolicy.fragmentedReady(memory.length, read))
            assertTrue("Readiness must never duplicate or inspect the media payload", requested <= 64)
        }
    }

    @Test fun fragmentedReadinessHandlesShortReadsAndExtendedBoxLengths() {
        val prefix = box("ftyp", "isom".toByteArray()) + box("moov") + box("moof")
        val extended = ByteBuffer.allocate(24).putInt(1).put("mdat".toByteArray()).putLong(24).putLong(0).array()
        val bytes = prefix + extended
        val read: (Long, ByteArray) -> Int = { offset, target ->
            val amount = minOf(3, target.size, (bytes.size - offset).coerceAtLeast(0).toInt())
            bytes.copyInto(target, 0, offset.toInt(), offset.toInt() + amount)
            amount
        }
        assertTrue(MediaPolicy.fragmentedReady(bytes.size.toLong(), read))
        assertFalse(MediaPolicy.fragmentedReady(bytes.size - 1L, read))
        assertFalse(MediaPolicy.fragmentedReady(prefix.size + 15L, read))
        assertFalse(MediaPolicy.fragmentedReady(bytes.size.toLong()) { _, _ -> 0 })
    }

    private fun box(type: String, data: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(data.size + 8).putInt(data.size + 8).put(type.toByteArray()).put(data).array()
}
