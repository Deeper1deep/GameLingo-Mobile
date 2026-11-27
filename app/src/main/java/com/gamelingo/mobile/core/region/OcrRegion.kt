package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rectangle as fractions of the screen (0..1). Fractions, not pixels, so the
 * same saved region fits any resolution, density or display size.
 */
data class NormalizedRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when finite, inside the unit square, ordered and not degenerate. */
    fun isValid(): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() } &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            width >= MIN_SIZE && height >= MIN_SIZE

    /**
     * The region forced into a valid shape, or null when it cannot be
     * trusted (NaN, inverted, or collapsed beyond repair). A null result
     * means "fall back to the full screen", never a crash.
     */
    fun sanitizedOrNull(): NormalizedRegion? {
        if (!listOf(left, top, right, bottom).all { it.isFinite() }) return null
        val l = min(left, right).coerceIn(0f, 1f)
        val r = max(left, right).coerceIn(0f, 1f)
        val t = min(top, bottom).coerceIn(0f, 1f)
        val b = max(top, bottom).coerceIn(0f, 1f)
        val fixed = NormalizedRegion(l, t, r, b)
        return fixed.takeIf { it.isValid() }
    }

    /** Pixel rectangle inside a [frameWidth] x [frameHeight] frame; always at least 1px and inside the frame. */
    fun toPixels(frameWidth: Int, frameHeight: Int): PixelRect {
        require(frameWidth > 0 && frameHeight > 0) { "frame must have positive dimensions" }
        val l = (left * frameWidth).roundToInt().coerceIn(0, frameWidth - 1)
        val t = (top * frameHeight).roundToInt().coerceIn(0, frameHeight - 1)
        val r = (right * frameWidth).roundToInt().coerceIn(l + 1, frameWidth)
        val b = (bottom * frameHeight).roundToInt().coerceIn(t + 1, frameHeight)
        return PixelRect(l, t, r, b)
    }

    /** "l,t,r,b" for DataStore. */
    fun encode(): String = "$left,$top,$right,$bottom"

    companion object {
        const val MIN_SIZE = 0.05f
        val FULL = NormalizedRegion(0f, 0f, 1f, 1f)

        fun fromPixels(rect: PixelRect, frameWidth: Int, frameHeight: Int): NormalizedRegion? {
            if (frameWidth <= 0 || frameHeight <= 0) return null
            return NormalizedRegion(
                rect.left.toFloat() / frameWidth,
                rect.top.toFloat() / frameHeight,
                rect.right.toFloat() / frameWidth,
                rect.bottom.toFloat() / frameHeight,
            ).sanitizedOrNull()
        }

        /** Corrupt or partial stored text yields null (full screen), never an exception. */
        fun decodeOrNull(raw: String?): NormalizedRegion? {
            if (raw.isNullOrBlank()) return null
            val p = raw.split(',').map { it.trim().toFloatOrNull() ?: return null }
            if (p.size != 4) return null
            return NormalizedRegion(p[0], p[1], p[2], p[3]).sanitizedOrNull()
        }

        /**
         * Maps a rectangle measured inside the selector window onto the screen
         * fractions. The window may not start at the screen origin (status bar,
         * cutout), so its on-screen offset is added before normalizing.
         */
        fun fromWindowRect(
            localLeft: Float, localTop: Float, localRight: Float, localBottom: Float,
            windowOffsetX: Float, windowOffsetY: Float,
            screenWidth: Int, screenHeight: Int,
        ): NormalizedRegion? {
            if (screenWidth <= 0 || screenHeight <= 0) return null
            return NormalizedRegion(
                (localLeft + windowOffsetX) / screenWidth,
                (localTop + windowOffsetY) / screenHeight,
                (localRight + windowOffsetX) / screenWidth,
                (localBottom + windowOffsetY) / screenHeight,
            ).sanitizedOrNull()
        }
    }
}

/**
 * One region per orientation. A rectangle drawn on a portrait screen means
 * nothing on the rotated screen, so each orientation keeps its own; an
 * orientation with no region of its own reads the full screen.
 */
data class OrientedRegions(
    val portrait: NormalizedRegion? = null,
    val landscape: NormalizedRegion? = null,
) {
    fun forFrame(frameWidth: Int, frameHeight: Int): NormalizedRegion? =
        (if (frameWidth > frameHeight) landscape else portrait)?.sanitizedOrNull()

    fun with(frameWidth: Int, frameHeight: Int, region: NormalizedRegion?): OrientedRegions =
        if (frameWidth > frameHeight) copy(landscape = region) else copy(portrait = region)
}

/** How much of a text block must lie inside the region for it to count. */
enum class RegionSensitivity(val minOverlap: Float) {
    /** Almost fully inside. */
    STRICT(0.9f),

    /** More than half inside: the default. */
    BALANCED(0.6f),

    /** A third is enough. */
    LENIENT(0.33f),
}

/** Decides which OCR blocks belong to the selected region. */
object RegionFilter {

    /** Fraction of [block]'s own area inside [region] (0 for an empty block). */
    fun overlapFraction(block: PixelRect, region: PixelRect): Float {
        val area = block.width.toLong() * block.height
        if (area <= 0) return 0f
        val w = min(block.right, region.right) - max(block.left, region.left)
        val h = min(block.bottom, region.bottom) - max(block.top, region.top)
        if (w <= 0 || h <= 0) return 0f
        return (w.toLong() * h).toFloat() / area
    }

    fun accepts(block: PixelRect, region: PixelRect, sensitivity: RegionSensitivity): Boolean =
        overlapFraction(block, region) >= sensitivity.minOverlap

    /**
     * Keeps the blocks that belong to the region, in their original order and
     * unmerged: unrelated text elements are never combined here.
     */
    fun filter(
        blocks: List<RecognizedBlock>,
        region: PixelRect,
        sensitivity: RegionSensitivity,
    ): List<RecognizedBlock> = blocks.filter { accepts(it.rect, region, sensitivity) }

    /** Accepted and rejected split, for the live preview. */
    fun partition(
        blocks: List<RecognizedBlock>,
        region: PixelRect,
        sensitivity: RegionSensitivity,
    ): Pair<List<RecognizedBlock>, List<RecognizedBlock>> =
        blocks.partition { accepts(it.rect, region, sensitivity) }
}

/** Which part of the rectangle a touch grabbed. */
enum class RegionHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/** Pure move/resize logic of the selector; the UI only forwards touches. */
object RegionEditor {

    /**
     * What a touch at ([x], [y]) grabs. Corners win over the body so a small
     * rectangle stays resizable; [slop] is the corner touch radius in the
     * same unit as the coordinates.
     */
    fun hitTest(r: NormalizedRegion, x: Float, y: Float, slop: Float): RegionHandle {
        fun near(cx: Float, cy: Float) = kotlin.math.abs(x - cx) <= slop && kotlin.math.abs(y - cy) <= slop
        return when {
            near(r.left, r.top) -> RegionHandle.TOP_LEFT
            near(r.right, r.top) -> RegionHandle.TOP_RIGHT
            near(r.left, r.bottom) -> RegionHandle.BOTTOM_LEFT
            near(r.right, r.bottom) -> RegionHandle.BOTTOM_RIGHT
            x in r.left..r.right && y in r.top..r.bottom -> RegionHandle.MOVE
            else -> RegionHandle.NONE
        }
    }

    /** Moves the whole rectangle by ([dx], [dy]) fractions, keeping its size and staying on screen. */
    fun move(r: NormalizedRegion, dx: Float, dy: Float): NormalizedRegion {
        val w = r.width
        val h = r.height
        val l = (r.left + dx).coerceIn(0f, 1f - w)
        val t = (r.top + dy).coerceIn(0f, 1f - h)
        return NormalizedRegion(l, t, l + w, t + h)
    }

    /** Drags one corner to ([x], [y]); the opposite corner stays fixed and the minimum size holds. */
    fun resize(r: NormalizedRegion, handle: RegionHandle, x: Float, y: Float): NormalizedRegion {
        val min = NormalizedRegion.MIN_SIZE
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        return when (handle) {
            RegionHandle.TOP_LEFT -> NormalizedRegion(
                cx.coerceAtMost(r.right - min), cy.coerceAtMost(r.bottom - min), r.right, r.bottom,
            )
            RegionHandle.TOP_RIGHT -> NormalizedRegion(
                r.left, cy.coerceAtMost(r.bottom - min), cx.coerceAtLeast(r.left + min), r.bottom,
            )
            RegionHandle.BOTTOM_LEFT -> NormalizedRegion(
                cx.coerceAtMost(r.right - min), r.top, r.right, cy.coerceAtLeast(r.top + min),
            )
            RegionHandle.BOTTOM_RIGHT -> NormalizedRegion(
                r.left, r.top, cx.coerceAtLeast(r.left + min), cy.coerceAtLeast(r.top + min),
            )
            else -> r
        }
    }

    /** A sensible first rectangle: the lower third band, where subtitles usually sit. */
    val DEFAULT_START = NormalizedRegion(0.10f, 0.68f, 0.90f, 0.95f)
}

/** Where the active region came from; reported in diagnostics. */
enum class RegionSource {
    /** The single linked region used in every orientation (the default). */
    LINKED,

    /** Independent mode: this orientation's own rectangle. */
    ORIENTATION_OVERRIDE,

    /** No linked region saved yet: an older per-orientation rectangle, used in both orientations. */
    LEGACY_SAVED,

    /** Nothing valid saved: the safe default band. */
    DEFAULT,
}

data class ResolvedRegion(val region: NormalizedRegion, val source: RegionSource)

/**
 * Decides which rectangle is active. It never returns null: custom region
 * mode must always scan a real, valid rectangle and can never fall back to
 * the full screen. Rotation changes only the pixel size the same fractions
 * are applied to, never which rectangle is used (unless the user switched
 * on independent regions per orientation).
 */
object RegionResolver {

    fun resolve(
        linked: NormalizedRegion?,
        perOrientation: OrientedRegions,
        independent: Boolean,
        frameWidth: Int,
        frameHeight: Int,
    ): ResolvedRegion {
        if (independent) {
            perOrientation.forFrame(frameWidth, frameHeight)
                ?.let { return ResolvedRegion(it, RegionSource.ORIENTATION_OVERRIDE) }
        }
        linked?.sanitizedOrNull()?.let { return ResolvedRegion(it, RegionSource.LINKED) }
        // Settings saved before the linked model: a fixed, orientation-free
        // choice, so rotating never swaps one rectangle for another.
        migrationCandidate(perOrientation, preferLandscape = false)
            ?.let { return ResolvedRegion(it, RegionSource.LEGACY_SAVED) }
        return ResolvedRegion(RegionEditor.DEFAULT_START, RegionSource.DEFAULT)
    }

    /**
     * The saved rectangle that becomes the linked one: the active
     * orientation's when valid, else the other, else null. Never invents a region.
     */
    fun migrationCandidate(perOrientation: OrientedRegions, preferLandscape: Boolean): NormalizedRegion? {
        val first = if (preferLandscape) perOrientation.landscape else perOrientation.portrait
        val second = if (preferLandscape) perOrientation.portrait else perOrientation.landscape
        return first?.sanitizedOrNull() ?: second?.sanitizedOrNull()
    }
}
