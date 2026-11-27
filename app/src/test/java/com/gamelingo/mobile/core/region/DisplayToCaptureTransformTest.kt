package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests of the real coordinate mapping: final pixels, not just normalized values. */
class DisplayToCaptureTransformTest {

    private val subtitles = NormalizedRegion(0.10f, 0.68f, 0.90f, 0.95f)

    // ---- A. coordinate-space mismatch ------------------------------------------------

    @Test fun `faithful frame is the plain fraction mapping`() {
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 2400)
        assertTrue(t.isFaithful)
        assertEquals(PixelRect(108, 1632, 972, 2280), t.toCapturePixels(subtitles))
    }

    @Test fun `half size faithful frame scales everything`() {
        val t = DisplayToCaptureTransform(1080, 2400, 540, 1200)
        assertTrue(t.isFaithful)
        assertEquals(PixelRect(54, 816, 486, 1140), t.toCapturePixels(subtitles))
    }

    @Test fun `frame with a different aspect is letterboxed and the region follows the picture`() {
        // Display 1080x2400 shown in a 1080x1920 frame: scale 0.8, side bars of 108px.
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 1920)
        assertFalse(t.isFaithful)
        assertEquals(0.8f, t.scale, 1e-4f)
        assertEquals(108f, t.offsetX, 0.01f)
        assertEquals(0f, t.offsetY, 0.01f)
        val px = t.toCapturePixels(subtitles)!!
        // left = 0.10*1080*0.8 + 108 = 194.4; top = 0.68*2400*0.8 = 1305.6
        assertEquals(194, px.left)
        assertEquals(1306, px.top)
        assertTrue(px.left >= t.contentRect()!!.left && px.right <= t.contentRect()!!.right)
    }

    @Test fun `wide frame gets top and bottom bars`() {
        // Display 1080x2400 in a 1200x2400 frame: scale 1.0, 60px side bars; and 1080x2400 in 1080x3000: scale 1.0, 300px bars top/bottom.
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 3000)
        assertEquals(1f, t.scale, 1e-4f)
        assertEquals(300f, t.offsetY, 0.01f)
        val px = t.toCapturePixels(NormalizedRegion(0f, 0f, 1f, 0.1f))!!
        assertEquals(300, px.top)
        assertEquals(540, px.bottom)
    }

    @Test fun `region at the display edges stays inside the content`() {
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 1920)
        val px = t.toCapturePixels(NormalizedRegion.FULL)!!
        assertEquals(t.contentRect(), px)
    }

    @Test fun `invalid dimensions give no mapping instead of a full frame`() {
        assertNull(DisplayToCaptureTransform(0, 2400, 1080, 2400).toCapturePixels(subtitles))
        assertNull(DisplayToCaptureTransform(1080, 2400, 0, 0).toCapturePixels(subtitles))
    }

    @Test fun `selector window with a non zero origin adds the origin exactly once`() {
        // Overlay window starts 96px below the top (cutout), view 1080 x 2304 inside a 1080 x 2400 display.
        val g = ScreenGeometry(1080, 2400, 0, 2.75f, 1080, 2400)
        val n = g.selectorToNormalized(108f, 1536f, 972f, 2184f, 0f, 96f)!!
        assertEquals(subtitles.left, n.left, 0.001f)
        assertEquals(subtitles.top, n.top, 0.001f)
        assertEquals(subtitles.bottom, n.bottom, 0.001f)
        // Selector and capture agree on the same screen area end to end.
        val capture = DisplayToCaptureTransform(1080, 2400, 1080, 2400).toCapturePixels(n)!!
        assertEquals(PixelRect(108, 1632, 972, 2280), capture)
    }

    @Test fun `display size change keeps the saved region at the same relative place`() {
        val small = DisplayToCaptureTransform(720, 1600, 720, 1600).toCapturePixels(subtitles)!!
        val big = DisplayToCaptureTransform(1440, 3200, 1440, 3200).toCapturePixels(subtitles)!!
        assertEquals(small.left * 2, big.left)
        assertEquals(small.top * 2, big.top)
        assertEquals(small.right * 2, big.right)
        assertEquals(small.bottom * 2, big.bottom)
    }

    @Test fun `inverse mapping round trips through letterboxing`() {
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 1920)
        val px = t.toCapturePixels(subtitles)!!
        val f = t.frameRectToDisplayFractions(px)!!
        assertEquals(subtitles.left, f[0], 0.01f)
        assertEquals(subtitles.top, f[1], 0.01f)
        assertEquals(subtitles.right, f[2], 0.01f)
        assertEquals(subtitles.bottom, f[3], 0.01f)
    }

    @Test fun `aspect check recognises a single window capture`() {
        assertTrue(DisplayToCaptureTransform.sameAspect(1080, 2400, 1080, 2400))
        assertTrue(DisplayToCaptureTransform.sameAspect(1080, 2400, 540, 1200))
        assertFalse(DisplayToCaptureTransform.sameAspect(1080, 2400, 1080, 1700))
        assertFalse(DisplayToCaptureTransform.sameAspect(1080, 2400, 0, 0))
    }

    // ---- D. rotation sequence ----------------------------------------------------------

    @Test fun `portrait landscape portrait landscape portrait maps the same area every time`() {
        val dims = listOf(1080 to 2400, 2400 to 1080, 1080 to 2400, 2400 to 1080, 1080 to 2400)
        val resolver = { w: Int, h: Int -> RegionResolver.resolve(subtitles, OrientedRegions(), false, w, h).region }
        for ((w, h) in dims) {
            val region = resolver(w, h)
            assertEquals(subtitles, region)
            val px = DisplayToCaptureTransform(w, h, w, h).toCapturePixels(region)!!
            // Same relative place in every orientation, within a pixel of rounding.
            assertEquals(subtitles.left * w, px.left.toFloat(), 1f)
            assertEquals(subtitles.top * h, px.top.toFloat(), 1f)
            assertEquals(subtitles.right * w, px.right.toFloat(), 1f)
            assertEquals(subtitles.bottom * h, px.bottom.toFloat(), 1f)
        }
    }

    @Test fun `rotation sequence holds across resolutions and aspect ratios`() {
        val sizes = listOf(1080 to 2340, 1080 to 2400, 1440 to 3200, 800 to 1280, 1200 to 1920, 1080 to 2640)
        for ((a, b) in sizes) {
            for ((w, h) in listOf(a to b, b to a, a to b, b to a)) {
                val px = DisplayToCaptureTransform(w, h, w, h).toCapturePixels(subtitles)!!
                assertEquals(subtitles.left * w, px.left.toFloat(), 1f)
                assertEquals(subtitles.top * h, px.top.toFloat(), 1f)
                assertTrue(px.width > 0 && px.height > 0)
            }
        }
    }

    // ---- E. OCR correctness at final pixel coordinates ------------------------------------

    private fun block(text: String, l: Int, t: Int, r: Int, b: Int) = RecognizedBlock(text, PixelRect(l, t, r, b))

    @Test fun `text inside the region is accepted and text outside is rejected in portrait and landscape`() {
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080)) {
            val t = DisplayToCaptureTransform(w, h, w, h)
            val region = t.toCapturePixels(subtitles)!!
            // A subtitle centred inside the region, and HUD text in the top-left corner.
            val cx = (region.left + region.right) / 2
            val cy = (region.top + region.bottom) / 2
            val inside = block("sub", cx - 100, cy - 20, cx + 100, cy + 20)
            val outside = block("hud", 10, 10, 200, 60)
            val kept = RegionFilter.filter(listOf(inside, outside), region, RegionSensitivity.BALANCED)
            assertEquals(listOf("sub"), kept.map { it.text })
        }
    }

    @Test fun `letterboxed frame filters with the transformed pixels`() {
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 1920)
        val region = t.toCapturePixels(subtitles)!!
        val cx = (region.left + region.right) / 2
        val cy = (region.top + region.bottom) / 2
        val inside = block("sub", cx - 100, cy - 20, cx + 100, cy + 20)
        // Text where the UNtransformed fraction mapping would have put the region, but that is outside the real one.
        val wrong = DisplayToCaptureTransform(1080, 1920, 1080, 1920).toCapturePixels(subtitles)!!
        assertNotNull(wrong)
        val kept = RegionFilter.filter(listOf(inside), region, RegionSensitivity.BALANCED)
        assertEquals(1, kept.size)
        // Text over the bar (outside the content) is never inside the region.
        assertTrue(region.left >= t.contentRect()!!.left)
        val barText = block("bar", 0, region.top, 50, region.bottom)
        assertTrue(RegionFilter.filter(listOf(barText), region, RegionSensitivity.LENIENT).isEmpty())
    }

    @Test fun `preview boxes use the same transform as OCR`() {
        val t = DisplayToCaptureTransform(1080, 2400, 1080, 1920)
        val region = t.toCapturePixels(subtitles)!!
        val cx = (region.left + region.right) / 2
        val cy = (region.top + region.bottom) / 2
        val result = RegionPreviewBus.build(
            listOf(block("sub", cx - 50, cy - 10, cx + 50, cy + 10)),
            1080, 1920,
            RegionPreviewBus.Candidate(subtitles, RegionSensitivity.BALANCED),
            t,
        )
        assertEquals(1, result.boxes.size)
        assertTrue(result.boxes[0].accepted)
        // Box is in display fractions: its centre is inside the region fractions.
        val bx = (result.boxes[0].left + result.boxes[0].right) / 2
        val by = (result.boxes[0].top + result.boxes[0].bottom) / 2
        assertTrue(bx > subtitles.left && bx < subtitles.right)
        assertTrue(by > subtitles.top && by < subtitles.bottom)
    }
}
