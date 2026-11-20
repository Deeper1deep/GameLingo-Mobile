package com.gamelingo.mobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestReleaseValidationTest {

    private fun release(
        sha256: String = "a".repeat(64),
        downloadUrl: String =
            "https://github.com/Deeper1deep/GameLingo-Updates/releases/download/v1.0.0/app.apk",
        appId: String = "com.gamelingo.mobile",
    ) = LatestRelease(
        schemaVersion = 1,
        appId = appId,
        latestVersion = "1.0.0",
        latestVersionCode = 7,
        minimumSupportedVersionCode = 1,
        releaseDate = "2026-11-20",
        changelog = "تغييرات",
        downloadUrl = downloadUrl,
        sha256 = sha256,
        mandatoryUpdate = false,
    )

    @Test
    fun `a well formed official manifest validates`() {
        assertNull(release().validationError())
        // Uppercase hex is accepted too.
        assertNull(release(sha256 = "A".repeat(64)).validationError())
    }

    @Test
    fun `a short or non hex sha256 is rejected`() {
        assertEquals(ReleaseValidationError.BAD_SHA256, release(sha256 = "abc").validationError())
        assertEquals(
            ReleaseValidationError.BAD_SHA256,
            release(sha256 = "g".repeat(64)).validationError(),
        )
    }

    @Test
    fun `a download url outside the official repo is rejected`() {
        assertEquals(
            ReleaseValidationError.UNTRUSTED_DOWNLOAD_URL,
            release(downloadUrl = "https://evil.example.com/app.apk").validationError(),
        )
        assertEquals(
            ReleaseValidationError.UNTRUSTED_DOWNLOAD_URL,
            release(
                downloadUrl = "http://github.com/Deeper1deep/GameLingo-Updates/releases/download/v1/app.apk",
            ).validationError(),
        )
    }

    @Test
    fun `an empty app id is rejected`() {
        assertEquals(ReleaseValidationError.BAD_IDENTITY, release(appId = "").validationError())
    }
}
