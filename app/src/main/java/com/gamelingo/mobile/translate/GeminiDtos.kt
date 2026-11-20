package com.gamelingo.mobile.translate

import kotlinx.serialization.Serializable

/**
 * Wire models for the Gemini generateContent REST API (v1beta). Only the
 * fields the app sends or reads are declared; unknown response fields are
 * ignored by the configured Json instance.
 */
@Serializable
data class GeminiPart(
    val text: String? = null,
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
data class GeminiRequest(
    val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

@Serializable
data class GeminiErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiErrorBody? = null,
)
