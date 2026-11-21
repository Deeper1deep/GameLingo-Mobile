package com.gamelingo.mobile.translate.local

import com.gamelingo.mobile.translate.MlKitTranslationEngine
import com.gamelingo.mobile.translate.TranslationEngine
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.TranslationResult
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Picks the best verified local engine, in order: TranslateGemma HD (realistic
 * Egyptian) -> Marian fast (MSA + dictionary) -> ML Kit (last-resort fallback).
 * Offline mode touches the network for NOTHING: every candidate runs on-device.
 *
 * A higher engine that fails mid-session degrades visibly to the next one for
 * that line only (the history entry keeps the real engine id); a missing model
 * is a skip, not an error.
 */
@Singleton
class LocalEngineChain internal constructor(
    private val translateGemmaEngine: LocalAiCandidate,
    private val marianEngine: LocalAiCandidate,
    private val mlKitEngine: TranslationEngine,
) : TranslationEngine, LocalAiRouter {

    /** Hilt entry point: the real engines; tests use the candidate-typed primary. */
    @Inject
    constructor(
        translateGemmaEngine: TranslateGemmaEngine,
        marianEngine: LocalAiTranslationEngine,
        @Named("mlkit") mlKitEngine: TranslationEngine,
    ) : this(
        translateGemmaEngine as LocalAiCandidate,
        marianEngine as LocalAiCandidate,
        mlKitEngine,
    )

    override val engineId: String = ENGINE_ID

    /** True when at least one downloadable local AI model is verified on disk. */
    override fun hasLocalAiModel(): Boolean =
        translateGemmaEngine.isAvailable() || marianEngine.isAvailable()

    /** Engine id the next line would actually use; the cache keys on this. */
    override fun plannedLocalEngineId(): String = when {
        translateGemmaEngine.isAvailable() -> TranslateGemmaEngine.ENGINE_ID
        marianEngine.isAvailable() -> LocalAiTranslationEngine.ENGINE_ID
        else -> MlKitTranslationEngine.ENGINE_ID
    }

    override suspend fun ensureReady() = Unit

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        if (translateGemmaEngine.isAvailable()) {
            try {
                return translateGemmaEngine.translate(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Fall through to the fast engine for this line; the failure
                // stays visible through the returned engine id and worker state.
            }
        }
        if (marianEngine.isAvailable()) {
            try {
                return marianEngine.translate(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Fall through to ML Kit for this line.
            }
        }
        return mlKitEngine.translate(request)
    }

    override fun close() {
        // ML Kit is owned by its own binding and shared with the hybrid engine;
        // closing it here too would double-close the same instance.
        translateGemmaEngine.close()
        marianEngine.close()
    }

    companion object {
        const val ENGINE_ID = "local-chain"
    }
}
