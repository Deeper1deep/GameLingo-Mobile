package com.gamelingo.mobile.core.ui

import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.R
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Toast requests from a thread without a Looper must reach the main thread and never throw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MainThreadNoticeTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val original = MainThreadNotice.poster

    @After fun restore() { MainThreadNotice.poster = original }

    @Test fun `toast requested from a background thread is posted to the main side and shown there`() {
        val posted = AtomicReference<Runnable?>(null)
        MainThreadNotice.poster = { posted.set(it) }
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try { MainThreadNotice.toast(ctx, R.string.region_saved) } catch (t: Throwable) { failure.set(t) }
        }
        worker.start()
        worker.join()
        assertNull("the background caller must not throw", failure.get())
        // Nothing was shown on the worker thread itself.
        assertNull(ShadowToast.getLatestToast())
        assertNotNull(posted.get())
        posted.get()!!.run()
        assertEquals(ctx.getString(R.string.region_saved), ShadowToast.getTextOfLatestToast())
    }

    @Test fun `a failure while showing or posting a notice never reaches the caller`() {
        MainThreadNotice.poster = { it.run() }
        // An id that does not exist makes Toast.makeText throw inside the runnable.
        MainThreadNotice.toast(ctx, 0x7f7f7f7f)
        MainThreadNotice.poster = { throw IllegalStateException("looper gone") }
        MainThreadNotice.toast(ctx, R.string.region_saved)
    }

    @Test fun `the default poster never touches the calling thread's looper`() {
        MainThreadNotice.poster = original
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try { MainThreadNotice.toast(ctx, R.string.region_saved) } catch (t: Throwable) { failure.set(t) }
        }
        worker.start()
        worker.join()
        assertNull(failure.get())
    }
}
