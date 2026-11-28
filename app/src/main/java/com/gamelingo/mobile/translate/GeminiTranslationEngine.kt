package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * Online translation through the Gemini generateContent API using the user's
 * own free AI Studio key (entered in settings, stored encrypted on-device).
 *
 * The default model is a free-tier Flash-Lite model: fast enough for live
 * subtitles and free of charge within the per-minute and per-day limits.
 * API failures are classified by HTTP status (quota vs provider down vs bad
 * key vs network) so the hybrid engine can back off instead of hammering a
 * spent quota, and so the user gets an honest error instead of a generic one.
 */
@Singleton
class GeminiTranslationEngine @Inject constructor(
    private val api: GeminiApi,
    private val settingsRepository: SettingsRepository,
) : TranslationEngine {

    override val engineId: String = ENGINE_ID

    /**
     * Every online request goes through this gate: bounded concurrency and a
     * local cooldown after a 429. Settable so tests use an isolated instance.
     */
    internal var gate: OnlineRequestGate = OnlineRequestGate.shared

    /** Nothing to warm up: the model runs in the cloud. */
    override suspend fun ensureReady() = Unit

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val apiKey = settingsRepository.settings.first().onlineApiKey.trim()
        if (apiKey.isEmpty()) {
            throw OnlineNotConfiguredException()
        }

        val response = gate.run {
            val answer = try {
                api.generateContent(
                    model = MODEL,
                    apiKey = apiKey,
                    request = GeminiRequest(
                        // Mode-appropriate mandate: the request carries the
                        // user's Arabic style (MSA or Egyptian); the profanity
                        // policy is identical in both.
                        systemInstruction = GeminiContent(
                            parts = listOf(GeminiPart(PromptComposer.systemPrompt(request.arabicMode, request.text))),
                        ),
                        contents = listOf(
                            GeminiContent(
                                role = "user",
                                parts = listOf(GeminiPart(ArabicStylePrompt.buildUserPrompt(request))),
                            ),
                        ),
                        generationConfig = GeminiGenerationConfig(
                            temperature = TEMPERATURE,
                            maxOutputTokens = MAX_OUTPUT_TOKENS,
                        ),
                        // Game dialogue carries profanity and violence the
                        // translation must PRESERVE, never soften or drop. The
                        // default safety filters silently swallow those lines;
                        // explicit BLOCK_NONE keeps the established profanity
                        // policy intact on the online path too.
                        safetySettings = SAFETY_SETTINGS,
                    ),
                )
            } catch (http: HttpException) {
                throw classifyHttpError(http, gate.now())
            }
            answer.error?.let { error ->
                throw classifyApiError(error.code ?: 0, error.status)
            }
            answer
        }

        val candidate = response.candidates?.firstOrNull()
        val finishReason = candidate?.finishReason
        // A truncated answer is NOT a successful translation: serving half a
        // sentence as if complete shows the user a wrong line. Treat it as a
        // provider failure so the chain degrades visibly to a full answer.
        if (finishReason == FINISH_MAX_TOKENS) {
            throw ProviderRefusedException("Gemini truncated the translation (MAX_TOKENS)")
        }
        if (finishReason != null && finishReason != FINISH_STOP) {
            // SAFETY / RECITATION / OTHER: the line was refused. With
            // BLOCK_NONE this should be rare; when it still happens, degrade
            // visibly instead of passing silence off as a translation.
            throw ProviderRefusedException("Gemini refused the line ($finishReason)")
        }
        val translated = candidate
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?.trim()
        if (translated.isNullOrEmpty()) {
            throw IOException("Gemini returned no translation")
        }
        return TranslationResult(translatedText = translated, engineId = engineId)
    }

    /**
     * One request for several lines. Same model, same style/profanity mandate,
     * same gate (so it counts as ONE request against the provider limit).
     * Entries that fail the number or source-echo check come back null (translate those
     * lines alone). Throws [BatchMisalignedException] when no entry is usable.
     */
    override suspend fun translateBatch(requests: List<TranslationRequest>): List<String?>? {
        if (requests.size < 2) return null
        val apiKey = settingsRepository.settings.first().onlineApiKey.trim()
        if (apiKey.isEmpty()) throw OnlineNotConfiguredException()
        val first = requests.first()
        val glossary = LinkedHashMap<String, String>()
        requests.forEach { glossary.putAll(it.glossary) }
        val hints = requests.withIndex()
            .filter { it.value.understandingHints.isNotBlank() }
            .associate { (it.index + 1) to it.value.understandingHints }
        val system = PromptComposer.systemPrompt(first.arabicMode, requests.joinToString("\n") { it.text }) +
            BatchFormat.SYSTEM_SUFFIX
        val user = BatchFormat.userPrompt(requests.map { it.text }, first.contextLines, glossary, hints)

        val response = gate.run {
            val answer = try {
                api.generateContent(
                    model = MODEL,
                    apiKey = apiKey,
                    request = GeminiRequest(
                        systemInstruction = GeminiContent(parts = listOf(GeminiPart(system))),
                        contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(user)))),
                        generationConfig = GeminiGenerationConfig(
                            temperature = TEMPERATURE,
                            maxOutputTokens = BatchFormat.maxOutputTokens(requests.size),
                        ),
                        safetySettings = SAFETY_SETTINGS,
                    ),
                )
            } catch (http: HttpException) {
                throw classifyHttpError(http, gate.now())
            }
            answer.error?.let { error -> throw classifyApiError(error.code ?: 0, error.status) }
            answer
        }
        val candidate = response.candidates?.firstOrNull()
        val finishReason = candidate?.finishReason
        if (finishReason == FINISH_MAX_TOKENS) {
            throw BatchMisalignedException("Gemini truncated the batch (MAX_TOKENS)")
        }
        if (finishReason != null && finishReason != FINISH_STOP) {
            throw ProviderRefusedException("Gemini refused the batch ($finishReason)")
        }
        val raw = candidate?.content?.parts?.firstOrNull()?.text.orEmpty()
        val entries = BatchFormat.parseEntries(raw, requests.map { it.text })
        if (entries == null || entries.all { it == null }) {
            throw BatchMisalignedException("Gemini batch answer had no usable entry for ${requests.size} lines")
        }
        return entries
    }

    override fun close() = Unit

    companion object {
        /** Free-tier Flash-Lite model, fast and free within AI Studio limits. */
        const val MODEL = "gemini-3.5-flash-lite"
        const val ENGINE_ID = "gemini-flash-lite"
        const val TEMPERATURE = 0.4
        const val MAX_OUTPUT_TOKENS = 256
        private const val FINISH_STOP = "STOP"
        private const val FINISH_MAX_TOKENS = "MAX_TOKENS"

        /** Profanity/violence in game dialogue must survive translation. */
        private val SAFETY_SETTINGS = listOf(
            GeminiSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
            GeminiSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
            GeminiSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
            GeminiSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE"),
        )

        /** Maps an HTTP failure to the typed error the rest of the app understands. */
        private fun classifyHttpError(error: HttpException, nowMillis: Long): IOException = when (error.code()) {
            HTTP_TOO_MANY_REQUESTS -> {
                // Only whitelisted fields of the body are kept (status, quota
                // id, retry delay); the message text and headers other than
                // Retry-After are never stored.
                val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                val info = ProviderErrorInfo.parse(body)
                QuotaExceededException(
                    "Gemini quota or rate limit hit (429)",
                    retryAfterMs = RetryAfter.parseMillis(
                        error.response()?.headers()?.get("Retry-After"),
                        nowMillis,
                    ) ?: info.retryDelayMs,
                    provider = info,
                )
            }
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                ApiAuthException("Gemini rejected the API key (${error.code()})")
            in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END ->
                ProviderUnavailableException("Gemini unavailable (${error.code()})")
            else -> IOException("Gemini HTTP ${error.code()}")
        }

        /** Same classification for a 200 response carrying an error body. */
        private fun classifyApiError(code: Int, status: String?): IOException = when (code) {
            HTTP_TOO_MANY_REQUESTS ->
                QuotaExceededException(
                    "Gemini quota ($code)",
                    provider = ProviderErrorInfo.parse("\"status\":\"${status.orEmpty()}\""),
                )
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                ApiAuthException("Gemini key error ($code)")
            in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END ->
                ProviderUnavailableException("Gemini unavailable ($code)")
            else -> IOException("Gemini API error $code")
        }

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_START = 500
        private const val HTTP_SERVER_ERROR_END = 599
    }
}
