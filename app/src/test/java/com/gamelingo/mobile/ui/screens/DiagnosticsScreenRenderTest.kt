package com.gamelingo.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.theme.GameLingoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real DiagnosticsScreen with Robolectric native graphics (no emulator) and
 * proves the share control is on screen. The rendered PNG is written to build/ and also
 * printed as base64 between markers so it can be recovered from the CI log when artifact
 * upload is unavailable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DiagnosticsScreenRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the geometry share button renders on the diagnostics screen`() {
        val label = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getString(R.string.diag_geometry_export)
        // The live once-per-second clock never lets Compose go idle under test, so it is off here.
        rule.setContent { GameLingoTheme { DiagnosticsScreen(liveClock = false) } }
        rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()

        // Status pills pulse forever, so Compose never reports idle. Freeze the test clock
        // on one frame before capture; the layout is already settled by the assertion above.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        val image = rule.onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        assertTrue("screen rendered a blank image", pixels.toSet().size > 8)

        val bitmap = android.graphics.Bitmap.createBitmap(image.width, image.height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val dir = java.io.File("build/diagnostics-render").apply { mkdirs() }
        java.io.File(dir, "diagnostics-screen.png").writeBytes(out.toByteArray())
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        println("DIAG_PNG_BEGIN")
        b64.chunked(4000).forEach { println(it) }
        println("DIAG_PNG_END")
    }
}
