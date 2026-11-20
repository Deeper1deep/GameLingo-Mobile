package com.gamelingo.mobile.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Free on-device English-to-Arabic translation via ML Kit. Output is standard
 * Arabic (not full Egyptian colloquial), which is why it is the offline
 * fallback rather than the primary engine. The ~30MB model downloads once on
 * first use and then works fully offline.
 */
@Singleton
class MlKitTranslationEngine @Inject constructor() : TranslationEngine {

    override val engineId: String = ENGINE_ID

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build(),
    )

    @Volatile
    private var modelReady = false

    /** Downloads the on-device model if needed. Requires internet the first time. */
    override suspend fun ensureReady() {
        if (modelReady) return
        suspendCancellableCoroutine<Unit> { continuation ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    modelReady = true
                    continuation.resume(Unit)
                }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        ensureReady()
        val translated = suspendCancellableCoroutine<String> { continuation ->
            translator.translate(request.text)
                .addOnSuccessListener { text -> continuation.resume(text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
        return TranslationResult(translatedText = translated.trim(), engineId = engineId)
    }

    /** Whether the model is already on the device (no download needed). */
    suspend fun isModelDownloaded(): Boolean {
        val manager = RemoteModelManager.getInstance()
        return suspendCancellableCoroutine { continuation ->
            manager.getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    continuation.resume(models.any { it.language == TranslateLanguage.ARABIC })
                }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    override fun close() {
        translator.close()
    }

    companion object {
        const val ENGINE_ID = "mlkit-ondevice"
    }
}
