package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeDetectorTest {

    private fun frame(fill: Int, size: Int = 16_384): ByteArray =
        ByteArray(size) { fill.toByte() }

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
        for (i in changed.size * 2 / 3 until changed.size) {
            changed[i] = 0xFF.toByte()
        }
        assertTrue(detector.hasChanged(changed))
    }

    @Test
    fun `a tiny speck of noise stays below the threshold`() {
        val detector = FrameChangeDetector(sampleStep = 1, changeThreshold = 0.02)
        detector.hasChanged(frame(64))
        val noisy = frame(64)
        noisy[123] = 200.toByte() // one pixel out of 16384
        assertFalse(detector.hasChanged(noisy))
    }

    @Test
    fun `reset makes the next frame count as first`() {
        val detector = FrameChangeDetector()
        detector.hasChanged(frame(7))
        detector.reset()
        assertTrue(detector.hasChanged(frame(7)))
    }
}
