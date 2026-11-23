package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegionTest {

    private val r = NormalizedRegion(0.10f, 0.65f, 0.90f, 0.95f)

    // ---- storage ---------------------------------------------------------

    @Test fun `normalized values round trip through the stored text`() =
        assertEquals(r, NormalizedRegion.decodeOrNull(r.encode()))

    @Test fun `corrupt stored text is null not a crash`() {
        listOf("", "abc", "0.1,0.2", "0.1,0.2,0.3,x", "NaN,0,1,1", null).forEach {
            assertNull(it, NormalizedRegion.decodeOrNull(it))
        }
    }

    @Test fun `invalid regions are rejected or repaired`() {
        assertNull(NormalizedRegion(0.5f, 0.5f, 0.5f, 0.9f).sanitizedOrNull())
        assertNull(NormalizedRegion(Float.NaN, 0f, 1f, 1f).sanitizedOrNull())
        val inverted = NormalizedRegion(0.9f, 0.9f, 0.1f, 0.1f).sanitizedOrNull()
        assertNotNull(inverted)
        assertTrue(inverted!!.isValid())
        val over = NormalizedRegion(-0.2f, 0.1f, 1.4f, 0.9f).sanitizedOrNull()
        assertEquals(NormalizedRegion(0f, 0.1f, 1f, 0.9f), over)
    }

    // ---- coordinate conversion --------------------------------------------

    @Test fun `the same region fits different resolutions`() {
        val a = r.toPixels(1080, 2400)
        val b = r.toPixels(1440, 3200)
        assertEquals(108, a.left); assertEquals(1560, a.top); assertEquals(972, a.right); assertEquals(2280, a.bottom)
        assertEquals(144, b.left); assertEquals(2080, b.top); assertEquals(1296, b.right); assertEquals(3040, b.bottom)
        assertEquals(a.width.toFloat() / 1080, b.width.toFloat() / 1440, 0.001f)
    }

    @Test fun `pixels convert back to the same fractions`() {
        val px = r.toPixels(1080, 2400)
        val back = NormalizedRegion.fromPixels(px, 1080, 2400)!!
        assertEquals(r.left, back.left, 0.002f); assertEquals(r.bottom, back.bottom, 0.002f)
    }

    @Test fun `pixel rectangle always stays inside the frame and non-empty`() {
        val px = NormalizedRegion(0.99f, 0.99f, 1f, 1f).sanitizedOrNull()
        assertNull(px) // too small to trust
        val edge = NormalizedRegion(0.90f, 0.90f, 1f, 1f).toPixels(100, 100)
        assertTrue(edge.right <= 100 && edge.bottom <= 100 && edge.width >= 1 && edge.height >= 1)
    }

    @Test fun `window offset is added before normalizing`() {
        // The selector window starts 72px below the screen top (status bar).
        val n = NormalizedRegion.fromWindowRect(100f, 100f, 900f, 500f, 0f, 72f, 1000, 2000)!!
        assertEquals(0.10f, n.left, 0.0001f)
        assertEquals(172f / 2000, n.top, 0.0001f)
        assertEquals(572f / 2000, n.bottom, 0.0001f)
    }

    // ---- rotation ---------------------------------------------------------

    @Test fun `each orientation keeps its own region`() {
        val portrait = NormalizedRegion(0.1f, 0.7f, 0.9f, 0.95f)
        val landscape = NormalizedRegion(0.2f, 0.75f, 0.8f, 0.95f)
        val regions = OrientedRegions().with(1080, 2400, portrait).with(2400, 1080, landscape)
        assertEquals(portrait, regions.forFrame(1080, 2400))
        assertEquals(landscape, regions.forFrame(2400, 1080))
    }

    @Test fun `an orientation without a region reads the full screen`() {
        val regions = OrientedRegions(portrait = r)
        assertNull(regions.forFrame(2400, 1080))
    }

    @Test fun `a corrupt stored region falls back to full screen`() =
        assertNull(OrientedRegions(portrait = NormalizedRegion(0.9f, 0.9f, 0.9f, 0.9f)).forFrame(1080, 2400))

    // ---- boundary filtering -------------------------------------------------

    private val region = PixelRect(100, 1500, 1000, 2300)
    private fun block(text: String, l: Int, t: Int, rr: Int, b: Int) = RecognizedBlock(text, PixelRect(l, t, rr, b))

    @Test fun `text fully inside is accepted`() =
        assertTrue(RegionFilter.accepts(PixelRect(200, 1800, 900, 1900), region, RegionSensitivity.STRICT))

    @Test fun `text completely outside is rejected`() =
        assertFalse(RegionFilter.accepts(PixelRect(200, 100, 900, 200), region, RegionSensitivity.LENIENT))

    @Test fun `text that only touches the border is rejected at every sensitivity`() {
        val touching = PixelRect(200, 1450, 900, 1510) // 10 of 60px inside
        RegionSensitivity.values().forEach { assertFalse("$it", RegionFilter.accepts(touching, region, it)) }
    }

    @Test fun `partial overlap follows the sensitivity`() {
        val half = PixelRect(200, 1450, 900, 1550) // exactly half inside
        assertFalse(RegionFilter.accepts(half, region, RegionSensitivity.STRICT))
        assertFalse(RegionFilter.accepts(half, region, RegionSensitivity.BALANCED))
        assertTrue(RegionFilter.accepts(half, region, RegionSensitivity.LENIENT))
        val mostly = PixelRect(200, 1480, 900, 1580) // 80% inside
        assertFalse(RegionFilter.accepts(mostly, region, RegionSensitivity.STRICT))
        assertTrue(RegionFilter.accepts(mostly, region, RegionSensitivity.BALANCED))
    }

    @Test fun `empty block is never accepted`() =
        assertFalse(RegionFilter.accepts(PixelRect(10, 10, 10, 10), region, RegionSensitivity.LENIENT))

    @Test fun `multiple lines keep their order and stay separate`() {
        val blocks = listOf(
            block("Objective: find the key", 150, 200, 900, 260),
            block("Where were you?", 200, 1800, 800, 1860),
            block("PlayerOne", 20, 50, 300, 90),
            block("I was right behind you.", 200, 1900, 900, 1960),
        )
        val out = RegionFilter.filter(blocks, region, RegionSensitivity.BALANCED)
        assertEquals(listOf("Where were you?", "I was right behind you."), out.map { it.text })
    }

    @Test fun `partition splits accepted and rejected for the preview`() {
        val blocks = listOf(block("in", 200, 1800, 800, 1860), block("out", 20, 50, 300, 90))
        val (ok, no) = RegionFilter.partition(blocks, region, RegionSensitivity.BALANCED)
        assertEquals(listOf("in"), ok.map { it.text })
        assertEquals(listOf("out"), no.map { it.text })
    }

    // ---- selection editing ------------------------------------------------

    @Test fun `touches grab corners before the body`() {
        assertEquals(RegionHandle.TOP_LEFT, RegionEditor.hitTest(r, 0.10f, 0.65f, 0.04f))
        assertEquals(RegionHandle.BOTTOM_RIGHT, RegionEditor.hitTest(r, 0.91f, 0.94f, 0.04f))
        assertEquals(RegionHandle.MOVE, RegionEditor.hitTest(r, 0.5f, 0.8f, 0.04f))
        assertEquals(RegionHandle.NONE, RegionEditor.hitTest(r, 0.5f, 0.2f, 0.04f))
    }

    @Test fun `moving keeps the size and stays on screen`() {
        val m = RegionEditor.move(r, 0.5f, 0.5f)
        assertEquals(r.width, m.width, 0.0001f)
        assertEquals(r.height, m.height, 0.0001f)
        assertEquals(1f, m.right, 0.0001f)
        assertEquals(1f, m.bottom, 0.0001f)
        val back = RegionEditor.move(r, -0.9f, -0.9f)
        assertEquals(0f, back.left, 0.0001f)
        assertEquals(0f, back.top, 0.0001f)
    }

    @Test fun `resizing moves one corner and keeps the opposite one`() {
        val z = RegionEditor.resize(r, RegionHandle.TOP_LEFT, 0.2f, 0.7f)
        assertEquals(0.2f, z.left, 0.0001f); assertEquals(0.7f, z.top, 0.0001f)
        assertEquals(r.right, z.right, 0.0001f); assertEquals(r.bottom, z.bottom, 0.0001f)
        val q = RegionEditor.resize(r, RegionHandle.BOTTOM_RIGHT, 0.95f, 0.99f)
        assertEquals(r.left, q.left, 0.0001f); assertEquals(0.99f, q.bottom, 0.0001f)
    }

    @Test fun `resizing can never collapse or invert the rectangle`() {
        val z = RegionEditor.resize(r, RegionHandle.TOP_LEFT, 5f, 5f)
        assertTrue(z.isValid())
        assertTrue(z.width >= NormalizedRegion.MIN_SIZE - 0.0001f)
        val y = RegionEditor.resize(r, RegionHandle.BOTTOM_RIGHT, -3f, -3f)
        assertTrue(y.isValid())
    }

    @Test fun `default start is valid`() = assertTrue(RegionEditor.DEFAULT_START.isValid())
}
