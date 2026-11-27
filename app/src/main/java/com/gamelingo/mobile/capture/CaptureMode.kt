package com.gamelingo.mobile.capture

import android.content.Context
import android.os.Build

/**
 * What the system asks the user to share (Android 14+).
 *
 * Default (restored): the normal system picker, which offers "share one app" and
 * "share entire screen" as in v0.8.4 and earlier. The picker's result cannot be proven to be
 * the whole display (app sharing also excludes system bars, and the platform reports no
 * origin), so in this mode the translation area is drawn on a snapshot of what is actually
 * captured and read as fractions of that frame. The saved whole-display area is never read as
 * frame fractions and never deleted.
 *
 * Switched off ("whole screen only"): the app requests the default display, which is provably
 * the full display, and the saved whole-display area applies as in v0.8.5.
 * Stored on its own so no existing setting or saved region changes.
 */
object CaptureMode {
    private const val FILE = "capture_mode"
    private const val KEY = "app_window"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** What the user chose. */
    fun appWindowChosen(context: Context): Boolean = prefs(context).getBoolean(KEY, true)

    fun setAppWindowChosen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    /** App-window capture is real only where the platform offers it. */
    fun appWindowActive(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && appWindowChosen(context)
}

/** Which consent the app asks the system for. Pure, so the launch path is unit-tested. */
object CaptureIntentPolicy {
    /** True only when the app must request the default display (whole screen, provable). */
    fun requestsDefaultDisplayOnly(sdkInt: Int, pickerChosen: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !pickerChosen
}
