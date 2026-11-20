package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode

/**
 * Everything besides the text itself that changes what a translation means or
 * where it comes from. Two requests with the same text but a different
 * [CacheContext] must never share an entry: a cached online answer is not a
 * local answer, a different prompt style is a different translation, and a
 * changed dialogue context can change the meaning of the line.
 */
data class CacheContext(
    val aiMode: AiMode,
    /** The engine the request would run on right now (after privacy gating). */
    val plannedEngineId: String,
    /** EgyptianDialectPrompt.PROMPT_VERSION at request time. */
    val promptVersion: Int,
    /** Hash of the context lines actually sent; 0 when context is disabled. */
    val contextSignature: Int,
    val languagePair: String = LANGUAGE_PAIR,
) {

    /**
     * Compatibility guard: a stored entry produced by [engineId] may be served
     * for this context. AUTO legitimately accepts either engine (fallback is
     * part of the mode); ONLINE and LOCAL accept only their own engine.
     */
    fun accepts(engineId: String): Boolean = when (aiMode) {
        AiMode.AUTO -> true
        AiMode.ONLINE, AiMode.LOCAL -> engineId == plannedEngineId
    }

    companion object {
        const val LANGUAGE_PAIR = "en-ar"

        /** Stable signature for the context lines sent with a request. */
        fun signatureOf(contextLines: List<String>): Int =
            if (contextLines.isEmpty()) 0 else contextLines.joinToString("").hashCode()
    }
}
