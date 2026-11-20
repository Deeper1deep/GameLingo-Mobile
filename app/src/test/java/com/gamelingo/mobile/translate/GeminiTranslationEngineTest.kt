package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AppSettings
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTranslationEngineTest {

    /** Captures the request and answers with a canned response. */
    private class StubApi(
        var response: GeminiResponse,
    ) : GeminiApi {
        var lastModel: String? = null
        var lastKey: String? = null
        var lastRequest: GeminiRequest? = null

        override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiRequest,
        ): GeminiResponse {
            lastModel = model
            lastKey = apiKey
            lastRequest = request
            return response
        }
    }

    private fun engine(
        apiKey: String,
        api: GeminiApi,
    ) = GeminiTranslationEngine(api, FakeSettingsRepository(AppSettings(onlineApiKey = apiKey)))

    @Test
    fun `missing key fails before any network call`() = runTest {
        val api = StubApi(GeminiResponse(candidates = emptyList()))
        val thrown = runCatching {
            engine("  ", api).translate(TranslationRequest(text = "hi"))
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(null, api.lastRequest)
    }

    @Test
    fun `request carries system prompt, user prompt, key header and model`() = runTest {
        val api = StubApi(
            GeminiResponse(
                candidates = listOf(
                    GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart("ازيك")))),
                ),
            ),
        )
        val result = engine("my-key", api).translate(
            TranslationRequest(text = "How are you?", contextLines = listOf("previous")),
        )
        assertEquals("ازيك", result.translatedText)
        assertEquals("gemini-flash-lite", result.engineId)
        assertEquals("my-key", api.lastKey)
        assertEquals(GeminiTranslationEngine.MODEL, api.lastModel)
        val request = api.lastRequest
        assertNotNull(request)
        assertEquals(
            EgyptianDialectPrompt.SYSTEM_PROMPT,
            request!!.systemInstruction?.parts?.firstOrNull()?.text,
        )
        val userText = request.contents.first().parts.first().text
        assertTrue(userText!!.contains("How are you?"))
        assertTrue(userText.contains("previous"))
    }

    @Test
    fun `empty candidates fail so the hybrid engine can fall back`() = runTest {
        val api = StubApi(GeminiResponse(candidates = emptyList()))
        val thrown = runCatching {
            engine("key", api).translate(TranslationRequest(text = "hi"))
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }

    @Test
    fun `error body in a 200 envelope fails with its message`() = runTest {
        val api = StubApi(
            GeminiResponse(error = GeminiErrorBody(code = 429, message = "quota exhausted")),
        )
        val thrown = runCatching {
            engine("key", api).translate(TranslationRequest(text = "hi"))
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertTrue(thrown!!.message!!.contains("429"))
    }

    @Test
    fun `error messages never contain the api key`() = runTest {
        val api = StubApi(
            GeminiResponse(error = GeminiErrorBody(code = 400, message = "bad request")),
        )
        val secret = "AIzaSECRETKEY123"
        val thrown = runCatching {
            engine(secret, api).translate(TranslationRequest(text = "hi"))
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertFalse(thrown!!.message.orEmpty().contains(secret))

        val missing = runCatching {
            engine("   ", StubApi(GeminiResponse())).translate(TranslationRequest(text = "hi"))
        }.exceptionOrNull()
        assertFalse(missing!!.message.orEmpty().contains("AIza"))
    }

    @Test
    fun `response json parses the way the API actually answers`() {
        // A trimmed real-shape response, including fields we do not declare.
        val json = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "  ترجمة  "}]},
                  "finishReason": "STOP",
                  "avgLogprobs": -0.1
                }
              ],
              "usageMetadata": {"promptTokenCount": 10},
              "modelVersion": "gemini-3.5-flash-lite"
            }
        """.trimIndent()
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString(GeminiResponse.serializer(), json)
        assertEquals(
            "  ترجمة  ",
            parsed.candidates!!.first().content!!.parts.first().text,
        )
    }
}
