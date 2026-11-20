package com.gamelingo.mobile.ocr

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** ML Kit Latin text recognizer: fully on-device, no model download at runtime. */
@Singleton
class MlKitTextRecognizerEngine @Inject constructor() : TextRecognizerEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    override suspend fun recognizeBlocks(image: InputImage): List<RecognizedBlock> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    continuation.resume(
                        result.textBlocks.mapNotNull { block ->
                            val box = block.boundingBox ?: return@mapNotNull null
                            RecognizedBlock(
                                text = block.text,
                                rect = PixelRect(box.left, box.top, box.right, box.bottom),
                            )
                        },
                    )
                }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    override fun close() {
        recognizer.close()
    }
}
