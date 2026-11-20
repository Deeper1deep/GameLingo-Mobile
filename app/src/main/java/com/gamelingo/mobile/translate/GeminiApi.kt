package com.gamelingo.mobile.translate

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Gemini generateContent endpoint. The user's own free AI Studio key travels
 * in the x-goog-api-key header so it never appears in URLs or logs.
 */
interface GeminiApi {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest,
    ): GeminiResponse
}
