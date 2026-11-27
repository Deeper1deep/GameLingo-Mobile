package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionResolverTest {

    private val bottom = NormalizedRegion(0.10f, 0.68f, 0.90f, 0.95f)
    private val other = NormalizedRegion(0.2f, 0.1f, 0.6f, 0.3f)

    private fun resolve(
        linked: NormalizedRegion? = bottom,
        overrides: OrientedRegions = OrientedRegions(),
        independent: Boolean = false,
        w: Int = 1080, h: Int = 2400,
    ) = RegionResolver.resolve(linked, overrides, independent, w, h)

    // ---- linked region -----------------------------------------------------

    @Test fun `the same normalized region is used in portrait and landscape`() {
        val p = resolve(w = 1080, h = 2400)
        val l = resolve(w = 2400, h = 1080)
        assertEquals(bottom, p.region)
        assertEquals(bottom, l.region)
        assertEquals(RegionSource.LINKED, p.source)
        assertEquals(RegionSource.LINKED, l.source)
    }

    @Test fun `a bottom centred region stays bottom centred after rotation`() {
        val land = resolve(w = 2400, h = 1080).region.toPixels(2400, 1080)
        // Still in the lower part and horizontally centred, not turned to a side.
        assertTrue(land.top > 1080 / 2)
        assertEquals(2400 - land.right, land.left)
        assertTrue(land.width > land.height)
    }

    @Test fun `different resolutions and aspect ratios keep the relative position`() {
        listOf(1080 to 2400, 1440 to 3200, 1080 to 1920, 1200 to 2000, 2400 to 1080).forEach { (w, h) ->
            val px = resolve(w = w, h = h).region.toPixels(w, h)
            assertEquals(0.10f, px.left.toFloat() / w, 0.002f)
            assertEquals(0.68f, px.top.toFloat() / h, 0.002f)
            assertEquals(0.90f, px.right.toFloat() / w, 0.002f)
            assertEquals(0.95f, px.bottom.toFloat() / h, 0.002f)
        }
    }

    @Test fun `rotating back restores exactly the same rectangle`() {
        val a = resolve(w = 1080, h = 2400).region
        resolve(w = 2400, h = 1080)
        assertEquals(a, resolve(w = 1080, h = 2400).region)
    }

    @Test fun `old saved orientation values never override the linked region`() {
        val old = OrientedRegions(portrait = other, landscape = other)
        assertEquals(bottom, resolve(overrides = old, w = 2400, h = 1080).region)
        assertEquals(bottom, resolve(overrides = old, w = 1080, h = 2400).region)
    }

    // ---- independent mode --------------------------------------------------

    @Test fun `independent mode uses the orientation's own region`() {
        val o = OrientedRegions(portrait = bottom, landscape = other)
        val p = resolve(linked = null, overrides = o, independent = true, w = 1080, h = 2400)
        val l = resolve(linked = null, overrides = o, independent = true, w = 2400, h = 1080)
        assertEquals(bottom, p.region); assertEquals(other, l.region)
        assertEquals(RegionSource.ORIENTATION_OVERRIDE, l.source)
    }

    @Test fun `independent mode with a missing override inherits the linked region`() {
        val o = OrientedRegions(portrait = other)
        val l = resolve(linked = bottom, overrides = o, independent = true, w = 2400, h = 1080)
        assertEquals(bottom, l.region)
        assertEquals(RegionSource.LINKED, l.source)
    }

    @Test fun `independent off ignores overrides entirely`() {
        val o = OrientedRegions(landscape = other)
        assertEquals(bottom, resolve(overrides = o, independent = false, w = 2400, h = 1080).region)
    }

    // ---- never full screen -------------------------------------------------

    @Test fun `nothing saved resolves to a real default band never the full screen`() {
        val r = resolve(linked = null)
        assertEquals(RegionSource.DEFAULT, r.source)
        assertEquals(RegionEditor.DEFAULT_START, r.region)
        assertTrue(r.region.isValid())
        assertTrue(r.region.width < 1f && r.region.height < 1f)
    }

    @Test fun `a corrupt linked region falls through safely`() {
        val broken = NormalizedRegion(0.5f, 0.5f, 0.5f, 0.5f)
        val r = resolve(linked = broken)
        assertTrue(r.region.isValid())
        assertEquals(RegionSource.DEFAULT, r.source)
    }

    @Test fun `every input combination resolves to a valid region`() {
        val values = listOf(null, bottom, other, NormalizedRegion(Float.NaN, 0f, 1f, 1f), NormalizedRegion(0.9f, 0.9f, 0.1f, 0.1f))
        for (l in values) for (pr in values) for (ls in values) for (ind in listOf(true, false)) for (dim in listOf(1080 to 2400, 2400 to 1080)) {
            val r = RegionResolver.resolve(l, OrientedRegions(pr, ls), ind, dim.first, dim.second)
            assertTrue(r.region.isValid())
            assertFalse(r.region == NormalizedRegion.FULL)
        }
    }

    // ---- migration -------------------------------------------------------------

    @Test fun `unmigrated legacy values follow the same rule migration uses, no hardcoded portrait first`() {
        val o = OrientedRegions(portrait = bottom, landscape = other)
        val p = resolve(linked = null, overrides = o, w = 1080, h = 2400)
        val l = resolve(linked = null, overrides = o, w = 2400, h = 1080)
        assertEquals(bottom, p.region)
        assertEquals(other, l.region)
        assertEquals(RegionSource.LEGACY_SAVED, p.source)
        // What the fallback shows is exactly what migration promotes for the same orientation.
        assertEquals(p.region, RegionResolver.migrationCandidate(o, preferLandscape = false))
        assertEquals(l.region, RegionResolver.migrationCandidate(o, preferLandscape = true))
    }

    @Test fun `only a landscape legacy region is used in portrait too`() {
        val o = OrientedRegions(landscape = other)
        assertEquals(other, resolve(linked = null, overrides = o, w = 1080, h = 2400).region)
    }

    @Test fun `migration prefers the active orientation then the other`() {
        val both = OrientedRegions(portrait = bottom, landscape = other)
        assertEquals(other, RegionResolver.migrationCandidate(both, preferLandscape = true))
        assertEquals(bottom, RegionResolver.migrationCandidate(both, preferLandscape = false))
        assertEquals(bottom, RegionResolver.migrationCandidate(OrientedRegions(portrait = bottom), preferLandscape = true))
        assertEquals(other, RegionResolver.migrationCandidate(OrientedRegions(landscape = other), preferLandscape = false))
        assertNull(RegionResolver.migrationCandidate(OrientedRegions(), preferLandscape = true))
        assertNull(RegionResolver.migrationCandidate(OrientedRegions(NormalizedRegion(0.5f, 0.5f, 0.5f, 0.5f), null), false))
    }

    // ---- geometry -------------------------------------------------------------

    private fun geo(dw: Int, dh: Int, cw: Int = dw, ch: Int = dh) =
        ScreenGeometry(dw, dh, 0, 2.75f, cw, ch)

    @Test fun `selector rectangle maps to display fractions with the window origin added once`() {
        val g = geo(1000, 2000)
        // Window starts 100px below the top (cutout), view is 1000 x 1900.
        val n = g.selectorToNormalized(100f, 1000f, 900f, 1800f, 0f, 100f)!!
        assertEquals(0.1f, n.left, 0.0001f)
        assertEquals(1100f / 2000, n.top, 0.0001f)
        assertEquals(1900f / 2000, n.bottom, 0.0001f)
    }

    @Test fun `an edge to edge window needs no offset and insets do not shift the region`() {
        val plain = geo(1080, 2400)
        val withInsets = plain.copy(insetTop = 96, insetBottom = 63, insetLeft = 0)
        val a = plain.selectorToNormalized(108f, 1632f, 972f, 2280f, 0f, 0f)
        val b = withInsets.selectorToNormalized(108f, 1632f, 972f, 2280f, 0f, 0f)
        assertEquals(a, b)
        assertEquals(bottom.left, a!!.left, 0.001f)
        assertEquals(bottom.top, a.top, 0.001f)
    }

    @Test fun `capture pixels use the capture frame dimensions`() {
        val g = geo(1080, 2400, cw = 540, ch = 1200)
        assertEquals(PixelRect(54, 816, 486, 1140), g.toCapturePixels(bottom))
        assertEquals(PixelRect(108, 1632, 972, 2280), g.toDisplayPixels(bottom))
    }

    @Test fun `a capture frame of another aspect is reported`() {
        assertFalse(geo(1080, 2400, 540, 1200).captureAspectMismatch())
        assertTrue(geo(1080, 2400, 1080, 1920).captureAspectMismatch())
    }

    @Test fun `a stale frame from before a rotation is rejected`() {
        assertTrue(FrameGeometryGuard.matches(2400, 1080, 2400, 1080))
        assertFalse(FrameGeometryGuard.matches(1080, 2400, 2400, 1080))
    }

    @Test fun `landscape is derived from the display`() {
        assertTrue(geo(2400, 1080).isLandscape)
        assertFalse(geo(1080, 2400).isLandscape)
        assertNotNull(geo(1080, 2400).describe())
    }
}
