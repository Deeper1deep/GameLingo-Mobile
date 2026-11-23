package com.gamelingo.mobile.overlay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

/** What the floating window is showing right now. */
data class OverlayUiState(
    val original: String? = null,
    val translated: String? = null,
    val failure: String? = null,
    val sessionError: Boolean = false,
    val notice: SessionNotice? = null,
    /** True while a session runs: shows the stop affordance. */
    val sessionRunning: Boolean = false,
    /**
     * The last translation was hidden because its source text left the
     * screen. Nothing is drawn except the small stop affordance: no stale
     * line, no waiting hint over the game.
     */
    val cleared: Boolean = false,
    /** Source-word colours for this line; empty = single text colour. */
    val sourceColors: List<com.gamelingo.mobile.core.color.SourceWordColor> = emptyList(),
    /** Arabic renderings the user pinned, used to colour the translation only when certain. */
    val glossary: Map<String, String> = emptyMap(),
)

/** Builds a string with per-range colours; ranges are clamped and applied in order. */
internal fun coloredText(
    text: String,
    spans: List<com.gamelingo.mobile.core.color.ColorSpan>,
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end > start) {
                addStyle(
                    androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(span.argb)),
                    start,
                    end,
                )
            }
        }
    }

/**
 * The floating translation window's content: the Arabic translation on the
 * first line (right-to-left), the original English line below it, a failure
 * notice when the last line could not be translated, a session notice when
 * the effective engine is not the picked one, and a short waiting hint before
 * the first line. Colors, opacity and text size all come from the user's
 * overlay settings. Width wraps the content up to a screen fraction so the
 * window never claims the whole screen without need.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val style = settings.overlayStyle
    val maxWidthDp = (LocalConfiguration.current.screenWidthDp * MAX_WIDTH_FRACTION).toInt()

    // Stop gesture lives on the subtitle bar itself. It needs a long press,
    // not one tap: stray gameplay touches reach this bar, and one tap ending
    // the session is exactly how accidental stops happen.
    val stoppable = state.sessionRunning && onStopSession != null
    var showStopHint by remember { mutableStateOf(false) }
    if (showStopHint) {
        LaunchedEffect(Unit) {
            delay(STOP_HINT_MILLIS)
            showStopHint = false
        }
    }
    val stopClickLabel = stringResource(R.string.overlay_stop_hint_cd)
    val stopLongClickLabel = stringResource(R.string.overlay_stop_cd)

    Column(
        modifier = Modifier
            .then(
                if (stoppable) {
                    Modifier.combinedClickable(
                        onClickLabel = stopClickLabel,
                        onLongClickLabel = stopLongClickLabel,
                        onLongClick = { onStopSession?.invoke() },
                        onClick = { showStopHint = true },
                    )
                } else {
                    Modifier
                },
            )
            .widthIn(max = maxWidthDp.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(
                if (state.cleared && state.notice == null) Color.Transparent else background,
                RoundedCornerShape(style.cornerRadiusDp.dp),
            )
            .padding(
                horizontal = (style.paddingDp + 6).dp,
                vertical = if (state.cleared) 2.dp else style.paddingDp.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A tap on the bar only explains the stop gesture, briefly and only
        // on demand: no permanent label sits next to the subtitle.
        if (showStopHint) {
            Text(
                text = stringResource(R.string.overlay_stop_hint),
                color = textColor.copy(alpha = 0.8f),
                fontSize = (settings.overlayTextSizeSp - 6).coerceAtLeast(9).sp,
                textAlign = TextAlign.Center,
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
                val keepColors = settings.preserveSourceColors && state.sourceColors.isNotEmpty()
                val arabicSpans = if (keepColors) {
                    com.gamelingo.mobile.core.color.ColorAligner.forTranslation(
                        original = state.original.orEmpty(),
                        translated = state.translated,
                        colored = state.sourceColors,
                        lookup = { state.glossary[it] },
                    )
                } else {
                    emptyList()
                }
                StyledText(
                    text = coloredText(state.translated, arabicSpans),
                    color = textColor,
                    fontSizeSp = settings.overlayTextSizeSp,
                    bold = style.bold,
                    direction = TextDirection.Rtl,
                    style = style,
                )
                // Second line: the original English, left-to-right.
                if (settings.showOriginalText && !state.original.isNullOrBlank()) {
                    val originalSpans = if (keepColors) {
                        com.gamelingo.mobile.core.color.ColorAligner.forOriginal(
                            state.original, state.sourceColors,
                        )
                    } else {
                        emptyList()
                    }
                    StyledText(
                        text = coloredText(state.original, originalSpans),
                        color = textColor.copy(alpha = 0.7f),
                        fontSizeSp = (settings.overlayTextSizeSp - 5).coerceAtLeast(10),
                        bold = false,
                        direction = TextDirection.Ltr,
                        style = style,
                    )
                }
            }

            state.cleared -> Unit

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
private const val STOP_HINT_MILLIS = 2_500L

private fun fontFamilyOf(font: com.gamelingo.mobile.core.overlay.OverlayFont) = when (font) {
    com.gamelingo.mobile.core.overlay.OverlayFont.DEFAULT -> androidx.compose.ui.text.font.FontFamily.Default
    com.gamelingo.mobile.core.overlay.OverlayFont.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
    com.gamelingo.mobile.core.overlay.OverlayFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
    com.gamelingo.mobile.core.overlay.OverlayFont.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
    com.gamelingo.mobile.core.overlay.OverlayFont.CURSIVE -> androidx.compose.ui.text.font.FontFamily.Cursive
}

/**
 * Subtitle text with the user's typography, optional outline (a stroked copy
 * drawn under the fill, so thin glyph parts stay readable) and shadow.
 */
@Composable
internal fun StyledText(
    text: androidx.compose.ui.text.AnnotatedString,
    color: Color,
    fontSizeSp: Int,
    bold: Boolean,
    direction: TextDirection,
    style: com.gamelingo.mobile.core.overlay.OverlayStyle,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val base = LocalTextStyle.current.copy(
        fontFamily = fontFamilyOf(style.font),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = fontSizeSp.sp,
        letterSpacing = style.letterSpacingSp.sp,
        lineHeight = (fontSizeSp * style.lineHeightMultiplier).sp,
        textAlign = TextAlign.Center,
        textDirection = direction,
        shadow = if (style.shadowEnabled) {
            androidx.compose.ui.graphics.Shadow(
                color = Color.Black.copy(alpha = 0.85f),
                offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                blurRadius = 4f,
            )
        } else {
            null
        },
    )
    androidx.compose.foundation.layout.Box {
        if (style.outlineWidthDp > 0f) {
            Text(
                text = text.text,
                color = Color(style.outlineColor),
                style = base.copy(
                    shadow = null,
                    drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = with(density) { (style.outlineWidthDp * 2f).dp.toPx() },
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                ),
            )
        }
        Text(text = text, color = color, style = base)
    }
}
