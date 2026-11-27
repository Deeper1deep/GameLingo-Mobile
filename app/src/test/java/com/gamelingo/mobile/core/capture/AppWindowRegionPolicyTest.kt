package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.region.NormalizedRegion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppWindowRegionPolicyTest {
    @After fun reset() = AppWindowRegionStore.clear()
    private val r = NormalizedRegion(0.1f, 0.6f, 0.9f, 0.9f)
    private val size = 800 to 600

    @Test fun `whole screen capture always uses the saved settings`() {
        assertEquals(AppWindowRegionPolicy.Decision.UseSettings, AppWindowRegionPolicy.decide(false, true, size))
    }

    @Test fun `custom area off means no region is needed in app window mode`() {
        assertEquals(AppWindowRegionPolicy.Decision.UseSettings, AppWindowRegionPolicy.decide(true, false, size))
    }

    @Test fun `app window mode never falls back to the display space region`() {
        assertEquals(AppWindowRegionPolicy.Decision.NeedsRepick, AppWindowRegionPolicy.decide(true, true, size))
    }

    @Test fun `a pick for this window size is used`() {
        AppWindowRegionStore.entry = AppWindowRegionStore.Entry(r, 800, 600)
        assertEquals(AppWindowRegionPolicy.Decision.UseSession(r), AppWindowRegionPolicy.decide(true, true, size))
    }

    @Test fun `a resized or rotated window needs a new pick`() {
        AppWindowRegionStore.entry = AppWindowRegionStore.Entry(r, 800, 600)
        assertEquals(AppWindowRegionPolicy.Decision.NeedsRepick, AppWindowRegionPolicy.decide(true, true, 600 to 800))
        assertEquals(AppWindowRegionPolicy.Decision.NeedsRepick, AppWindowRegionPolicy.decide(true, true, null))
    }

    @Test fun `a new capture session with the same size needs a new pick`() {
        AppWindowRegionStore.entry = AppWindowRegionStore.Entry(r, 800, 600)
        AppWindowRegionStore.clear() // what capture start does
        assertEquals(AppWindowRegionPolicy.Decision.NeedsRepick, AppWindowRegionPolicy.decide(true, true, size))
    }

    @Test fun `switching back to whole screen uses settings again and the pick is not needed`() {
        AppWindowRegionStore.entry = AppWindowRegionStore.Entry(r, 800, 600)
        assertEquals(AppWindowRegionPolicy.Decision.UseSettings, AppWindowRegionPolicy.decide(false, true, size))
    }

    @Test fun `a valid pick is used even when the saved custom area is off, without touching settings`() {
        AppWindowRegionStore.entry = AppWindowRegionStore.Entry(r, 800, 600)
        assertEquals(AppWindowRegionPolicy.Decision.UseSession(r), AppWindowRegionPolicy.decide(true, false, size))
    }

    @Test fun `a stale pick with custom area off still needs a new pick`() {
        AppWindowRegionStore.entry = AppWindowRegionStore.Entry(r, 800, 600)
        assertEquals(AppWindowRegionPolicy.Decision.NeedsRepick, AppWindowRegionPolicy.decide(true, false, 700 to 600))
    }
}
