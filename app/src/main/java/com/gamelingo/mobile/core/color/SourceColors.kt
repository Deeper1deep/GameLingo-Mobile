package com.gamelingo.mobile.core.color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One recognized word and the colour its glyphs were drawn in (ARGB). */
data class SourceWordColor(val word: String, val argb: Int)

/** A colour applied to [start, end) of a displayed string. */
data class ColorSpan(val start: Int, val end: Int, val argb: Int)

/**
 * Local, pixel-level text colour detection. Runs on the already-captured
 * frame; nothing leaves the device and no extra model is involved.
 */
object SourceColorDetector {

    private const val MIN_FOREGROUND_FRACTION = 0.06
    private const val MIN_FOREGROUND_PIXELS = 4
    private const val FOREGROUND_DISTANCE = 70.0
    private const val MIN_SATURATION = 0.35f
    private const val MIN_VALUE = 0.35f

    /**
     * The dominant glyph colour inside a word box, or null when it cannot be
     * told confidently (flat area, no clear foreground, too few pixels).
     * The background is estimated from the box border and excluded, so a
     * coloured background is never mistaken for coloured text.
     *
     * @param pixels row-major ARGB, [width] x [height]
     */
    fun dominantTextColor(pixels: IntArray, width: Int, height: Int): Int? {
        if (width < 2 || height < 2 || pixels.size < width * height) return null
        val background = estimateBackground(pixels, width, height)
        val bgR = background shr 16 and 0xFF
        val bgG = background shr 8 and 0xFF
        val bgB = background and 0xFF

        // Histogram of foreground candidates in 4-bit-per-channel buckets.
        val counts = HashMap<Int, IntArray>() // bucket -> [n, sumR, sumG, sumB]
        var candidates = 0
        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val dr = r - bgR
            val dg = g - bgG
            val db = b - bgB
            if (sqrt((dr * dr + dg * dg + db * db).toDouble()) < FOREGROUND_DISTANCE) continue
            candidates++
            val bucket = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            val acc = counts.getOrPut(bucket) { IntArray(4) }
            acc[0]++; acc[1] += r; acc[2] += g; acc[3] += b
        }
        val total = width * height
        if (candidates < MIN_FOREGROUND_PIXELS || candidates < total * MIN_FOREGROUND_FRACTION) return null
        val best = counts.values.maxByOrNull { it[0] } ?: return null
        val n = best[0]
        return (0xFF shl 24) or ((best[1] / n) shl 16) or ((best[2] / n) shl 8) or (best[3] / n)
    }

    /** Most common colour along the box border (text rarely touches it). */
    private fun estimateBackground(pixels: IntArray, w: Int, h: Int): Int {
        val counts = HashMap<Int, IntArray>()
        fun add(p: Int) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            val acc = counts.getOrPut(key) { IntArray(4) }
            acc[0]++; acc[1] += r; acc[2] += g; acc[3] += b
        }
        for (x in 0 until w) { add(pixels[x]); add(pixels[(h - 1) * w + x]) }
        for (y in 1 until h - 1) { add(pixels[y * w]); add(pixels[y * w + w - 1]) }
        val best = counts.values.maxBy { it[0] }
        val n = best[0]
        return (best[1] / n shl 16) or (best[2] / n shl 8) or (best[3] / n)
    }

    /**
     * True for a colour worth preserving. Near-white, grey and near-black are
     * the default subtitle look and stay in the user's chosen text colour.
     */
    fun isChromatic(argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val saturation = if (mx == 0f) 0f else (mx - mn) / mx
        return saturation >= MIN_SATURATION && mx >= MIN_VALUE
    }
}

/** Maps detected word colours onto displayed strings. Pure. */
object ColorAligner {

    private val TOKEN = Regex("[\\p{L}\\p{N}'’-]+")

    private fun norm(word: String) = word.lowercase().trim('\'', '’', '-')

    /**
     * Spans for the ORIGINAL (English) line: each coloured word is painted at
     * its occurrence, in order, so a repeated word consumes one detection per
     * appearance.
     */
    fun forOriginal(original: String, colored: List<SourceWordColor>): List<ColorSpan> {
        if (colored.isEmpty()) return emptyList()
        val pool = colored.groupBy { norm(it.word) }.mapValues { it.value.toMutableList() }
        val spans = mutableListOf<ColorSpan>()
        for (m in TOKEN.findAll(original)) {
            val hit = pool[norm(m.value)]?.removeFirstOrNull() ?: continue
            spans += ColorSpan(m.range.first, m.range.last + 1, hit.argb)
        }
        return spans
    }

    /**
     * Spans for the ARABIC line, only where the match is certain:
     *  - [lookup] (user glossary / pinned names) gives the Arabic rendering of
     *    the coloured English word and that rendering occurs in [translated]; or
     *  - a Latin name kept as-is in the translation (e.g. "Ellie").
     * Everything else stays in the single text colour: a guessed alignment
     * would paint the wrong Arabic word.
     */
    fun forTranslation(
        original: String,
        translated: String,
        colored: List<SourceWordColor>,
        lookup: (String) -> String?,
    ): List<ColorSpan> {
        if (colored.isEmpty() || translated.isBlank()) return emptyList()
        val spans = mutableListOf<ColorSpan>()
        val taken = BooleanArray(translated.length)
        for (c in colored.distinctBy { norm(it.word) }) {
            if (!original.contains(c.word, ignoreCase = true)) continue
            val candidates = listOfNotNull(lookup(norm(c.word)), c.word.takeIf { it.first().isUpperCase() })
            for (target in candidates) {
                if (target.isBlank()) continue
                val at = translated.indexOf(target, ignoreCase = true)
                if (at < 0) continue
                val end = at + target.length
                if ((at until end).any { taken[it] }) continue
                for (i in at until end) taken[i] = true
                spans += ColorSpan(at, end, c.argb)
                break
            }
        }
        return spans.sortedBy { it.start }
    }
}
