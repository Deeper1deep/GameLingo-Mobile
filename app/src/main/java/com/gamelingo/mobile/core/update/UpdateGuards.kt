package com.gamelingo.mobile.core.update

/** Pure install-time guards, unit-tested without Android. */
object UpdateGuards {

    /** Extra free space beyond the APK itself (temp copy + installer staging). */
    const val STORAGE_HEADROOM_BYTES = 64L * 1024 * 1024

    /**
     * The APK's own version must be exactly what latest.json promised and
     * strictly newer than the installed build: a same or older APK (replayed
     * or swapped file) is refused, whatever its checksum says.
     */
    fun versionAcceptable(apkVersionCode: Long?, releaseVersionCode: Long, installedVersionCode: Long): Boolean =
        apkVersionCode != null &&
            apkVersionCode == releaseVersionCode &&
            apkVersionCode > installedVersionCode

    /** Enough free space for the download plus headroom; unknown size needs headroom alone. */
    fun hasEnoughStorage(usableBytes: Long, apkBytes: Long?): Boolean =
        usableBytes >= (apkBytes ?: 0L) + STORAGE_HEADROOM_BYTES
}
