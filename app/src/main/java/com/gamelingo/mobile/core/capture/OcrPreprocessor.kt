package com.gamelingo.mobile.core.capture

/**
 * Pure OCR preprocessing options on grayscale pixel buffers (no Android types, so
 * they are unit-testable). Each option is applied ONLY when [chooseHelpful] finds a
 * measured separation gain on that very frame; otherwise the original is kept.
 * Not wired into capture by default: whether any option helps real game frames is
 * unmeasured and needs real frames.
 */
class Gray(val width: Int, val height: Int, val px: IntArray) {
    init { require(px.size == width * height) }
    operator fun get(x: Int, y: Int) = px[y * width + x]
}

object OcrPreprocessor {
    enum class Option { GRAYSCALE_ONLY, CONTRAST_STRETCH, SHARPEN, SCALE_2X, THRESHOLD }

    /** ARGB ints to 0..255 luma. */
    fun toGray(width: Int, height: Int, argb: IntArray): Gray = Gray(
        width, height,
        IntArray(argb.size) {
            val c = argb[it]
            ((0.299 * ((c shr 16) and 255)) + (0.587 * ((c shr 8) and 255)) + (0.114 * (c and 255))).toInt()
        },
    )

    fun contrastStretch(g: Gray): Gray {
        val lo = g.px.min(); val hi = g.px.max()
        if (hi - lo < 8) return g
        return Gray(g.width, g.height, IntArray(g.px.size) { (g.px[it] - lo) * 255 / (hi - lo) })
    }

    fun sharpen(g: Gray): Gray {
        val out = g.px.copyOf()
        for (y in 1 until g.height - 1) for (x in 1 until g.width - 1) {
            val v = 5 * g[x, y] - g[x - 1, y] - g[x + 1, y] - g[x, y - 1] - g[x, y + 1]
            out[y * g.width + x] = v.coerceIn(0, 255)
        }
        return Gray(g.width, g.height, out)
    }

    /** Bilinear upscale by an integer [factor] (2..4). */
    fun scale(g: Gray, factor: Int): Gray {
        require(factor in 2..4)
        val w = g.width * factor; val h = g.height * factor
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x.toFloat() / factor; val fy = y.toFloat() / factor
            val x0 = fx.toInt().coerceAtMost(g.width - 1); val y0 = fy.toInt().coerceAtMost(g.height - 1)
            val x1 = (x0 + 1).coerceAtMost(g.width - 1); val y1 = (y0 + 1).coerceAtMost(g.height - 1)
            val ax = fx - x0; val ay = fy - y0
            val top = g[x0, y0] * (1 - ax) + g[x1, y0] * ax
            val bot = g[x0, y1] * (1 - ax) + g[x1, y1] * ax
            out[y * w + x] = (top * (1 - ay) + bot * ay).toInt()
        }
        return Gray(w, h, out)
    }

    /** Otsu global threshold to pure black/white. */
    fun threshold(g: Gray): Gray {
        val hist = IntArray(256); g.px.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = g.px.size
        var sum = 0L; for (i in 0..255) sum += i.toLong() * hist[i]
        var wB = 0; var sumB = 0L; var best = 0.0; var t = 127
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB; val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return Gray(g.width, g.height, IntArray(g.px.size) { if (g.px[it] > t) 255 else 0 })
    }

    fun apply(option: Option, g: Gray): Gray = when (option) {
        Option.GRAYSCALE_ONLY -> g
        Option.CONTRAST_STRETCH -> contrastStretch(g)
        Option.SHARPEN -> sharpen(g)
        Option.SCALE_2X -> scale(g, 2)
        Option.THRESHOLD -> threshold(g)
    }

    /**
     * Separation score: the fraction of pixels that sit clearly at one of the two
     * extremes of the frame's own range (bimodality). Higher = text is easier to
     * separate from the background. Scale-independent.
     */
    fun separation(g: Gray): Double {
        val lo = g.px.min(); val hi = g.px.max()
        val range = hi - lo
        if (range < 8) return 0.0
        val band = range / 5
        val extreme = g.px.count { it <= lo + band || it >= hi - band }
        return extreme.toDouble() / g.px.size * (range / 255.0)
    }

    data class Comparison(val option: Option, val before: Double, val after: Double) {
        val gain get() = after - before
    }

    fun compare(g: Gray, option: Option) = Comparison(option, separation(g), separation(apply(option, g)))

    /** Options whose measured gain on THIS frame is at least [minGain], best first. Empty = keep the frame as is. */
    fun chooseHelpful(g: Gray, minGain: Double = 0.05): List<Comparison> =
        Option.entries.filter { it != Option.GRAYSCALE_ONLY }
            .map { compare(g, it) }.filter { it.gain >= minGain }.sortedByDescending { it.gain }
}
