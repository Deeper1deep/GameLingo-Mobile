package com.gamelingo.mobile.core.overlay

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * System font families only. No font files are bundled, so there is no
 * licensing exposure; every family below ships with Android and has Arabic
 * coverage through the platform's fallback.
 */
@Serializable
enum class OverlayFont { DEFAULT, SANS, SERIF, MONO, CURSIVE }

/** Look of the subtitle text and its box, beyond colour/size/opacity. */
@Serializable
data class OverlayStyle(
    val font: OverlayFont = OverlayFont.DEFAULT,
    val bold: Boolean = true,
    val letterSpacingSp: Float = 0f,
    val lineHeightMultiplier: Float = 1.25f,
    val outlineWidthDp: Float = 0f,
    val outlineColor: Long = 0xFF000000,
    val shadowEnabled: Boolean = false,
    val cornerRadiusDp: Int = 12,
    val paddingDp: Int = 10,
    /** Locks dragging only; the stop control always stays usable. */
    val positionLocked: Boolean = false,
    /** Longest translation shown, in lines; 0 = no limit (wraps fully). */
    val maxLines: Int = 0,
) {
    fun maxLinesOrUnlimited(): Int = if (maxLines <= 0) Int.MAX_VALUE else maxLines

    /** Values forced into their safe ranges (also repairs corrupt stored JSON). */
    fun sanitized() = copy(
        letterSpacingSp = letterSpacingSp.coerceIn(MIN_SPACING, MAX_SPACING),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
        outlineWidthDp = outlineWidthDp.coerceIn(0f, MAX_OUTLINE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(0, MAX_RADIUS),
        paddingDp = paddingDp.coerceIn(MIN_PADDING, MAX_PADDING),
        maxLines = maxLines.coerceIn(0, MAX_LINES),
    )

    companion object {
        const val MIN_SPACING = -1f
        const val MAX_SPACING = 4f
        const val MIN_LINE_HEIGHT = 1.0f
        const val MAX_LINE_HEIGHT = 2.0f
        const val MAX_OUTLINE = 4f
        const val MAX_RADIUS = 28
        const val MIN_PADDING = 4
        const val MAX_PADDING = 24
        const val MAX_LINES = 6

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(style: OverlayStyle): String = json.encodeToString(style.sanitized())

        /** Corrupt or unknown JSON yields the default style, never a crash. */
        fun decodeOrDefault(raw: String?): OverlayStyle =
            if (raw.isNullOrBlank()) OverlayStyle()
            else runCatching { json.decodeFromString<OverlayStyle>(raw).sanitized() }.getOrDefault(OverlayStyle())
    }
}

/** Everything the editor changes at once; presets and the custom preset are of this type. */
@Serializable
data class OverlayAppearance(
    val style: OverlayStyle = OverlayStyle(),
    val textColor: Long,
    val backgroundColor: Long,
    val opacity: Float,
    val textSizeSp: Int,
) {
    fun sanitized() = copy(
        style = style.sanitized(),
        opacity = opacity.coerceIn(0.2f, 1f),
        textSizeSp = textSizeSp.coerceIn(12, 30),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(a: OverlayAppearance): String = json.encodeToString(a.sanitized())

        fun decodeOrNull(raw: String?): OverlayAppearance? =
            if (raw.isNullOrBlank()) null
            else runCatching { json.decodeFromString<OverlayAppearance>(raw).sanitized() }.getOrNull()
    }
}

enum class OverlayPresetId { CLASSIC, CINEMATIC, NEON, HIGH_CONTRAST, MINIMAL, ANIME, SOFT_GLASS }

/** The seven built-in looks. */
object OverlayPresets {
    private const val WHITE = 0xFFFFFFFFL
    private const val BLACK = 0xFF000000L

    fun appearance(id: OverlayPresetId): OverlayAppearance = when (id) {
        OverlayPresetId.CLASSIC -> OverlayAppearance(
            style = OverlayStyle(),
            textColor = WHITE, backgroundColor = BLACK, opacity = 0.85f, textSizeSp = 18,
        )
        OverlayPresetId.CINEMATIC -> OverlayAppearance(
            style = OverlayStyle(font = OverlayFont.SERIF, bold = false, outlineWidthDp = 1f, shadowEnabled = true, cornerRadiusDp = 4, lineHeightMultiplier = 1.35f),
            textColor = 0xFFFFF3C4, backgroundColor = BLACK, opacity = 0.35f, textSizeSp = 20,
        )
        OverlayPresetId.NEON -> OverlayAppearance(
            style = OverlayStyle(font = OverlayFont.SANS, outlineWidthDp = 1.5f, outlineColor = 0xFF6A00FF, shadowEnabled = true, cornerRadiusDp = 16, letterSpacingSp = 0.5f),
            textColor = 0xFF39FFB6, backgroundColor = 0xFF0B0620, opacity = 0.8f, textSizeSp = 19,
        )
        OverlayPresetId.HIGH_CONTRAST -> OverlayAppearance(
            style = OverlayStyle(outlineWidthDp = 2f, cornerRadiusDp = 6, paddingDp = 12),
            textColor = 0xFFFFFF00, backgroundColor = BLACK, opacity = 1f, textSizeSp = 22,
        )
        OverlayPresetId.MINIMAL -> OverlayAppearance(
            style = OverlayStyle(bold = false, cornerRadiusDp = 0, paddingDp = 6, shadowEnabled = true),
            textColor = WHITE, backgroundColor = BLACK, opacity = 0.2f, textSizeSp = 16,
        )
        OverlayPresetId.ANIME -> OverlayAppearance(
            style = OverlayStyle(font = OverlayFont.CURSIVE, outlineWidthDp = 2.5f, outlineColor = 0xFF1B1B3A, cornerRadiusDp = 20),
            textColor = 0xFFFFFFFF, backgroundColor = 0xFF1B1B3A, opacity = 0.55f, textSizeSp = 20,
        )
        OverlayPresetId.SOFT_GLASS -> OverlayAppearance(
            style = OverlayStyle(bold = false, cornerRadiusDp = 24, paddingDp = 14, letterSpacingSp = 0.2f),
            textColor = 0xFFF2F6FF, backgroundColor = 0xFF3A4A66, opacity = 0.55f, textSizeSp = 18,
        )
    }
}
