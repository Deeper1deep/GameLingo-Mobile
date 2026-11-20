package com.gamelingo.mobile.core.update

/** What the app should do after reading latest.json from the updates repo. */
sealed interface UpdateDecision {

    /** Installed build is current, or the entry is not meant for this app/schema. */
    data object UpToDate : UpdateDecision

    /** A newer build exists. [mandatory] mirrors the publisher's flag in latest.json. */
    data class Available(val release: LatestRelease, val mandatory: Boolean) : UpdateDecision

    /**
     * The installed build is below the publisher's minimum supported version, so the
     * update cannot be skipped. Only emitted when the published build is actually newer;
     * a broken latest.json (latest older than the minimum) never forces an install.
     */
    data class Forced(val release: LatestRelease) : UpdateDecision
}

object UpdateDecider {

    fun decide(
        release: LatestRelease,
        installedVersionCode: Int,
        installedAppId: String,
    ): UpdateDecision = when {
        !release.isSupportedSchema || release.appId != installedAppId ->
            UpdateDecision.UpToDate
        release.isBelowMinimum(installedVersionCode) &&
            release.latestVersionCode > installedVersionCode ->
            UpdateDecision.Forced(release)
        release.isUpdateFor(installedVersionCode, installedAppId) ->
            UpdateDecision.Available(release, release.mandatoryUpdate)
        else -> UpdateDecision.UpToDate
    }
}
