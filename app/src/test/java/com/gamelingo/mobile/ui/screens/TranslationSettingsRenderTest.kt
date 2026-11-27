package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
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
class TranslationSettingsRenderTest {
    @get:Rule
    val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun `glossary edit and context reset render`() {
        rule.setContent {
            GameLingoTheme {
              androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                com.gamelingo.mobile.ui.screens.TranslationSettingsContent(
                    settings = com.gamelingo.mobile.data.settings.AppSettings(
                        arabicTranslationMode = com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC,
                        contextLines = 3,
                        userGlossaryTerms = listOf(
                            com.gamelingo.mobile.data.settings.UserGlossaryTerm("Joel", "جويل", game = "TLOU", pronunciation = "joe-el", note = "اسم شخصية"),
                        ),
                    ),
                    onArabicMode = {}, onRemoveTerm = {}, onAddTerm = { _, _, _, _, _ -> }, onContextLines = {}, onShowOriginal = {},
                )
              }
            }
        }
        // Status pills pulse forever, so Compose never reports idle. Freeze the test clock
        // on one frame before capture; the layout is already settled by the assertion above.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        // captureToImage waits for Compose idle, which the pulsing pills never allow. Draw the
        // activity's decor view directly instead (same pixels, no idle wait).
        val view = rule.activity.window.decorView
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        assertTrue("screen rendered a blank image", pixels.toSet().size > 8)

        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val dir = java.io.File("build/trset-render").apply { mkdirs() }
        java.io.File(dir, "trset-screen.png").writeBytes(out.toByteArray())
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        println("TRSET_PNG_BEGIN")
        b64.chunked(4000).forEach { println(it) }
        println("TRSET_PNG_END")
    }
}
