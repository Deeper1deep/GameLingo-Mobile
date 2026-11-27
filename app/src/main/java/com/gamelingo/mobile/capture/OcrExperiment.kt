package com.gamelingo.mobile.capture

import android.content.Context
import android.graphics.Bitmap
import com.gamelingo.mobile.core.capture.OcrPreprocessor

/**
 * EXPERIMENTAL, user-chosen, OFF by default. Preprocesses only the copy that is fed
 * to ML Kit; the captured frame, diagnostics and everything else keep the original.
 * Nothing here is evidence that it improves OCR on real game frames: it is a switch
 * for the user to try. Only same-size options are offered so box coordinates never change.
 * [OFF] is the reset and the default; with OFF the OCR input is the very same bitmap as before.
 */
object OcrExperiment {
    enum class Mode(val option: OcrPreprocessor.Option?) {
        OFF(null),
        CONTRAST(OcrPreprocessor.Option.CONTRAST_STRETCH),
        SHARPEN(OcrPreprocessor.Option.SHARPEN),
        THRESHOLD(OcrPreprocessor.Option.THRESHOLD),
    }

    private const val FILE = "ocr_experiment"
    private const val KEY = "mode"

    fun get(context: Context): Mode =
        runCatching { Mode.valueOf(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: "OFF") }
            .getOrDefault(Mode.OFF)

    fun set(context: Context, mode: Mode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }

    fun reset(context: Context) = set(context, Mode.OFF)

    /** Returns [src] itself for OFF; otherwise a NEW bitmap of the same size (caller recycles it, never [src]). */
    fun prepare(src: Bitmap, mode: Mode): Bitmap {
        val option = mode.option ?: return src
        val w = src.width; val h = src.height
        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)
        val out = OcrPreprocessor.apply(option, OcrPreprocessor.toGray(w, h, argb))
        val res = IntArray(w * h) { val v = out.px[it].coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888)
    }
}
