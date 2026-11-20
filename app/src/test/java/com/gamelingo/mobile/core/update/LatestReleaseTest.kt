package com.gamelingo.mobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleaseTest {

    private val sampleJson = """
        {
          "schemaVersion": 1,
          "appId": "com.gamelingo.mobile",
          "latestVersion": "1.2.0",
          "latestVersionCode": 12,
          "minimumSupportedVersionCode": 5,
          "releaseDate": "2026-11-20",
          "changelog": "إصلاحات وتحسينات",
          "downloadUrl": "https://github.com/Deeper1deep/GameLingo-Updates/releases/download/v1.2.0/gamelingo-1.2.0.apk",
          "sha256": "abc123",
          "mandatoryUpdate": false
        }
    """.trimIndent()

    @Test
    fun `parses a valid latest json`() {
        val release = LatestRelease.parse(sampleJson)
        assertEquals(1, release.schemaVersion)
        assertEquals("com.gamelingo.mobile", release.appId)
        assertEquals("1.2.0", release.latestVersion)
        assertEquals(12, release.latestVersionCode)
        assertEquals(5, release.minimumSupportedVersionCode)
        assertFalse(release.mandatoryUpdate)
    }

    @Test
    fun `ignores unknown future fields`() {
        val withExtra = sampleJson.replace("{", "{ \"futureField\": \"x\",")
        val release = LatestRelease.parse(withExtra)
        assertEquals(12, release.latestVersionCode)
    }

    @Test
    fun `mandatoryUpdate defaults to false when missing`() {
        val withoutFlag = sampleJson.replace(Regex(",\\s*\"mandatoryUpdate\": false"), "")
        assertFalse(LatestRelease.parse(withoutFlag).mandatoryUpdate)
    }

    @Test
    fun `detects update for older installed version with same app id`() {
        val release = LatestRelease.parse(sampleJson)
        assertTrue(release.isUpdateFor(installedVersionCode = 11, installedAppId = "com.gamelingo.mobile"))
        assertFalse(release.isUpdateFor(installedVersionCode = 12, installedAppId = "com.gamelingo.mobile"))
        assertFalse(release.isUpdateFor(installedVersionCode = 1, installedAppId = "com.other.app"))
    }

    @Test
    fun `detects installs below minimum supported version`() {
        val release = LatestRelease.parse(sampleJson)
        assertTrue(release.isBelowMinimum(4))
        assertFalse(release.isBelowMinimum(5))
    }

    @Test
    fun `rejects schemas newer than the app understands`() {
        val future = sampleJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val release = LatestRelease.parse(future)
        assertFalse(release.isSupportedSchema)
        assertFalse(release.isUpdateFor(1, "com.gamelingo.mobile"))
    }
}
