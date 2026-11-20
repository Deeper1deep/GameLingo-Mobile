package com.gamelingo.mobile.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why a latest.json manifest must not be trusted; mapped to an Arabic UI string. */
enum class ReleaseValidationError {
    /** sha256 missing or not 64 hex characters. */
    BAD_SHA256,

    /** downloadUrl not HTTPS or not the official updates repo release path. */
    UNTRUSTED_DOWNLOAD_URL,

    /** appId or version fields empty/invalid. */
    BAD_IDENTITY,
}

/**
 * Mirror of the latest.json file published in the GameLingo-Updates repo.
 * Unknown fields are ignored so the schema can grow without breaking old
 * installs.
 */
@Serializable
data class LatestRelease(
    val schemaVersion: Int,
    val appId: String,
    val latestVersion: String,
    val latestVersionCode: Int,
    val minimumSupportedVersionCode: Int,
    val releaseDate: String,
    val changelog: String,
    val downloadUrl: String,
    val sha256: String,
    val mandatoryUpdate: Boolean = false,
    /** Optional APK size; older/latest.json without it fall back to a HEAD request. */
    val fileSizeBytes: Long? = null,
) {
    /** Older installs only understand schemas up to [CURRENT_SCHEMA]. */
    val isSupportedSchema: Boolean
        get() = schemaVersion <= CURRENT_SCHEMA

    fun isUpdateFor(installedVersionCode: Int, installedAppId: String): Boolean =
        isSupportedSchema &&
            appId == installedAppId &&
            latestVersionCode > installedVersionCode

    fun isBelowMinimum(installedVersionCode: Int): Boolean =
        installedVersionCode < minimumSupportedVersionCode

    /**
     * Structural validation of the manifest itself, independent of the
     * installed app: a manifest that fails this is metadata garbage and must
     * never reach the download path, whatever it claims about versions.
     */
    fun validationError(): ReleaseValidationError? {
        if (appId.isBlank() || latestVersion.isBlank() ||
            latestVersionCode <= 0 || minimumSupportedVersionCode < 0
        ) {
            return ReleaseValidationError.BAD_IDENTITY
        }
        if (!SHA256_HEX.matches(sha256)) return ReleaseValidationError.BAD_SHA256
        if (!downloadUrl.startsWith(OFFICIAL_DOWNLOAD_PREFIX)) {
            return ReleaseValidationError.UNTRUSTED_DOWNLOAD_URL
        }
        return null
    }

    companion object {
        const val CURRENT_SCHEMA = 1

        /**
         * The only place an APK may come from: HTTPS releases of the official
         * GameLingo-Updates repository.
         */
        const val OFFICIAL_DOWNLOAD_PREFIX =
            "https://github.com/Deeper1deep/GameLingo-Updates/releases/download/"

        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): LatestRelease = json.decodeFromString(raw)
    }
}
