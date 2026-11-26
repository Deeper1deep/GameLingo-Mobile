package com.gamelingo.mobile.core.overlay

import com.gamelingo.mobile.core.capture.RecognizedBlock
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How the translated subtitle is positioned. Independent of the OCR region. */
@Serializable
enum class PlacementMode {
    /** Stays where the user put it (or at the top/bottom anchor until they do). */
    FIXED,

    /** Sits on the detected original subtitle. */
    FOLLOW_ORIGINAL,

    /** Directly beneath the detected original subtitle. */
    BELOW_ORIGINAL,

    /** Picks a spot that does not cover the original subtitle or the scanned area. */
    SMART_AUTO,
}

/** Physical text alignment inside the translation box (needs a set width to be visible). */
@Serializable
enum class PlacementAlign { LEFT, CENTER, RIGHT }

/**
 * Where and how big the translation window is, as fractions of the screen so
 * one saved value fits any resolution or orientation. Stored separately from
 * the OCR region: nothing here is derived from, or writes to, the region.
 */
@Serializable
data class TranslationPlacement(
    val mode: PlacementMode = PlacementMode.FIXED,
    /** Fixed mode: horizontal centre of the window; null = centred. */
    val fixedX: Float? = null,
    /** Fixed mode: top edge of the window; null = the top/bottom anchor setting. */
    val fixedY: Float? = null,
    /** Extra horizontal nudge in any mode (fraction of screen width). */
    val offsetX: Float = 0f,
    /** Extra vertical nudge in any mode (fraction of screen height). */
    val offsetY: Float = 0f,
    /** Window width; null = wrap the text. */
    val widthFraction: Float? = null,
    /** Window height; null = wrap the text. */
    val heightFraction: Float? = null,
    val align: PlacementAlign = PlacementAlign.CENTER,
) {
    fun sanitized(): TranslationPlacement = copy(
        fixedX = fixedX?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        fixedY = fixedY?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        offsetX = offsetX.takeIf { it.isFinite() }?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: 0f,
        offsetY = offsetY.takeIf { it.isFinite() }?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: 0f,
        widthFraction = widthFraction?.takeIf { it.isFinite() }?.coerceIn(MIN_WIDTH, 1f),
        heightFraction = heightFraction?.takeIf { it.isFinite() }?.coerceIn(MIN_HEIGHT, 1f),
    )

    /** Position and size back to defaults; mode and alignment are kept. */
    fun positionReset(): TranslationPlacement = copy(
        fixedX = null, fixedY = null, offsetX = 0f, offsetY = 0f, widthFraction = null, heightFraction = null,
    )

    companion object {
        const val MAX_OFFSET = 0.5f
        const val MIN_WIDTH = 0.2f
        const val MIN_HEIGHT = 0.06f

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(p: TranslationPlacement): String = json.encodeToString(p.sanitized())

        /** Corrupt or unknown JSON yields the default placement, never a crash. */
        fun decodeOrDefault(raw: String?): TranslationPlacement =
            if (raw.isNullOrBlank()) TranslationPlacement()
            else runCatching { json.decodeFromString<TranslationPlacement>(raw).sanitized() }
                .getOrDefault(TranslationPlacement())
    }
}

/** A box as screen fractions (0..1). */
@Serializable
data class NormalizedBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersectionArea(o: NormalizedBox): Float {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    companion object {
        /**
         * The box of the published subtitle: the union of the OCR blocks whose
         * text is part of [publishedText] (all blocks when none match, as the
         * text may have been merged/normalised), in screen fractions.
         */
        fun ofBlocks(
            blocks: List<RecognizedBlock>,
            publishedText: String,
            frameWidth: Int,
            frameHeight: Int,
        ): NormalizedBox? {
            if (frameWidth <= 0 || frameHeight <= 0 || blocks.isEmpty()) return null
            val flat = publishedText.replace(Regex("\\s+"), " ").trim()
            val matching = blocks.filter {
                val t = it.text.replace(Regex("\\s+"), " ").trim()
                t.isNotEmpty() && flat.contains(t, ignoreCase = true)
            }.ifEmpty { blocks }
            val l = matching.minOf { it.rect.left }
            val t = matching.minOf { it.rect.top }
            val r = matching.maxOf { it.rect.right }
            val b = matching.maxOf { it.rect.bottom }
            if (r <= l || b <= t) return null
            return NormalizedBox(
                (l.toFloat() / frameWidth).coerceIn(0f, 1f),
                (t.toFloat() / frameHeight).coerceIn(0f, 1f),
                (r.toFloat() / frameWidth).coerceIn(0f, 1f),
                (b.toFloat() / frameHeight).coerceIn(0f, 1f),
            )
        }
    }
}

/** Top-left pixel of the translation window. */
data class PlacementPoint(val left: Int, val top: Int)

/** Pure placement math; no Android imports. */
object PlacementResolver {

    /**
     * @param overlayWidth/overlayHeight current size of the window in px
     * @param source the original subtitle's box, when known
     * @param ocrRegion the scanned area (Smart Auto keeps off it; never modified)
     * @param defaultAnchorTop Fixed mode with nothing saved: top or bottom edge
     * @param marginPx edge margin of the default anchor
     * @param applyOffsets add the user's nudge (false to compute the base spot)
     */
    fun resolve(
        placement: TranslationPlacement,
        screenWidth: Int,
        screenHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        source: NormalizedBox?,
        ocrRegion: NormalizedBox?,
        defaultAnchorTop: Boolean,
        marginPx: Int,
        applyOffsets: Boolean = true,
    ): PlacementPoint {
        val p = placement.sanitized()
        val gap = (screenHeight * GAP_FRACTION).toInt()

        fun centered(centerX: Float, top: Int) = Pair((centerX * screenWidth - overlayWidth / 2f).toInt(), top)

        val fixedBase = {
            val top = p.fixedY?.let { (it * screenHeight).toInt() }
                ?: if (defaultAnchorTop) marginPx else screenHeight - marginPx - overlayHeight
            centered(p.fixedX ?: 0.5f, top)
        }

        val (bx, by) = when (p.mode) {
            PlacementMode.FIXED -> fixedBase()
            PlacementMode.FOLLOW_ORIGINAL ->
                source?.let { centered(it.centerX, (it.top * screenHeight).toInt()) } ?: fixedBase()
            PlacementMode.BELOW_ORIGINAL -> source?.let {
                val below = (it.bottom * screenHeight).toInt() + gap
                if (below + overlayHeight <= screenHeight) centered(it.centerX, below)
                else centered(it.centerX, (it.top * screenHeight).toInt() - gap - overlayHeight)
            } ?: fixedBase()
            PlacementMode.SMART_AUTO -> smartAuto(
                screenWidth, screenHeight, overlayWidth, overlayHeight, source, ocrRegion,
                defaultAnchorTop, marginPx, gap,
            ) ?: fixedBase()
        }
        val x = bx + if (applyOffsets) (p.offsetX * screenWidth).toInt() else 0
        val y = by + if (applyOffsets) (p.offsetY * screenHeight).toInt() else 0
        return clamp(x, y, screenWidth, screenHeight, overlayWidth, overlayHeight)
    }

    private fun smartAuto(
        sw: Int, sh: Int, ow: Int, oh: Int,
        source: NormalizedBox?, region: NormalizedBox?,
        anchorTop: Boolean, margin: Int, gap: Int,
    ): Pair<Int, Int>? {
        if (source == null && region == null) return null
        val cx = source?.centerX ?: region?.centerX ?: 0.5f
        val left = (cx * sw - ow / 2f).toInt()
        val candidates = buildList {
            source?.let {
                add(left to (it.bottom * sh).toInt() + gap)
                add(left to (it.top * sh).toInt() - gap - oh)
            }
            region?.let {
                add(left to (it.bottom * sh).toInt() + gap)
                add(left to (it.top * sh).toInt() - gap - oh)
            }
            add(left to margin)
            add(left to sh - margin - oh)
        }.map { (x, y) ->
            val c = clamp(x, y, sw, sh, ow, oh)
            c.left to c.top
        }
        val avoid = listOfNotNull(source, region)
        fun overlap(pos: Pair<Int, Int>): Float {
            val box = NormalizedBox(
                pos.first.toFloat() / sw, pos.second.toFloat() / sh,
                (pos.first + ow).toFloat() / sw, (pos.second + oh).toFloat() / sh,
            )
            return avoid.sumOf { box.intersectionArea(it).toDouble() }.toFloat()
        }
        // First candidate that covers nothing wins; otherwise the least covering one.
        val best = candidates.firstOrNull { overlap(it) == 0f } ?: candidates.minByOrNull { overlap(it) }
        return best
    }

    fun clamp(x: Int, y: Int, sw: Int, sh: Int, ow: Int, oh: Int): PlacementPoint =
        PlacementPoint(
            x.coerceIn(0, (sw - ow).coerceAtLeast(0)),
            y.coerceIn(0, (sh - oh).coerceAtLeast(0)),
        )

    /** The placement a finished drag corresponds to, for the given mode. */
    fun afterDrag(
        placement: TranslationPlacement,
        droppedLeft: Int, droppedTop: Int,
        base: PlacementPoint,
        screenWidth: Int, screenHeight: Int, overlayWidth: Int,
    ): TranslationPlacement =
        if (placement.mode == PlacementMode.FIXED) {
            placement.copy(
                fixedX = (droppedLeft + overlayWidth / 2f) / screenWidth,
                fixedY = droppedTop.toFloat() / screenHeight,
                offsetX = 0f, offsetY = 0f,
            ).sanitized()
        } else {
            // Followed modes keep following; the drag becomes a nudge.
            placement.copy(
                offsetX = (droppedLeft - base.left).toFloat() / screenWidth,
                offsetY = (droppedTop - base.top).toFloat() / screenHeight,
            ).sanitized()
        }

    private const val GAP_FRACTION = 0.008f
}
