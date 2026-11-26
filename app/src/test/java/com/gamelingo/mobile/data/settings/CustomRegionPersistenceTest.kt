package com.gamelingo.mobile.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.core.region.NormalizedRegion
import com.gamelingo.mobile.core.region.RegionSensitivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The real DataStore repository on the JVM (Robolectric). One test method on
 * purpose: the DataStore delegate is process-wide, so ordering is explicit.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class CustomRegionPersistenceTest {

    @Test
    fun `custom region settings default off and persist normalized values`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = DataStoreSettingsRepository(context, ApiKeyCipher())

        // Disabled by default: existing users keep the current behaviour.
        val initial = repo.settings.first()
        assertFalse(initial.customRegionEnabled)
        assertNull(initial.customRegions.portrait)
        assertNull(initial.customRegions.landscape)
        assertTrue(initial.showRegionBorder)
        assertEquals(RegionSensitivity.BALANCED, initial.regionSensitivity)
        assertFalse(initial.regionPreview)
        val keepsOtherSettings = initial.captureIntervalMs

        val portrait = NormalizedRegion(0.10f, 0.65f, 0.90f, 0.95f)
        val landscape = NormalizedRegion(0.2f, 0.7f, 0.8f, 0.95f)
        repo.setCustomRegionEnabled(true)
        repo.setCustomRegion(landscape = false, region = portrait)
        repo.setCustomRegion(landscape = true, region = landscape)
        repo.setRegionSensitivity(RegionSensitivity.STRICT)
        repo.setShowRegionBorder(false)
        repo.setRegionPreview(true)

        // A fresh repository reads the same values back (restart).
        val restored = DataStoreSettingsRepository(context, ApiKeyCipher()).settings.first()
        assertTrue(restored.customRegionEnabled)
        assertEquals(portrait, restored.customRegions.portrait)
        assertEquals(landscape, restored.customRegions.landscape)
        assertEquals(RegionSensitivity.STRICT, restored.regionSensitivity)
        assertFalse(restored.showRegionBorder)
        assertTrue(restored.regionPreview)
        // Nothing else was touched.
        assertEquals(keepsOtherSettings, restored.captureIntervalMs)

        // An invalid rectangle is never stored: it clears that orientation.
        repo.setCustomRegion(landscape = true, region = NormalizedRegion(0.5f, 0.5f, 0.5f, 0.5f))
        val afterInvalid = repo.settings.first()
        assertNull(afterInvalid.customRegions.landscape)
        assertNotNull(afterInvalid.customRegions.portrait)

        // The translation placement is stored apart from the region: saving
        // one never changes the other.
        val placement = com.gamelingo.mobile.core.overlay.TranslationPlacement(
            mode = com.gamelingo.mobile.core.overlay.PlacementMode.BELOW_ORIGINAL,
            fixedX = 0.4f, fixedY = 0.2f, widthFraction = 0.6f,
        )
        val regionBefore = repo.settings.first().customRegions
        repo.setTranslationPlacement(placement)
        val afterPlacement = repo.settings.first()
        assertEquals(placement, afterPlacement.translationPlacement)
        assertEquals(regionBefore, afterPlacement.customRegions)
        repo.setCustomRegion(landscape = false, region = NormalizedRegion(0.3f, 0.3f, 0.6f, 0.6f))
        assertEquals(placement, repo.settings.first().translationPlacement)
        // Restart keeps both.
        val again = DataStoreSettingsRepository(context, ApiKeyCipher()).settings.first()
        assertEquals(placement, again.translationPlacement)
        assertEquals(NormalizedRegion(0.3f, 0.3f, 0.6f, 0.6f), again.customRegions.portrait)

        // Reset to full screen = clearing the rectangle.
        repo.setCustomRegion(landscape = false, region = null)
        assertNull(repo.settings.first().customRegions.portrait)
    }
}
