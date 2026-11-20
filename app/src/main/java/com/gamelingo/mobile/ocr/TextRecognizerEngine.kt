package com.gamelingo.mobile.ocr

import com.google.mlkit.vision.common.InputImage

/**
 * On-device text recognition. Screenshots never leave the device: the engine
 * receives frames and returns text only. Implementations must be closable.
 */
interface TextRecognizerEngine {
    /** Recognizes Latin (English) text in [image]; returns the full text, possibly empty. */
    suspend fun recognize(image: InputImage): String

    fun close()
}
