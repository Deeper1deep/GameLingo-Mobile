package com.gamelingo.mobile.capture

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the launch path: the normal picker is the default again; whole screen only is an explicit switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CaptureLaunchPathTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun `a fresh install or an existing one without the setting gets the system picker`() {
        ctx.getSharedPreferences("capture_mode", 0).edit().clear().commit()
        assertTrue(CaptureMode.appWindowChosen(ctx))
        assertTrue(CaptureMode.appWindowActive(ctx))
        assertFalse(CaptureIntentPolicy.requestsDefaultDisplayOnly(35, CaptureMode.appWindowChosen(ctx)))
    }

    @Test fun `whole screen only is an explicit choice and requests the default display on Android 14 and up`() {
        CaptureMode.setAppWindowChosen(ctx, false)
        assertFalse(CaptureMode.appWindowActive(ctx))
        assertTrue(CaptureIntentPolicy.requestsDefaultDisplayOnly(34, false))
        assertTrue(CaptureIntentPolicy.requestsDefaultDisplayOnly(35, false))
        CaptureMode.setAppWindowChosen(ctx, true)
        assertTrue(CaptureMode.appWindowActive(ctx))
    }

    @Test fun `before Android 14 there is no picker choice and the plain consent is used`() {
        assertFalse(CaptureIntentPolicy.requestsDefaultDisplayOnly(33, false))
        assertFalse(CaptureIntentPolicy.requestsDefaultDisplayOnly(33, true))
        assertEquals(false, CaptureIntentPolicy.requestsDefaultDisplayOnly(30, false))
    }
}
