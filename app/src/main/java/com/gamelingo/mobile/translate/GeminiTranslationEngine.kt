package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Online translation through the Gemini generateContent API using the user's
 * own free AI Studio key (entered in settings, stored on-device only).
 *
 * The default model is a free-tier Flash-Lite model: fast enough for live
 * subtitles and free of charge within the per-minute and per-day limits.
 * Throws when the key is missing or the API answers with an error, so the
 * hybrid engine can fall back or surface the failure honestly.
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
            throw IOException("Gemini API key is not configured")
        }

        val response = api.generateContent(
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

        response.error?.let { error ->
            throw IOException("Gemini API error ${error.code}: ${error.message}")
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
    }
}
