package com.gamelingo.mobile.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConstraintsTest {

    @Test
    fun `capture interval below minimum is clamped to minimum`() {
        assertEquals(
            SettingsConstraints.MIN_CAPTURE_INTERVAL_MS,
            SettingsConstraints.clampCaptureIntervalMs(10L),
        )
    }

    @Test
    fun `capture interval above maximum is clamped to maximum`() {
        assertEquals(
            SettingsConstraints.MAX_CAPTURE_INTERVAL_MS,
            SettingsConstraints.clampCaptureIntervalMs(99_999L),
        )
    }

    @Test
    fun `capture interval inside range is kept`() {
        assertEquals(1_500L, SettingsConstraints.clampCaptureIntervalMs(1_500L))
    }

    @Test
    fun `overlay opacity is clamped to the allowed band`() {
        assertEquals(
            SettingsConstraints.MIN_OVERLAY_OPACITY,
            SettingsConstraints.clampOverlayOpacity(0f),
        )
        assertEquals(
            SettingsConstraints.MAX_OVERLAY_OPACITY,
            SettingsConstraints.clampOverlayOpacity(5f),
        )
        assertEquals(0.5f, SettingsConstraints.clampOverlayOpacity(0.5f))
    }

    @Test
    fun `overlay text size is clamped`() {
        assertEquals(
            SettingsConstraints.MIN_OVERLAY_TEXT_SIZE_SP,
            SettingsConstraints.clampOverlayTextSizeSp(1),
        )
        assertEquals(
            SettingsConstraints.MAX_OVERLAY_TEXT_SIZE_SP,
            SettingsConstraints.clampOverlayTextSizeSp(100),
        )
    }

    @Test
    fun `timeout is clamped`() {
        assertEquals(
            SettingsConstraints.MIN_TIMEOUT_SEC,
            SettingsConstraints.clampTimeoutSec(0),
        )
        assertEquals(
            SettingsConstraints.MAX_TIMEOUT_SEC,
            SettingsConstraints.clampTimeoutSec(1_000),
        )
    }

    @Test
    fun `context lines are clamped`() {
        assertEquals(
            SettingsConstraints.MIN_CONTEXT_LINES,
            SettingsConstraints.clampContextLines(-3),
        )
        assertEquals(
            SettingsConstraints.MAX_CONTEXT_LINES,
            SettingsConstraints.clampContextLines(500),
        )
    }

    @Test
    fun `custom region keeps top below bottom with minimum span`() {
        val region = SettingsConstraints.clampRegionFractions(top = 0.9f, bottom = 0.2f)
        assertTrue(region.top < region.bottom)
        assertTrue(region.span >= SettingsConstraints.MIN_REGION_SPAN)
        assertTrue(region.bottom <= 1f)
        assertTrue(region.top >= 0f)
    }

    @Test
    fun `custom region cannot exceed screen bounds`() {
        val region = SettingsConstraints.clampRegionFractions(top = -0.5f, bottom = 2f)
        assertEquals(0f, region.top)
        assertEquals(1f, region.bottom)
    }

    @Test
    fun `full screen preset covers the whole height`() {
        val region = SettingsConstraints.resolveRegion(TextRegionPreset.FULL_SCREEN, 0.3f, 0.5f)
        assertEquals(0f, region.top)
        assertEquals(1f, region.bottom)
    }

    @Test
    fun `lower third preset ignores custom values`() {
        val region = SettingsConstraints.resolveRegion(TextRegionPreset.LOWER_THIRD, 0.1f, 0.2f)
        assertEquals(SettingsConstraints.LOWER_THIRD_REGION, region)
    }

    @Test
    fun `custom preset applies clamped custom values`() {
        val region = SettingsConstraints.resolveRegion(TextRegionPreset.CUSTOM, 0.2f, 0.8f)
        assertEquals(0.2f, region.top, 1e-6f)
        assertEquals(0.8f, region.bottom, 1e-6f)
    }

    @Test
    fun `default settings form a valid region`() {
        val region = AppSettings().effectiveRegion
        assertTrue(region.top < region.bottom)
        assertTrue(region.bottom <= 1f)
    }
}

class ParseEnumOrDefaultTest {

    @Test
    fun `known value parses`() {
        assertEquals(AiMode.ONLINE, parseEnumOrDefault("ONLINE", AiMode.AUTO))
    }

    @Test
    fun `unknown value falls back to default`() {
        assertEquals(AiMode.AUTO, parseEnumOrDefault("NOT_A_MODE", AiMode.AUTO))
    }

    @Test
    fun `null falls back to default`() {
        assertEquals(TextRegionPreset.LOWER_THIRD, parseEnumOrDefault(null, TextRegionPreset.LOWER_THIRD))
    }

    @Test
    fun `lowercase value falls back to default`() {
        assertEquals(OverlayPosition.BOTTOM, parseEnumOrDefault("bottom", OverlayPosition.BOTTOM))
    }
}
