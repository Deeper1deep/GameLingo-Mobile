package com.gamelingo.mobile.core.overlay

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPlacementTest {

    private val sw = 1080
    private val sh = 2400
    private val ow = 600
    private val oh = 200
    private val source = NormalizedBox(0.2f, 0.80f, 0.8f, 0.86f)
    private val region = NormalizedBox(0.1f, 0.65f, 0.9f, 0.95f)

    private fun resolve(
        p: TranslationPlacement,
        src: NormalizedBox? = source,
        reg: NormalizedBox? = region,
        top: Boolean = false,
        offsets: Boolean = true,
    ) = PlacementResolver.resolve(p, sw, sh, ow, oh, src, reg, top, 48, offsets)

    // ---- fixed ---------------------------------------------------------

    @Test fun `fixed with nothing saved keeps the old top or bottom anchor`() {
        val bottom = resolve(TranslationPlacement(), top = false)
        assertEquals((sw - ow) / 2, bottom.left)
        assertEquals(sh - 48 - oh, bottom.top)
        assertEquals(48, resolve(TranslationPlacement(), top = true).top)
    }

    @Test fun `fixed saved position is exact and ignores the subtitle and the ocr region`() {
        val p = TranslationPlacement(fixedX = 0.3f, fixedY = 0.1f)
        val a = resolve(p, source, region)
        val b = resolve(p, NormalizedBox(0.5f, 0.2f, 0.9f, 0.3f), NormalizedBox(0f, 0f, 1f, 0.5f))
        val c = resolve(p, null, null)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals((0.3f * sw - ow / 2f).toInt(), a.left)
        assertEquals((0.1f * sh).toInt(), a.top)
    }

    @Test fun `moving the ocr region never moves a fixed translation`() {
        val p = TranslationPlacement(fixedX = 0.5f, fixedY = 0.7f)
        val before = resolve(p, reg = NormalizedBox(0.1f, 0.1f, 0.5f, 0.3f))
        val after = resolve(p, reg = NormalizedBox(0.4f, 0.6f, 0.9f, 0.95f))
        assertEquals(before, after)
    }

    // ---- follow / below ----------------------------------------------------

    @Test fun `follow sits on the original subtitle`() {
        val pt = resolve(TranslationPlacement(mode = PlacementMode.FOLLOW_ORIGINAL))
        assertEquals((source.centerX * sw - ow / 2f).toInt(), pt.left)
        assertEquals((source.top * sh).toInt(), pt.top)
    }

    @Test fun `follow moves when the subtitle moves and ignores the fixed position`() {
        val p = TranslationPlacement(mode = PlacementMode.FOLLOW_ORIGINAL, fixedX = 0.1f, fixedY = 0.1f)
        val a = resolve(p, NormalizedBox(0.2f, 0.5f, 0.8f, 0.56f))
        val b = resolve(p, NormalizedBox(0.2f, 0.7f, 0.8f, 0.76f))
        assertTrue(b.top > a.top)
    }

    @Test fun `below is directly under the original`() {
        val pt = resolve(TranslationPlacement(mode = PlacementMode.BELOW_ORIGINAL), NormalizedBox(0.2f, 0.5f, 0.8f, 0.56f))
        assertTrue(pt.top >= (0.56f * sh).toInt())
        assertTrue(pt.top < (0.56f * sh).toInt() + 60)
    }

    @Test fun `below flips above when there is no room beneath`() {
        val low = NormalizedBox(0.2f, 0.93f, 0.8f, 0.98f)
        val pt = resolve(TranslationPlacement(mode = PlacementMode.BELOW_ORIGINAL), low)
        assertTrue(pt.top + oh <= (low.top * sh).toInt())
    }

    @Test fun `followed modes without a known subtitle fall back to the fixed spot`() {
        val fixed = resolve(TranslationPlacement(fixedX = 0.5f, fixedY = 0.4f), src = null)
        listOf(PlacementMode.FOLLOW_ORIGINAL, PlacementMode.BELOW_ORIGINAL).forEach {
            assertEquals(fixed, resolve(TranslationPlacement(mode = it, fixedX = 0.5f, fixedY = 0.4f), src = null))
        }
    }

    // ---- smart auto --------------------------------------------------------

    @Test fun `smart auto covers neither the subtitle nor the scanned area when it can`() {
        val pt = resolve(TranslationPlacement(mode = PlacementMode.SMART_AUTO), source, region)
        val box = NormalizedBox(
            pt.left.toFloat() / sw, pt.top.toFloat() / sh,
            (pt.left + ow).toFloat() / sw, (pt.top + oh).toFloat() / sh,
        )
        assertEquals(0f, box.intersectionArea(source), 0.0001f)
        assertEquals(0f, box.intersectionArea(region), 0.0001f)
    }

    @Test fun `smart auto without information uses the fixed spot`() {
        assertEquals(
            resolve(TranslationPlacement(), null, null),
            resolve(TranslationPlacement(mode = PlacementMode.SMART_AUTO), null, null),
        )
    }

    // ---- offsets, clamping ------------------------------------------------

    @Test fun `offsets nudge any mode`() {
        val base = resolve(TranslationPlacement(mode = PlacementMode.FOLLOW_ORIGINAL))
        val moved = resolve(TranslationPlacement(mode = PlacementMode.FOLLOW_ORIGINAL, offsetX = 0.1f, offsetY = -0.05f))
        assertEquals(base.left + (0.1f * sw).toInt(), moved.left)
        assertEquals(base.top - (0.05f * sh).toInt(), moved.top)
    }

    @Test fun `the window always stays on screen`() {
        val p = TranslationPlacement(fixedX = 1f, fixedY = 1f, offsetX = 0.5f, offsetY = 0.5f)
        val pt = resolve(p)
        assertTrue(pt.left in 0..(sw - ow))
        assertTrue(pt.top in 0..(sh - oh))
    }

    @Test fun `resolution change keeps the same relative spot`() {
        val p = TranslationPlacement(fixedX = 0.4f, fixedY = 0.3f)
        val a = PlacementResolver.resolve(p, 1080, 2400, 540, 200, null, null, false, 48)
        val b = PlacementResolver.resolve(p, 1440, 3200, 720, 266, null, null, false, 64)
        assertEquals((a.left + 270f) / 1080, (b.left + 360f) / 1440, 0.002f)
        assertEquals(a.top / 2400f, b.top / 3200f, 0.002f)
    }

    @Test fun `rotation uses the same fractions on the new screen`() {
        val p = TranslationPlacement(fixedX = 0.5f, fixedY = 0.7f)
        val land = PlacementResolver.resolve(p, 2400, 1080, 600, 120, null, null, false, 48)
        assertEquals((2400 - 600) / 2, land.left)
        assertEquals((0.7f * 1080).toInt(), land.top)
    }

    // ---- drag results -----------------------------------------------------

    @Test fun `dragging in fixed mode saves a fixed position and clears nudges`() {
        val p = TranslationPlacement(offsetX = 0.1f)
        val base = PlacementPoint(240, 2000)
        val out = PlacementResolver.afterDrag(p, 100, 500, base, sw, sh, ow)
        assertEquals((100 + ow / 2f) / sw, out.fixedX!!, 0.0001f)
        assertEquals(500f / sh, out.fixedY!!, 0.0001f)
        assertEquals(0f, out.offsetX, 0f)
    }

    @Test fun `dragging in a followed mode keeps the mode and saves a nudge`() {
        val p = TranslationPlacement(mode = PlacementMode.BELOW_ORIGINAL, fixedX = 0.2f, fixedY = 0.2f)
        val out = PlacementResolver.afterDrag(p, 300, 1000, PlacementPoint(240, 900), sw, sh, ow)
        assertEquals(PlacementMode.BELOW_ORIGINAL, out.mode)
        assertEquals(60f / sw, out.offsetX, 0.0001f)
        assertEquals(100f / sh, out.offsetY, 0.0001f)
        assertEquals(0.2f, out.fixedX!!, 0f) // saved fixed spot untouched
    }

    // ---- storage ------------------------------------------------------------

    @Test fun `placement survives a stored round trip`() {
        val p = TranslationPlacement(
            PlacementMode.SMART_AUTO, 0.3f, 0.6f, 0.05f, -0.1f, 0.7f, 0.15f, PlacementAlign.RIGHT,
        )
        assertEquals(p, TranslationPlacement.decodeOrDefault(TranslationPlacement.encode(p)))
    }

    @Test fun `corrupt or missing storage gives the default`() {
        listOf(null, "", "{", "[]", """{"mode":"NOPE"}""").forEach {
            assertEquals(TranslationPlacement(), TranslationPlacement.decodeOrDefault(it))
        }
    }

    @Test fun `out of range values are clamped`() {
        val p = TranslationPlacement(fixedX = 3f, fixedY = -2f, widthFraction = 0.01f, heightFraction = 9f, offsetX = 5f).sanitized()
        assertEquals(1f, p.fixedX!!, 0f)
        assertEquals(0f, p.fixedY!!, 0f)
        assertEquals(TranslationPlacement.MIN_WIDTH, p.widthFraction!!, 0f)
        assertEquals(1f, p.heightFraction!!, 0f)
        assertEquals(TranslationPlacement.MAX_OFFSET, p.offsetX, 0f)
    }

    @Test fun `reset clears position and size but keeps mode and alignment`() {
        val p = TranslationPlacement(PlacementMode.BELOW_ORIGINAL, 0.2f, 0.2f, 0.1f, 0.1f, 0.5f, 0.2f, PlacementAlign.LEFT).positionReset()
        assertNull(p.fixedX); assertNull(p.fixedY); assertNull(p.widthFraction); assertNull(p.heightFraction)
        assertEquals(0f, p.offsetX, 0f)
        assertEquals(PlacementMode.BELOW_ORIGINAL, p.mode)
        assertEquals(PlacementAlign.LEFT, p.align)
    }

    // ---- original subtitle box ---------------------------------------------

    @Test fun `source box is the union of the lines that were published`() {
        val blocks = listOf(
            RecognizedBlock("Where were you?", PixelRect(200, 1800, 800, 1850)),
            RecognizedBlock("I was behind you.", PixelRect(220, 1860, 900, 1910)),
            RecognizedBlock("PlayerOne", PixelRect(20, 50, 300, 90)),
        )
        val box = NormalizedBox.ofBlocks(blocks, "Where were you? I was behind you.", sw, sh)
        assertNotNull(box)
        assertEquals(200f / sw, box!!.left, 0.0001f)
        assertEquals(900f / sw, box.right, 0.0001f)
        assertEquals(1800f / sh, box.top, 0.0001f)
        assertEquals(1910f / sh, box.bottom, 0.0001f)
    }

    @Test fun `no blocks no box`() = assertNull(NormalizedBox.ofBlocks(emptyList(), "x", sw, sh))
}
