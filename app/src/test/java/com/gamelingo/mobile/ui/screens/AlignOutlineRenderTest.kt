package com.gamelingo.mobile.ui.screens

import com.gamelingo.mobile.overlay.AlignOutline
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Renders the real alignment outline (hint, Confirm, Cancel) over a stand-in "game" background. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AlignOutlineRenderTest {
    @get:Rule
    val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun `the alignment outline renders with hint and buttons`() {
        var confirmed = false
        val activity = rule.activity
        val host = android.widget.FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.rgb(40, 60, 40)) }
        val (frame, _) = AlignOutline.build(activity, onConfirm = { confirmed = true }, onCancel = {})
        // a window of 2/3 the display height, offset from the top, as the user would drag it
        host.addView(frame, android.widget.FrameLayout.LayoutParams(900, 1500).apply { topMargin = 300; leftMargin = 150 })
        activity.setContentView(host)
        rule.waitForIdle()
        val view = activity.window.decorView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(view.width.coerceAtLeast(1233), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(view.height.coerceAtLeast(2673), android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val w = view.width.coerceAtLeast(1); val h = view.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        assertTrue("blank render", pixels.toSet().size > 8)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        println("ALIGN_PNG_BEGIN"); b64.chunked(4000).forEach { println(it) }; println("ALIGN_PNG_END")
    }
}
