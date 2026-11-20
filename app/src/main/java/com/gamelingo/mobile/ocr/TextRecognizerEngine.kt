package com.gamelingo.mobile.ocr

import com.gamelingo.mobile.core.capture.RecognizedBlock
import com.google.mlkit.vision.common.InputImage

/**
 * On-device text recognition. Screenshots never leave the device: the engine
 * receives frames and returns text only. Implementations must be closable.
 */
interface TextRecognizerEngine {
    /** Recognizes Latin (English) text in [image]; returns the full text, possibly empty. */
    suspend fun recognize(image: InputImage): String

    /**
     * Recognizes text as positioned blocks, so the pipeline can tell a moving
     * subtitle line apart from a static corner watermark. Coordinates are
     * relative to [image]; possibly empty.
     */
    suspend fun recognizeBlocks(image: InputImage): List<RecognizedBlock>

    fun close()
}
