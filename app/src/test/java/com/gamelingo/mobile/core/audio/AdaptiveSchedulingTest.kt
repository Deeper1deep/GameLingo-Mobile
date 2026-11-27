package com.gamelingo.mobile.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the pacing arithmetic only. Real effect on latency, battery and quality is unmeasured. */
class AdaptiveSchedulingTest {
    private fun c(smart: Boolean = true, audio: Long = 0, ocr: Long = 1_000, queue: Int = 0, now: Long = 2_000) =
        AdaptiveScheduling.captureIntervalMs(600, smart, now, audio, ocr, queue)

    @Test fun `non smart profiles are never changed`() {
        assertEquals(600L, c(smart = false, queue = 10, audio = 1_900, ocr = 0))
    }

    @Test fun `idle smart auto keeps the configured interval`() {
        assertEquals(600L, c())
    }

    @Test fun `a backed up translation queue slows capture by 1_5x`() {
        assertEquals(900L, c(queue = 3))
        assertEquals(600L, c(queue = 2))
    }

    @Test fun `active speech with a quiet screen doubles the interval, never more`() {
        assertEquals(1_200L, c(audio = 19_000, ocr = 5_000, now = 20_000))
        assertEquals(1_200L, c(audio = 19_000, ocr = 5_000, now = 20_000, queue = 9))
    }

    @Test fun `speech alone does not slow capture while the screen is active`() {
        assertEquals(600L, c(audio = 19_000, ocr = 18_000, now = 20_000))
    }

    @Test fun `old speech is not active`() {
        assertEquals(600L, c(audio = 1_000, ocr = 0, now = 20_000))
    }

    @Test fun `the interval never gets shorter than configured`() {
        listOf(0, 1, 5).forEach { q -> assert(c(queue = q) >= 600L) }
    }
}
