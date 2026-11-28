package com.gamelingo.mobile.translate

/** One line of subtitle text plus the surrounding dialogue context. */
data class TranslationRequest(
    val text: String,
    /** Previous original lines, oldest first. Kept short: never the whole dialogue. */
    val contextLines: List<String> = emptyList(),
    /** Character names seen so far, kept consistent across translations. */
    val characterNames: List<String> = emptyList(),
    /** Gaming terms with their established renderings for the target mode. */
    val glossary: Map<String, String> = emptyMap(),
    /**
     * The Arabic style this line must land in. Carried on the request (not
     * read from settings inside engines) so a fallback to another engine
     * can never silently switch styles, and so a mode change mid-session
     * only affects lines built after it.
     */
    val arabicMode: com.gamelingo.mobile.data.settings.ArabicTranslationMode =
        com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC,
    /**
     * Non-literal expressions the understanding layer found in [text],
     * rendered as meaning hints for prompt-capable engines. Empty when the
     * line has no known idiom/phrasal verb, so prompts never pay for noise.
     * Literal engines ignore this; their safety net is exact-line
     * post-processing in the chain.
     */
    val understandingHints: String = "",
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

    /**
     * Translates several lines in one provider request, one translation per
     * input, in input order. Null when this engine has no batch path (callers
     * then translate line by line). Throws [BatchMisalignedException] when the
     * answer does not map exactly onto the inputs.
     */
    suspend fun translateBatch(requests: List<TranslationRequest>): List<String>? = null

    fun close()
}

/**
 * The engine an id belongs to, without the post-processing tags the pipeline
 * appends ("+dict", "+idiom", "+qc"). Always compare engines through this:
 * a tagged id is still the same engine.
 */
fun baseEngineId(engineId: String): String = engineId.substringBefore('+')
