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

    /** Nothing to warm up: the model runs in the cloud. */
    override suspend fun ensureReady() = Unit

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val apiKey = settingsRepository.settings.first().onlineApiKey.trim()
        if (apiKey.isEmpty()) {
            throw OnlineNotConfiguredException()
        }

        val response = try {
            api.generateContent(
                model = MODEL,
                apiKey = apiKey,
                request = GeminiRequest(
                    // Mode-appropriate mandate: the request carries the
                    // user's Arabic style (MSA or Egyptian); the profanity
                    // policy is identical in both.
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(ArabicStylePrompt.systemPromptFor(request.arabicMode))),
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
            throw classifyHttpError(http)
        }

        response.error?.let { error ->
            throw classifyApiError(error.code ?: 0, error.message.orEmpty())
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
        private fun classifyHttpError(error: HttpException): IOException = when (error.code()) {
            HTTP_TOO_MANY_REQUESTS ->
                QuotaExceededException(
                    "Gemini quota or rate limit hit (429)",
                    retryAfterMs = parseRetryAfter(error),
                )
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                ApiAuthException("Gemini rejected the API key (${error.code()})")
            in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END ->
                ProviderUnavailableException("Gemini unavailable (${error.code()})")
            else -> IOException("Gemini HTTP ${error.code()}")
        }

        /** Same classification for a 200 response carrying an error body. */
        private fun classifyApiError(code: Int, message: String): IOException = when (code) {
            HTTP_TOO_MANY_REQUESTS -> QuotaExceededException("Gemini quota ($code): $message")
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                ApiAuthException("Gemini key error ($code): $message")
            in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END ->
                ProviderUnavailableException("Gemini unavailable ($code): $message")
            else -> IOException("Gemini API error $code: $message")
        }

        /**
         * Reads the Retry-After header (seconds) from a 429 response. Null
         * when absent or unparsable: the default cooldown then applies.
         */
        private fun parseRetryAfter(error: HttpException): Long? =
            error.response()?.headers()?.get("Retry-After")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it in 0..MAX_RETRY_AFTER_SEC }
                ?.let { it * MILLIS_PER_SECOND }

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_START = 500
        private const val HTTP_SERVER_ERROR_END = 599
        private const val MILLIS_PER_SECOND = 1_000L

        /** Caps a Retry-After hint at 10 minutes so a hostile or buggy
         *  header cannot park the online engine for hours. */
        private const val MAX_RETRY_AFTER_SEC = 600L
    }
}
