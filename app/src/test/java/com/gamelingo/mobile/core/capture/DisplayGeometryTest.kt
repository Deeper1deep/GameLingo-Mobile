package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayGeometryTest {

    private val portrait = DisplayGeometry(width = 1080, height = 2400, rotation = 0)
    private val landscape = DisplayGeometry(width = 2400, height = 1080, rotation = 1)

    @Test
    fun `no geometry yet requires a build`() {
        assertTrue(DisplayGeometry.needsRebuild(null, portrait))
    }

    @Test
    fun `same geometry does not rebuild`() {
        assertFalse(DisplayGeometry.needsRebuild(portrait, portrait.copy()))
    }

    @Test
    fun `rotation to landscape rebuilds`() {
        assertTrue(DisplayGeometry.needsRebuild(portrait, landscape))
    }

    @Test
    fun `rotation change with same size rebuilds`() {
        assertTrue(
            DisplayGeometry.needsRebuild(
                DisplayGeometry(width = 1080, height = 2400, rotation = 0),
                DisplayGeometry(width = 1080, height = 2400, rotation = 2),
            ),
        )
    }
}
