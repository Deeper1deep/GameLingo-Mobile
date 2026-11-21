package com.gamelingo.mobile.translate.local

import com.gamelingo.mobile.translate.TranslationEngine
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.TranslationResult

/**
 * The offline-first routing surface the hybrid engine consumes. Kept as an
 * interface so engine tests substitute fakes without Android model files.
 */
interface LocalAiRouter {
    /** True when at least one downloadable local AI model is verified on disk. */
    fun hasLocalAiModel(): Boolean

    /** Engine id the next local line would actually use; the cache keys on this. */
    fun plannedLocalEngineId(): String

    /** Translates fully on-device; never touches the network. */
    suspend fun translate(request: TranslationRequest): TranslationResult

    /** Releases warm model resources. */
    fun close()
}

/** A downloadable-model engine in the local chain: available only when its model verified on disk. */
interface LocalAiCandidate : TranslationEngine {
    fun isAvailable(): Boolean
}
