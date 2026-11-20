package com.gamelingo.mobile.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    companion object {
        const val CURRENT_SCHEMA = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): LatestRelease = json.decodeFromString(raw)
    }
}
