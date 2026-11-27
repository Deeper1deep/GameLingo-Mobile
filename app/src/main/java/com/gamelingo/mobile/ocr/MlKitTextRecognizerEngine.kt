package com.gamelingo.mobile.ocr

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import com.gamelingo.mobile.core.capture.RecognizedWord
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** ML Kit Latin text recognizer: fully on-device, no model download at runtime. */
@Singleton
class MlKitTextRecognizerEngine @Inject constructor() : TextRecognizerEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(image: InputImage): String =
        processSafely(image) { it.text }

    override suspend fun recognizeBlocks(image: InputImage): List<RecognizedBlock> =
        processSafely(image) { result ->
            result.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                RecognizedBlock(
                    text = block.text,
                    rect = PixelRect(box.left, box.top, box.right, box.bottom),
                    // ML Kit documents 0 as "unavailable" (older Play services), so zeros are not scores.
                    confidence = block.lines.map { it.confidence }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    words = block.lines.flatMap { line ->
                        line.elements.mapNotNull { element ->
                            val wb = element.boundingBox ?: return@mapNotNull null
                            RecognizedWord(element.text, PixelRect(wb.left, wb.top, wb.right, wb.bottom))
                        }
                    },
                )
            }
        }

    /**
     * Awaits the ML Kit task to full completion, even when the caller is
     * cancelled mid-flight. The native task may still be reading the image's
     * bitmap after the coroutine resumes; returning early lets the caller
     * recycle that bitmap while ML Kit reads it, which is a native
     * use-after-free. So on cancellation we first wait (uncancellably) for
     * the task to settle, then rethrow the cancellation.
     */
    private suspend fun <T> processSafely(image: InputImage, map: (Text) -> T): T {
        val task = recognizer.process(image)
        val result = try {
            suspendCancellableCoroutine<Result<T>> { continuation ->
                task.addOnSuccessListener { value ->
                    continuation.resume(Result.success(map(value)))
                }
                task.addOnFailureListener { error ->
                    continuation.resume(Result.failure(error))
                }
                task.addOnCanceledListener {
                    continuation.resumeWithException(
                        CancellationException("recognition task cancelled"),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { task.awaitSettled() }
            throw cancelled
        }
        return result.getOrThrow()
    }

    /** Resumes once the task has finished in any state (immediate when already done). */
    private suspend fun Task<*>.awaitSettled() =
        suspendCancellableCoroutine<Unit> { continuation ->
            addOnCompleteListener { continuation.resume(Unit) }
        }

    override fun close() {
        recognizer.close()
    }
}
