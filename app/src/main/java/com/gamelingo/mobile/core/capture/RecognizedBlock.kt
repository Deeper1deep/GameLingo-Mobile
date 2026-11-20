package com.gamelingo.mobile.core.capture

/** One block of recognized text with its position inside the captured frame. */
data class RecognizedBlock(
    val text: String,
    val rect: PixelRect,
)

/** @return this rectangle moved by [dx]/[dy] pixels. */
fun PixelRect.translated(dx: Int, dy: Int): PixelRect =
    PixelRect(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)
