package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.CaptureHealth
import com.gamelingo.mobile.core.session.RequestState
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.session.WorkerStatus
import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.overlay.OverlayContent
import com.gamelingo.mobile.overlay.OverlayUiState
import com.gamelingo.mobile.ui.theme.GameLingoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Real renders (Robolectric native graphics) of the batching UI; PNGs are printed between markers in the log. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class BatchBacklogRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        GameLingoTheme {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            ) { content() }
        }
    }

    private fun shot(tag: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        val view = rule.activity.window.decorView
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        assertTrue("blank render", pixels.toSet().size > 8)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        println("${tag}_BEGIN")
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP).chunked(4000).forEach { println(it) }
        println("${tag}_END")
    }

    @Test
    fun `diagnostics shows requests in the last minute, batches and lost lines`() {
        val now = System.currentTimeMillis()
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        CaptureHealth.onFrameAcquired(now - 2_000L)
        CaptureHealth.onOcrRun(now - 3_000L)
        TranslationSessionBus.onWorkerStatus(
            WorkerStatus(
                state = RequestState.RATE_LIMITED,
                cooldownUntilMillis = now + 20_000L,
                lastProviderLimit = "QUOTA_429/RESOURCE_EXHAUSTED/PerMinutePerProject/wait=20s",
                lastProviderLimitAtMillis = now - 5_000L,
                lostLines = 3, batchesSent = 4, batchedLines = 17, onlineRequestsLastMinute = 9, queueDepth = 12,
            ),
        )
        themed { DiagnosticsScreen(liveClock = false) }
        rule.onNodeWithText(ctx.getString(R.string.diag_lost_lines_fmt, 3)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.diag_batch_fmt, 4, 17)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.diag_requests_minute_fmt, 9)).performScrollTo().assertIsDisplayed()
        shot("BATCH_DIAG_PNG")
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onWorkerStatus(WorkerStatus())
    }

    // SYNTHETIC FIXTURE for layout only: invented text, NOT a real model output and NOT an accepted
    // source-to-translation mapping. The Arabic and English rows below are unrelated on purpose.
    private val longArabic = "أنا مش هسيبك تعدّي من هنا يا صاحبي، لو فكرت إنك تقدر تهرب بعد كل اللي حصل ده تبقى بتحلم، " +
        "والمرة دي مفيش حد هيلحقك ولا حد هيسمع صوتك قدام الباب الكبير ده"

    @Test
    fun `history shows a translated row, a long Arabic row and saved originals with their reasons`() {
        val rows = listOf(
            TranslationHistoryEntity(1, "Get down now", longArabic, engine = "gemini-flash-lite", createdAt = 1_000),
            TranslationHistoryEntity(2, "Watch out, they are right behind us and there is no way out of this place", "",
                engine = "none", createdAt = 2_000, status = TranslationHistoryEntity.untranslatedStatus("overflow")),
            TranslationHistoryEntity(3, "I never asked for any of this.", "",
                engine = "none", createdAt = 3_000, status = TranslationHistoryEntity.untranslatedStatus("expired")),
            TranslationHistoryEntity(4, "Hold the door!", "",
                engine = "none", createdAt = 4_000, status = TranslationHistoryEntity.untranslatedStatus("stopped")),
        )
        themed {
            Column(Modifier.padding(16.dp)) {
                rows.forEach { com.gamelingo.mobile.ui.components.GlCard(onClick = {}) { HistoryEntryTexts(it) } }
            }
        }
        rule.onNodeWithText(ctx.getString(R.string.history_untranslated_overflow)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.history_untranslated_expired)).assertIsDisplayed()
        rule.onNodeWithText("Hold the door!").assertIsDisplayed()
        shot("BATCH_HISTORY_PNG")
    }

    @Test
    fun `overlay shows the waiting state and the lost lines notice`() {
        themed {
            Column(Modifier.padding(16.dp)) {
                OverlayContent(
                    state = OverlayUiState(original = "Get down now", rateLimitWaiting = true, sessionRunning = true),
                    settings = AppSettings(),
                )
                OverlayContent(
                    state = OverlayUiState(original = "Watch out", translated = longArabic,
                        notice = SessionNotice.TRANSLATION_LINES_LOST, sessionRunning = true),
                    settings = AppSettings(),
                )
            }
        }
        rule.onNodeWithText(ctx.getString(R.string.overlay_notice_lines_lost)).assertIsDisplayed()
        shot("BATCH_OVERLAY_PNG")
    }
}
