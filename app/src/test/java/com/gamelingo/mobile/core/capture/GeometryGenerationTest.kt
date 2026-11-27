package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.region.FrameGeometryGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame rejection and rebuild decisions around rotation / captured-content changes. */
class GeometryGenerationTest {

    private val portrait = DisplayGeometry(1080, 2400, 0, generation = 3)

    @Test fun `generation alone never triggers a rebuild`() {
        assertFalse(DisplayGeometry.needsRebuild(portrait, portrait.copy(generation = 9)))
        assertFalse(DisplayGeometry.needsUpdate(portrait, portrait.copy(generation = 9)))
    }

    @Test fun `a reported content size change updates the geometry without a surface rebuild`() {
        val reported = portrait.copy(contentWidth = 1080, contentHeight = 2400)
        assertFalse(DisplayGeometry.needsRebuild(portrait, reported))
        assertTrue(DisplayGeometry.needsUpdate(portrait, reported))
    }

    @Test fun `repeated identical content resize callbacks change nothing`() {
        val reported = portrait.copy(contentWidth = 1080, contentHeight = 2400)
        assertFalse(DisplayGeometry.needsUpdate(reported, reported.copy(generation = 7)))
    }

    @Test fun `rotation rebuilds`() {
        val land = DisplayGeometry(2400, 1080, 1)
        assertTrue(DisplayGeometry.needsRebuild(portrait, land))
    }

    @Test fun `a frame from an older generation is rejected even when sizes match`() {
        assertFalse(FrameGeometryGuard.matches(1080, 2400, 1080, 2400, readerGeneration = 2, activeGeneration = 3))
        assertTrue(FrameGeometryGuard.matches(1080, 2400, 1080, 2400, readerGeneration = 3, activeGeneration = 3))
    }

    @Test fun `an image whose size differs from the active geometry is rejected`() {
        assertFalse(FrameGeometryGuard.matches(2400, 1080, 1080, 2400, 3, 3))
        assertFalse(FrameGeometryGuard.matches(1080, 2399, 1080, 2400, 3, 3))
    }
}
