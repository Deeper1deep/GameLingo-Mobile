package com.gamelingo.mobile.core.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * The one place a background thread may ask for a Toast. Frame processing and
 * other service coroutines run on worker threads that have no Looper, and
 * `Toast.show()` there throws "Can't toast on a thread that has not called
 * Looper.prepare()". This always hops to the main looper, and a failure to
 * show a notice is swallowed (logged) so it can never interrupt the caller's
 * pipeline.
 */
object MainThreadNotice {

    private const val TAG = "MainThreadNotice"

    /** Test hook: how a runnable reaches the main thread. */
    internal var poster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }

    /** Safe from any thread. Never throws. */
    fun toast(context: Context, @StringRes message: Int, length: Int = Toast.LENGTH_LONG) {
        val appContext = context.applicationContext ?: context
        val show = Runnable {
            try {
                Toast.makeText(appContext, message, length).show()
            } catch (error: Exception) {
                Log.w(TAG, "notice not shown: ${error.javaClass.simpleName}")
            }
        }
        try {
            poster(show)
        } catch (error: Exception) {
            Log.w(TAG, "notice not posted: ${error.javaClass.simpleName}")
        }
    }
}
