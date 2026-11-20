package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionMapperTest {

    @Test
    fun `full range covers the whole frame`() {
        val rect = RegionMapper.mapToPixels(0f, 1f, frameWidth = 1080, frameHeight = 2400)
        assertEquals(PixelRect(0, 0, 1080, 2400), rect)
    }

    @Test
    fun `lower third maps to the bottom band`() {
        val rect = RegionMapper.mapToPixels(0.60f, 0.98f, frameWidth = 1080, frameHeight = 2400)
        assertEquals(0, rect.left)
        assertEquals(1080, rect.right)
        assertEquals(1440, rect.top)
        assertEquals(2352, rect.bottom)
    }

    @Test
    fun `region is clamped inside the frame`() {
        val rect = RegionMapper.mapToPixels(-0.5f, 2f, frameWidth = 1080, frameHeight = 2400)
        assertEquals(0, rect.top)
        assertEquals(2400, rect.bottom)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero height frame is rejected`() {
        RegionMapper.mapToPixels(0f, 1f, frameWidth = 1080, frameHeight = 0)
    }
}
