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

        // ---- linked region + safe migration -------------------------------------
        val legacyPortrait = NormalizedRegion(0.1f, 0.68f, 0.9f, 0.95f)
        val legacyLandscape = NormalizedRegion(0.3f, 0.6f, 0.7f, 0.9f)
        repo.setCustomRegion(landscape = false, region = legacyPortrait)
        repo.setCustomRegion(landscape = true, region = legacyLandscape)
        val before = repo.settings.first()
        assertNull(before.linkedRegion)
        assertFalse(before.independentRegions) // never silently switched on

        repo.migrateLinkedRegion(preferLandscape = true)
        val migrated = repo.settings.first()
        assertEquals(legacyLandscape, migrated.linkedRegion) // active orientation preferred
        // Nothing deleted, nothing overwritten.
        assertEquals(legacyPortrait, migrated.customRegions.portrait)
        assertEquals(legacyLandscape, migrated.customRegions.landscape)
        // Idempotent: a second run (even preferring the other side) changes nothing.
        repo.migrateLinkedRegion(preferLandscape = false)
        assertEquals(legacyLandscape, repo.settings.first().linkedRegion)

        // The linked region resolves identically in both orientations.
        val s = repo.settings.first()
        assertEquals(s.resolveCustomRegion(1080, 2400).region, s.resolveCustomRegion(2400, 1080).region)

        // Selecting in linked mode writes only the linked region.
        val picked = NormalizedRegion(0.2f, 0.7f, 0.8f, 0.9f)
        repo.setLinkedRegion(picked)
        val afterPick = repo.settings.first()
        assertEquals(picked, afterPick.linkedRegion)
        assertEquals(legacyPortrait, afterPick.customRegions.portrait)

        // Independent mode (explicit) uses the override of the orientation.
        repo.setIndependentRegions(true)
        val ind = repo.settings.first()
        assertEquals(legacyLandscape, ind.resolveCustomRegion(2400, 1080).region)
        assertEquals(legacyPortrait, ind.resolveCustomRegion(1080, 2400).region)
        repo.setCustomRegion(landscape = true, region = null)
        assertEquals(picked, repo.settings.first().resolveCustomRegion(2400, 1080).region) // inherits linked

        // Explicit reset forgets everything; resolution is still a real region.
        repo.clearCustomRegions()
        val cleared = repo.settings.first()
        assertNull(cleared.linkedRegion)
        assertNull(cleared.customRegions.portrait)
        assertNotNull(cleared.resolveCustomRegion(1080, 2400).region)
        assertEquals(
            com.gamelingo.mobile.core.region.RegionSource.DEFAULT,
            cleared.resolveCustomRegion(1080, 2400).source,
        )

        // ---- migration under concurrency and restart ------------------------------------
        val p1 = NormalizedRegion(0.1f, 0.68f, 0.9f, 0.95f)
        val l1 = NormalizedRegion(0.3f, 0.6f, 0.7f, 0.9f)
        repo.setCustomRegion(landscape = false, region = p1)
        repo.setCustomRegion(landscape = true, region = l1)
        // Many concurrent callers with DIFFERENT orientation preferences: DataStore edits are
        // serialized, so exactly one value is chosen, it is one of the saved rectangles, and
        // every later caller leaves it unchanged (no caller can overwrite a valid linked region).
        kotlinx.coroutines.coroutineScope {
            (0 until 16).map { i ->
                kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.Default) {
                    repo.migrateLinkedRegion(preferLandscape = i % 2 == 0)
                }
            }.forEach { it.await() }
        }
        val concurrent = repo.settings.first()
        assertTrue(concurrent.linkedRegion == p1 || concurrent.linkedRegion == l1)
        assertEquals(p1, concurrent.customRegions.portrait) // nothing deleted
        assertEquals(l1, concurrent.customRegions.landscape)
        val chosen = concurrent.linkedRegion
        repo.migrateLinkedRegion(preferLandscape = true)
        repo.migrateLinkedRegion(preferLandscape = false)
        assertEquals(chosen, repo.settings.first().linkedRegion)
        // After a process restart (fresh repository) the linked region is the same and a repeat is a no-op.
        val restarted = DataStoreSettingsRepository(context, ApiKeyCipher())
        assertEquals(chosen, restarted.settings.first().linkedRegion)
        restarted.migrateLinkedRegion(preferLandscape = chosen == l1)
        restarted.migrateLinkedRegion(preferLandscape = chosen != l1)
        assertEquals(chosen, restarted.settings.first().linkedRegion)

        // Only a landscape legacy value, migrated from a portrait session: still picked (other valid side).
        repo.clearCustomRegions()
        repo.setCustomRegion(landscape = true, region = l1)
        repo.migrateLinkedRegion(preferLandscape = false)
        assertEquals(l1, repo.settings.first().linkedRegion)
        // Only a portrait legacy value, migrated from a landscape session.
        repo.clearCustomRegions()
        repo.setCustomRegion(landscape = false, region = p1)
        repo.migrateLinkedRegion(preferLandscape = true)
        assertEquals(p1, repo.settings.first().linkedRegion)
        // Nothing saved: migration invents nothing.
        repo.clearCustomRegions()
        repo.migrateLinkedRegion(preferLandscape = true)
        assertNull(repo.settings.first().linkedRegion)
    }
}
