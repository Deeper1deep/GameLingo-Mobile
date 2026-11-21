package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeDetectorTest {

    // 64x64 grayscale digest, exactly what the capture service produces.
    private fun frame(fill: Int, size: Int = 4_096): ByteArray =
        ByteArray(size) { fill.toByte() }

    /** Paints [rows] of the 64-wide frame bright, like a subtitle appearing. */
    private fun paintRows(frame: ByteArray, rows: IntRange, value: Int = 0xFF) {
        for (row in rows) {
            for (x in 0 until 64) {
                frame[row * 64 + x] = value.toByte()
            }
        }
    }

    @Test
    fun `first frame counts as changed`() {
        assertTrue(FrameChangeDetector().hasChanged(frame(0)))
    }

    @Test
    fun `identical frame does not count as changed`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(32))
        assertFalse(detector.hasChanged(frame(32)))
    }

    @Test
    fun `empty frame never counts as changed`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(10))
        assertFalse(detector.hasChanged(ByteArray(0)))
    }

    @Test
    fun `a real content change is detected`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(0))
        val changed = frame(0)
        // Repaint the lower band (where subtitles sit) bright.
        paintRows(changed, 43 until 64)
        assertTrue(detector.hasChanged(changed))
    }

    @Test
    fun `text change at the top of the screen is detected`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(20))
        val changed = frame(20)
        paintRows(changed, 2 until 5) // band 0: rows 0-15
        assertTrue(detector.hasChanged(changed))
    }

    @Test
    fun `text change in the middle of the screen is detected`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(20))
        val changed = frame(20)
        paintRows(changed, 30 until 33) // band 1: rows 16-31
        assertTrue(detector.hasChanged(changed))
    }

    @Test
    fun `text change at the bottom of the screen is detected`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(20))
        val changed = frame(20)
        paintRows(changed, 58 until 61) // band 3: rows 48-63
        assertTrue(detector.hasChanged(changed))
    }

    @Test
    fun `a small local change the global histogram would miss is caught by the band`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(24))
        val changed = frame(24)
        // Two bright rows out of 64: only ~3% of the whole frame, but ~12% of
        // its band - the old 16-point sampler never saw changes this size.
        paintRows(changed, 52 until 54)
        assertTrue(detector.hasChanged(changed))
    }

    @Test
    fun `a tiny speck of noise stays below the threshold`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(64))
        val noisy = frame(64)
        noisy[123] = 200.toByte() // one pixel out of 4096
        assertFalse(detector.hasChanged(noisy))
    }

    @Test
    fun `mild compression brightness jitter does not count as changed`() {
        val detector = FrameChangeDetector()
        // Base 72 sits mid-bucket (bucket 4 spans 64-79), so +-2 luminance
        // noise never crosses a bucket edge: compression jitter must not
        // re-trigger OCR.
        val first = frame(72)
        detector.hasChanged(first)
        val jittered = frame(72)
        for (i in jittered.indices step 7) {
            jittered[i] = (72 + (i % 5) - 2).toByte()
        }
        assertFalse(detector.hasChanged(jittered))
    }

    @Test
    fun `a static screen stays unchanged across many frames`() {
        val detector = FrameChangeDetector()
        val static = frame(48)
        paintRows(static, 50 until 56, 0xC0)
        assertTrue(detector.hasChanged(static)) // first frame
        repeat(5) { assertFalse(detector.hasChanged(static.copyOf())) }
    }

    @Test
    fun `reset makes the next frame count as first`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(7))
        detector.reset()
        assertTrue(detector.hasChanged(frame(7)))
    }
}
