package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode

/**
 * Everything besides the text itself that changes what a translation means or
 * where it comes from. Two requests with the same text but a different
 * [CacheContext] must never share an entry: a cached online answer is not a
 * local answer, a different prompt style is a different translation, a
 * changed dialogue context can change the meaning of the line, and a model
 * or dictionary update must never serve pre-update output.
 */
data class CacheContext(
    val aiMode: AiMode,
    /** The engine the request would run on right now (after privacy gating). */
    val plannedEngineId: String,
    /**
     * Arabic style of the request. The same sentence in MSA and in Egyptian
     * are two independent cache entries; one mode's result is never served
     * for the other.
     */
    val arabicMode: com.gamelingo.mobile.data.settings.ArabicTranslationMode =
        com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC,
    /** Prompt wording version for the request's mode at request time. */
    val promptVersion: Int,
    /** Hash of the context lines actually sent; 0 when context is disabled. */
    val contextSignature: Long,
    /** Installed version of the model behind the planned local engine; 0 otherwise. */
    val modelVersion: Int = 0,
    /** Active Egyptian dictionary version; a dictionary update must not serve old entries. */
    val dictionaryVersion: Int = 0,
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

        /**
         * Stable signature for the context lines sent with a request. A
         * 32-bit String.hashCode over a joined blob collides across real
         * dialogue variants (same words, different boundaries), which would
         * serve a translation computed under the WRONG context. Use 64 bits
         * of SHA-256 over length-prefixed lines instead.
         */
        fun signatureOf(contextLines: List<String>): Long {
            if (contextLines.isEmpty()) return 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            for (line in contextLines) {
                digest.update(line.length.toByte())
                digest.update((line.length ushr 8).toByte())
                digest.update(line.toByteArray(Charsets.UTF_8))
            }
            val hash = digest.digest()
            var out = 0L
            for (i in 0 until 8) out = (out shl 8) or (hash[i].toLong() and 0xFF)
            return out
        }
    }
}
