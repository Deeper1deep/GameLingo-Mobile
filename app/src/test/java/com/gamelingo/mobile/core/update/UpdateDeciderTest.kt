package com.gamelingo.mobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDeciderTest {

    private fun release(
        latestCode: Int = 5,
        minCode: Int = 1,
        appId: String = APP_ID,
        schema: Int = 1,
        mandatory: Boolean = false,
    ) = LatestRelease(
        schemaVersion = schema,
        appId = appId,
        latestVersion = "1.0.$latestCode",
        latestVersionCode = latestCode,
        minimumSupportedVersionCode = minCode,
        releaseDate = "2026-11-20",
        changelog = "تغييرات",
        downloadUrl = "https://example.com/app.apk",
        sha256 = "abc",
        mandatoryUpdate = mandatory,
    )

    @Test
    fun `up to date when codes match`() {
        assertEquals(
            UpdateDecision.UpToDate,
            UpdateDecider.decide(release(latestCode = 5), 5, APP_ID),
        )
    }

    @Test
    fun `available when newer and not mandatory`() {
        val decision = UpdateDecider.decide(release(latestCode = 6), 5, APP_ID)
        assertTrue(decision is UpdateDecision.Available)
        assertEquals(false, (decision as UpdateDecision.Available).mandatory)
    }

    @Test
    fun `available carries the publisher mandatory flag`() {
        val decision = UpdateDecider.decide(release(latestCode = 6, mandatory = true), 5, APP_ID)
        assertTrue(decision is UpdateDecision.Available)
        assertEquals(true, (decision as UpdateDecision.Available).mandatory)
    }

    @Test
    fun `forced when installed is below the publisher minimum`() {
        val decision = UpdateDecider.decide(release(latestCode = 6, minCode = 5), 4, APP_ID)
        assertTrue(decision is UpdateDecision.Forced)
    }

    @Test
    fun `forced requires a genuinely newer build`() {
        // Broken metadata (latest older than the minimum) must not force an install loop.
        val decision = UpdateDecider.decide(release(latestCode = 3, minCode = 5), 4, APP_ID)
        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `ignores entries for another app id`() {
        val decision = UpdateDecider.decide(release(appId = "com.other.app"), 1, APP_ID)
        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `ignores schemas newer than understood`() {
        val decision = UpdateDecider.decide(release(schema = 99), 1, APP_ID)
        assertEquals(UpdateDecision.UpToDate, decision)
    }

    private companion object {
        const val APP_ID = "com.gamelingo.mobile"
    }
}
