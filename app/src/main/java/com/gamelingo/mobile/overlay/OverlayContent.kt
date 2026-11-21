package com.gamelingo.mobile.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.overlay.OverlayLayout
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.data.settings.AppSettings

/** What the floating window is showing right now. */
data class OverlayUiState(
    val original: String? = null,
    val translated: String? = null,
    val failure: String? = null,
    val sessionError: Boolean = false,
    val notice: SessionNotice? = null,
    /** True while a session runs: shows the stop affordance. */
    val sessionRunning: Boolean = false,
)

/**
 * The floating translation window's content: the Arabic translation on the
 * first line (right-to-left), the original English line below it, a failure
 * notice when the last line could not be translated, a session notice when
 * the effective engine is not the picked one, and a short waiting hint before
 * the first line. Colors, opacity and text size all come from the user's
 * overlay settings. Width wraps the content up to a screen fraction so the
 * window never claims the whole screen without need.
 */
@Composable
fun OverlayContent(
    state: OverlayUiState,
    settings: AppSettings,
    onStopSession: (() -> Unit)? = null,
) {
    val background = Color(
        OverlayLayout.backgroundWithOpacity(
            backgroundArgb = settings.overlayBackgroundColor,
            opacity = settings.overlayOpacity,
        ),
    )
    val textColor = Color(settings.overlayTextColor)
    val maxWidthDp = (LocalConfiguration.current.screenWidthDp * MAX_WIDTH_FRACTION).toInt()

    Column(
        modifier = Modifier
            .widthIn(max = maxWidthDp.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // In-session stop affordance: without it the user must leave the game
        // and find the app's stop button to end a session.
        if (state.sessionRunning && onStopSession != null) {
            Text(
                text = stringResource(R.string.overlay_stop),
                color = textColor.copy(alpha = 0.8f),
                fontSize = (settings.overlayTextSizeSp - 6).coerceAtLeast(9).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(onClickLabel = stringResource(R.string.overlay_stop_cd)) {
                        onStopSession()
                    }
                    .padding(bottom = 2.dp),
            )
        }
        when {
            state.sessionError -> Text(
                text = stringResource(R.string.overlay_error),
                color = textColor,
                fontSize = (settings.overlayTextSizeSp - 4).coerceAtLeast(10).sp,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Rtl),
            )

            state.failure != null -> Text(
                text = stringResource(R.string.home_translation_failed),
                color = textColor,
                fontSize = (settings.overlayTextSizeSp - 4).coerceAtLeast(10).sp,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Rtl),
            )

            state.translated != null -> {
                // First line: the Arabic translation, right-to-left.
                Text(
                    text = state.translated,
                    color = textColor,
                    fontSize = settings.overlayTextSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Rtl),
                )
                // Second line: the original English, left-to-right.
                if (settings.showOriginalText && !state.original.isNullOrBlank()) {
                    Text(
                        text = state.original,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = (settings.overlayTextSizeSp - 5).coerceAtLeast(10).sp,
                        textAlign = TextAlign.Center,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    )
                }
            }

            else -> Text(
                text = stringResource(R.string.overlay_waiting),
                color = textColor.copy(alpha = 0.7f),
                fontSize = (settings.overlayTextSizeSp - 5).coerceAtLeast(10).sp,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Rtl),
            )
        }

        // A session notice rides under whatever else is showing: the user
        // picked one engine and is getting another, and must know.
        state.notice?.let { notice ->
            Text(
                text = stringResource(
                    when (notice) {
                        SessionNotice.ONLINE_NOT_CONFIGURED ->
                            R.string.overlay_notice_online_not_configured
                        SessionNotice.FELL_BACK_TO_LOCAL ->
                            R.string.overlay_notice_fell_back_local
                    },
                ),
                color = textColor.copy(alpha = 0.85f),
                fontSize = (settings.overlayTextSizeSp - 6).coerceAtLeast(9).sp,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Rtl),
            )
        }
    }
}

private const val MAX_WIDTH_FRACTION = 0.92f
