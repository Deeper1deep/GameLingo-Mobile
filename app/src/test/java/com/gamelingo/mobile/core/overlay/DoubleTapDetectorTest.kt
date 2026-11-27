package com.gamelingo.mobile.core.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapDetectorTest {

    private fun detector() = DoubleTapDetector(slopPx = 24)

    private fun DoubleTapDetector.tap(downAt: Long, upAt: Long, x: Float = 500f, y: Float = 900f): Boolean {
        onDown(downAt, x, y)
        return onUp(upAt)
    }

    @Test fun `two quick taps in the same place are a double tap`() {
        val d = detector()
        assertFalse(d.tap(0, 80))
        assertTrue(d.tap(200, 270))
    }

    @Test fun `a single tap is not a double tap`() {
        assertFalse(detector().tap(0, 80))
    }

    @Test fun `taps too far apart in time are not a double tap`() {
        val d = detector()
        assertFalse(d.tap(0, 80))
        assertFalse(d.tap(900, 970))
    }

    @Test fun `taps in different places are not a double tap`() {
        val d = detector()
        assertFalse(d.tap(0, 80, x = 100f))
        assertFalse(d.tap(200, 270, x = 600f))
    }

    @Test fun `a long press is not a tap and clears the first tap`() {
        val d = detector()
        assertFalse(d.tap(0, 80))
        assertFalse(d.tap(200, 1_800))
        assertFalse(d.tap(2_000, 2_070))
    }

    @Test fun `a drag is not a tap`() {
        val d = detector()
        assertFalse(d.tap(0, 80))
        d.onDown(200, 500f, 900f)
        d.onMove(560f, 900f)
        assertFalse(d.onUp(260))
    }

    @Test fun `a cancel clears the first tap`() {
        val d = detector()
        assertFalse(d.tap(0, 80))
        d.onDown(200, 500f, 900f)
        d.onCancel()
        assertFalse(d.tap(260, 330))
    }

    @Test fun `a third tap starts over after a double tap`() {
        val d = detector()
        d.tap(0, 80)
        assertTrue(d.tap(200, 270))
        assertFalse(d.tap(380, 450))
        assertTrue(d.tap(560, 630))
    }

    @Test fun `tap within slop jitter still counts`() {
        val d = detector()
        d.onDown(0, 500f, 900f); d.onMove(510f, 905f); assertFalse(d.onUp(70))
        d.onDown(200, 505f, 902f); d.onMove(498f, 899f)
        assertTrue(d.onUp(270))
    }
}
