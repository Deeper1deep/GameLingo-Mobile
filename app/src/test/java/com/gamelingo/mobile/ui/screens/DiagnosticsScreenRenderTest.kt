package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.CaptureHealth
import com.gamelingo.mobile.core.session.RequestState
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.session.WorkerStatus
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
    val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

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
        val dir = java.io.File("build/diagnostics-render").apply { mkdirs() }
        java.io.File(dir, "diagnostics-screen.png").writeBytes(out.toByteArray())
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        println("DIAG_PNG_BEGIN")
        b64.chunked(4000).forEach { println(it) }
        println("DIAG_PNG_END")
    }

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        GameLingoTheme {
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            ) { content() }
        }
    }

    private fun printPixels(tag: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        val view = rule.activity.window.decorView
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        println("${tag}_BEGIN")
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP).chunked(4000).forEach { println(it) }
        println("${tag}_END")
    }

    private fun textMatching(regex: Regex) = SemanticsMatcher("text matches $regex") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { regex.containsMatchIn(it.text) } == true
    }

    @Test
    fun `a rate limit shows as request state, cooldown, last success and API error while capture and OCR rows stay live and separate`() {
        val now = System.currentTimeMillis()
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        CaptureHealth.onFrameAcquired(now - 2_000L)
        CaptureHealth.onOcrRun(now - 3_000L)
        TranslationSessionBus.onWorkerStatus(
            WorkerStatus(
                state = RequestState.RATE_LIMITED,
                lastSuccessAtMillis = now - 20_000L,
                lastError = "QUOTA_429/RESOURCE_EXHAUSTED",
                cooldownUntilMillis = now + 3_600_000L,
            ),
        )
        themed { DiagnosticsScreen(liveClock = false) }
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Request state, cooldown, last success and the API error each have their own row.
        rule.onNodeWithText("RATE_LIMITED").performScrollTo().assertIsDisplayed()
        rule.onNode(textMatching(Regex("^(35\\d\\d|3600) ثانية$"))).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.diag_last_error_fmt, "QUOTA_429/RESOURCE_EXHAUSTED"))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.diag_cooldown)).performScrollTo().assertIsDisplayed()
        // Capture and OCR carry their own timestamps, unaffected by the translation state.
        rule.onNodeWithText(ctx.getString(R.string.diag_last_frame)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.diag_last_ocr)).performScrollTo().assertIsDisplayed()
        rule.onAllNodes(textMatching(Regex("^من [0-9]+ ثانية$"))).assertCountEquals(3)
        printPixels("DIAG_RL_PNG")
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onWorkerStatus(WorkerStatus())
    }

    @Test
    fun `an indefinite block shows its own cooldown text`() {
        val now = System.currentTimeMillis()
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        CaptureHealth.onFrameAcquired(now - 1_000L)
        CaptureHealth.onOcrRun(now - 2_000L)
        TranslationSessionBus.onWorkerStatus(
            WorkerStatus(state = RequestState.RATE_LIMITED, lastError = "QUOTA_429", cooldownUntilMillis = Long.MAX_VALUE),
        )
        themed { DiagnosticsScreen(liveClock = false) }
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        rule.onNodeWithText(ctx.getString(R.string.diag_cooldown_blocked)).performScrollTo().assertIsDisplayed()
        printPixels("DIAG_BLOCK_PNG")
        TranslationSessionBus.onWorkerStatus(WorkerStatus())
        TranslationSessionBus.onSessionStopped()
    }
}
