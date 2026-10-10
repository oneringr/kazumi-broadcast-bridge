package io.github.kazumibridge.core

import org.junit.Assert.*
import org.junit.Test

class DurationValidationTest {
    @Test fun shortVideoCannotConcealMissingOrInvalidRequiredAudio() {
        for (audio in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                MediaPolicy.completedDuration(120.0, 20.0, 120.0, audio)
            }
            assertFalse("Invalid audio cannot offer force casting", failure is DurationMismatch)
            assertTrue(failure.message.orEmpty().contains("音频"))
        }
    }

    @Test fun audioRemainsRequiredWhenItsSourceDurationIsUnknown() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MediaPolicy.completedDuration(120.0, 20.0, actualAudio = null, requiresAudio = true)
        }
        assertFalse(failure is DurationMismatch)
        assertTrue(failure.message.orEmpty().contains("音频"))
        assertEquals(120.0, MediaPolicy.completedDuration(null, 120.0, actualAudio = 119.9, requiresAudio = true), 0.001)
    }

    @Test fun missingOrNonFiniteVideoNeverProducesRecoverableMismatch() {
        for (video in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                MediaPolicy.completedDuration(120.0, video, 120.0, 120.0)
            }
            assertFalse(failure is DurationMismatch)
            assertTrue(failure.message.orEmpty().contains("视频"))
        }
    }

    @Test fun shortValidTracksProduceTypedMismatchWithActualCompletedDuration() {
        val failure = assertThrows(DurationMismatch::class.java) {
            MediaPolicy.completedDuration(120.0, 20.0, 120.0, 21.0)
        }
        assertEquals(21.0, failure.completedDuration, 0.001)
        assertTrue(failure.message.orEmpty().contains("视频转换提前结束"))
        assertTrue(failure.message.orEmpty().contains("音频转换提前结束"))
    }

    @Test fun shortAudioIsRecoverableOnlyWhenItHasAValidDuration() {
        val failure = assertThrows(DurationMismatch::class.java) {
            MediaPolicy.completedDuration(120.0, 120.0, 120.0, 80.0)
        }
        assertEquals(120.0, failure.completedDuration, 0.001)
        assertTrue(failure.message.orEmpty().contains("音频转换提前结束"))
        assertFalse(failure.message.orEmpty().contains("视频转换提前结束"))
    }

    @Test fun toleranceAcceptsBoundaryButRejectsLargerShortfall() {
        assertEquals(118.0, MediaPolicy.completedDuration(120.0, 118.0, 120.0, 118.0), 0.001)
        assertEquals(4995.0, MediaPolicy.completedDuration(5000.0, 4995.0), 0.001)
        assertThrows(DurationMismatch::class.java) { MediaPolicy.completedDuration(120.0, 117.99) }
        assertThrows(DurationMismatch::class.java) { MediaPolicy.completedDuration(5000.0, 4994.99) }
    }

    @Test fun successfulUnknownSourceDurationsKeepActualMaximumContract() {
        for (expected in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(125.0, MediaPolicy.completedDuration(expected, 120.0, expected, 125.0), 0.001)
        }
        assertEquals(120.0, MediaPolicy.completedDuration(null, 120.0), 0.001)
        assertEquals(125.0, MediaPolicy.completedDuration(null, 120.0, actualAudio = 125.0), 0.001)
    }
}
