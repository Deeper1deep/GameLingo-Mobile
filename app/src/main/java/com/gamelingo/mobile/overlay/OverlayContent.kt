package com.gamelingo.mobile.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.overlay.OverlayLayout
import com.gamelingo.mobile.data.settings.AppSettings

/** What the floating window is showing right now. */
data class OverlayUiState(
    val original: String? = null,
    val translated: String? = null,
    val failure: String? = null,
    val sessionError: Boolean = false,
)

/**
 * The floating translation window's content: the latest translated line,
 * optionally the original above it, a failure notice when the last line could
 * not be translated, and a short waiting hint before the first line. Colors,
 * opacity and text size all come from the user's overlay settings.
 */
@Composable
fun OverlayContent(
    state: OverlayUiState,
    settings: AppSettings,
) {
    val background = Color(
        OverlayLayout.backgroundWithOpacity(
            backgroundArgb = settings.overlayBackgroundColor,
            opacity = settings.overlayOpacity,
        ),
    )
    val textColor = Color(settings.overlayTextColor)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.sessionError -> Text(
                text = stringResource(R.string.overlay_error),
                color = textColor,
                fontSize = (settings.overlayTextSizeSp - 4).coerceAtLeast(10).sp,
                textAlign = TextAlign.Center,
            )

            state.failure != null -> Text(
                text = stringResource(R.string.home_translation_failed),
                color = textColor,
                fontSize = (settings.overlayTextSizeSp - 4).coerceAtLeast(10).sp,
                textAlign = TextAlign.Center,
            )

            state.translated != null -> {
                if (settings.showOriginalText && !state.original.isNullOrBlank()) {
                    Text(
                        text = state.original,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = (settings.overlayTextSizeSp - 5).coerceAtLeast(10).sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = state.translated,
                    color = textColor,
                    fontSize = settings.overlayTextSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Text(
                text = stringResource(R.string.overlay_waiting),
                color = textColor.copy(alpha = 0.7f),
                fontSize = (settings.overlayTextSizeSp - 5).coerceAtLeast(10).sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
