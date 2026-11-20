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
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(EgyptianDialectPrompt.SYSTEM_PROMPT)),
                    ),
                    contents = listOf(
                        GeminiContent(
                            role = "user",
                            parts = listOf(GeminiPart(EgyptianDialectPrompt.buildUserPrompt(request))),
                        ),
                    ),
                    generationConfig = GeminiGenerationConfig(
                        temperature = TEMPERATURE,
                        maxOutputTokens = MAX_OUTPUT_TOKENS,
                    ),
                ),
            )
        } catch (http: HttpException) {
            throw classifyHttpError(http)
        }

        response.error?.let { error ->
            throw classifyApiError(error.code ?: 0, error.message.orEmpty())
        }

        val translated = response.candidates
            ?.firstOrNull()
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

        /** Maps an HTTP failure to the typed error the rest of the app understands. */
        private fun classifyHttpError(error: HttpException): IOException = when (error.code()) {
            HTTP_TOO_MANY_REQUESTS ->
                QuotaExceededException("Gemini quota or rate limit hit (429)")
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

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_START = 500
        private const val HTTP_SERVER_ERROR_END = 599
    }
}
