package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.capture.OcrPreprocessor.Option
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic buffers only: these prove the maths and the keep-original rule, NOT quality on game frames. */
class OcrPreprocessorTest {
    private fun frame(w: Int, h: Int, bg: Int, fg: Int): Gray {
        val px = IntArray(w * h) { bg }
        for (y in h / 3 until 2 * h / 3) for (x in 2 until w - 2 step 3) px[y * w + x] = fg
        return Gray(w, h, px)
    }

    @Test fun `grayscale uses luma weights`() {
        val g = OcrPreprocessor.toGray(1, 3, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertEquals(listOf(76, 149, 29), g.px.toList())
    }

    @Test fun `contrast stretch widens a washed-out frame and increases separation`() {
        val low = frame(30, 12, 100, 140)
        val s = OcrPreprocessor.contrastStretch(low)
        assertEquals(0, s.px.min()); assertEquals(255, s.px.max())
        assertTrue(OcrPreprocessor.compare(low, Option.CONTRAST_STRETCH).gain > 0.05)
    }

    @Test fun `a frame that already has full contrast is left alone by the helpful filter`() {
        val good = frame(30, 12, 0, 255)
        assertTrue(OcrPreprocessor.chooseHelpful(good).none { it.option == Option.CONTRAST_STRETCH })
    }

    @Test fun `flat frame is not stretched into noise`() {
        val flat = Gray(4, 4, IntArray(16) { 120 })
        assertEquals(flat.px.toList(), OcrPreprocessor.contrastStretch(flat).px.toList())
        assertEquals(0.0, OcrPreprocessor.separation(flat), 0.0)
    }

    @Test fun `scale multiplies size and keeps corners`() {
        val g = Gray(2, 2, intArrayOf(0, 255, 255, 0))
        val s = OcrPreprocessor.scale(g, 2)
        assertEquals(4, s.width); assertEquals(4, s.height)
        assertEquals(0, s[0, 0])
    }

    @Test fun `otsu threshold yields only black and white`() {
        val t = OcrPreprocessor.threshold(frame(30, 12, 60, 200))
        assertTrue(t.px.all { it == 0 || it == 255 })
        assertTrue(t.px.any { it == 255 } && t.px.any { it == 0 })
    }

    @Test fun `sharpen keeps size and clamps`() {
        val s = OcrPreprocessor.sharpen(frame(10, 10, 10, 250))
        assertEquals(100, s.px.size)
        assertTrue(s.px.all { it in 0..255 })
    }

    @Test fun `before and after comparison is reported per option`() {
        val c = OcrPreprocessor.compare(frame(30, 12, 100, 140), Option.THRESHOLD)
        assertTrue(c.after >= c.before)
    }
}
