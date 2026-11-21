package com.gamelingo.mobile.data.settings

/**
 * Arabic style every translation targets. One setting, read once per
 * request by the coordinator, honored by every engine and fallback:
 * prompt-capable engines get a mode-appropriate prompt, on-device engines
 * get the matching post-processing, and the cache keys on it so the two
 * modes never share an entry.
 */
enum class ArabicTranslationMode {
    /** Modern Standard Arabic: natural, correct, clear; no Egyptian colloquialisms. */
    MODERN_STANDARD_ARABIC,

    /** Natural spoken Egyptian (the project's original behavior; the default). */
    EGYPTIAN_ARABIC,
}

/** Engine selection for the hybrid translator. */
enum class AiMode {
    /** Try the online provider first, fall back to the local engine. */
    AUTO,

    /** Online provider only; fail visibly when there is no connection. */
    ONLINE,

    /** On-device engine only; never touches the network. */
    LOCAL,
}

/** Preset screen regions the OCR pass can be restricted to. */
enum class TextRegionPreset {
    /** OCR reads the whole frame. */
    FULL_SCREEN,

    /** OCR reads the lower third, where game subtitles usually sit. */
    LOWER_THIRD,

    /** OCR reads a user-defined vertical band. */
    CUSTOM,
}

/** Default anchor for the floating translation window; the user can drag it afterwards. */
enum class OverlayPosition {
    TOP,
    BOTTOM,
}

/** Vertical band of the screen, as fractions of total height (0 = top edge, 1 = bottom edge). */
data class TextRegionFractions(
    val top: Float,
    val bottom: Float,
) {
    val span: Float get() = bottom - top
}

/**
 * Pure bounds and defaults for every persisted setting. All DataStore writes and
 * UI sliders go through these clamps so an out-of-range value can never be stored.
 */
object SettingsConstraints {
    // 600ms keeps subtitles responsive: with the stability tracker asking for
    // two similar reads, a new line reaches the translator about 1.2s after it
    // appears, instead of 2s+ at the old 1s default.
    const val DEFAULT_CAPTURE_INTERVAL_MS = 600L
    const val MIN_CAPTURE_INTERVAL_MS = 300L
    const val MAX_CAPTURE_INTERVAL_MS = 3_000L

    const val DEFAULT_OVERLAY_OPACITY = 0.85f
    const val MIN_OVERLAY_OPACITY = 0.2f
    const val MAX_OVERLAY_OPACITY = 1f

    const val DEFAULT_OVERLAY_TEXT_SIZE_SP = 18
    const val MIN_OVERLAY_TEXT_SIZE_SP = 12
    const val MAX_OVERLAY_TEXT_SIZE_SP = 30

    const val DEFAULT_TIMEOUT_SEC = 20
    const val MIN_TIMEOUT_SEC = 5
    const val MAX_TIMEOUT_SEC = 60

    const val DEFAULT_CONTEXT_LINES = 5
    const val MIN_CONTEXT_LINES = 0
    const val MAX_CONTEXT_LINES = 20

    /** Default custom region: the lower subtitle band. */
    const val DEFAULT_REGION_TOP = 0.60f
    const val DEFAULT_REGION_BOTTOM = 0.98f

    /** Smallest useful OCR band, as a fraction of screen height. */
    const val MIN_REGION_SPAN = 0.05f

    val FULL_SCREEN_REGION = TextRegionFractions(top = 0f, bottom = 1f)
    val LOWER_THIRD_REGION = TextRegionFractions(
        top = DEFAULT_REGION_TOP,
        bottom = DEFAULT_REGION_BOTTOM,
    )

    fun clampCaptureIntervalMs(value: Long): Long =
        value.coerceIn(MIN_CAPTURE_INTERVAL_MS, MAX_CAPTURE_INTERVAL_MS)

    fun clampOverlayOpacity(value: Float): Float =
        value.coerceIn(MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY)

    fun clampOverlayTextSizeSp(value: Int): Int =
        value.coerceIn(MIN_OVERLAY_TEXT_SIZE_SP, MAX_OVERLAY_TEXT_SIZE_SP)

    fun clampTimeoutSec(value: Int): Int =
        value.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    fun clampContextLines(value: Int): Int =
        value.coerceIn(MIN_CONTEXT_LINES, MAX_CONTEXT_LINES)

    /** Clamps the custom region so 0 <= top < bottom <= 1 with at least [MIN_REGION_SPAN] height. */
    fun clampRegionFractions(top: Float, bottom: Float): TextRegionFractions {
        val clampedTop = top.coerceIn(0f, 1f - MIN_REGION_SPAN)
        val clampedBottom = bottom.coerceIn(clampedTop + MIN_REGION_SPAN, 1f)
        return TextRegionFractions(top = clampedTop, bottom = clampedBottom)
    }

    /** Resolves the effective OCR band for a preset, applying clamps to custom values. */
    fun resolveRegion(
        preset: TextRegionPreset,
        customTop: Float,
        customBottom: Float,
    ): TextRegionFractions = when (preset) {
        TextRegionPreset.FULL_SCREEN -> FULL_SCREEN_REGION
        TextRegionPreset.LOWER_THIRD -> LOWER_THIRD_REGION
        TextRegionPreset.CUSTOM -> clampRegionFractions(customTop, customBottom)
    }
}

/** Safe enum parsing for persisted settings: unknown values fall back to the default. */
inline fun <reified T : Enum<T>> parseEnumOrDefault(name: String?, default: T): T =
    name
        ?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() }
        ?: default
