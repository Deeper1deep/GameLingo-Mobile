package com.gamelingo.mobile.capture

import android.content.Context
import android.os.Build

/**
 * User choice between whole-screen capture (default, unchanged) and capturing
 * one app window (Android 14+). Stored on its own so no existing setting,
 * default or saved region changes.
 */
object CaptureMode {
    private const val FILE = "capture_mode"
    private const val KEY = "app_window"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** What the user chose. */
    fun appWindowChosen(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setAppWindowChosen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    /** App-window capture is real only where the platform offers it. */
    fun appWindowActive(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && appWindowChosen(context)
}
