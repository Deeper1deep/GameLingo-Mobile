package com.gamelingo.mobile.translate

/** One line of subtitle text plus the surrounding dialogue context. */
data class TranslationRequest(
    val text: String,
    /** Previous original lines, oldest first. Kept short: never the whole dialogue. */
    val contextLines: List<String> = emptyList(),
    /** Character names seen so far, kept consistent across translations. */
    val characterNames: List<String> = emptyList(),
    /** Gaming terms with their established Egyptian renderings. */
    val glossary: Map<String, String> = emptyMap(),
)

/** The engine's answer for one line. */
data class TranslationResult(
    val translatedText: String,
    /** Which engine actually produced this: shown in history, never hidden. */
    val engineId: String,
    val fromCache: Boolean = false,
)

/**
 * A translation engine. Online engines use the user's own API key (entered in
 * settings, stored on-device); the local engine never touches the network.
 */
interface TranslationEngine {
    /** Stable identifier recorded in the translation history. */
    val engineId: String

    /** Downloads models or warms up as needed. Idempotent. */
    suspend fun ensureReady()

    /** Translates [request.text] into natural Egyptian colloquial Arabic. */
    suspend fun translate(request: TranslationRequest): TranslationResult

    fun close()
}
