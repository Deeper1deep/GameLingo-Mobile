package com.gamelingo.mobile.data.update

import com.gamelingo.mobile.core.update.LatestRelease
import com.gamelingo.mobile.core.update.StreamCopier
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Talks to the public GameLingo-Updates repo over raw.githubusercontent.com.
 * No authentication: the repo only ever holds public release artifacts.
 */
@Singleton
class UpdatesClient @Inject constructor(
    private val client: OkHttpClient,
) {

    /**
     * Fetches and parses latest.json. Throws IOException on network/HTTP
     * failure and SerializationException on a malformed manifest, so callers
     * can tell a connection problem apart from untrustworthy metadata.
     */
    suspend fun fetchLatestRelease(): LatestRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_JSON_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("latest.json HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw java.io.IOException("latest.json empty body")
            LatestRelease.parse(body)
        }
    }

    /** Best-effort Content-Length of the APK, for showing the size before downloading. */
    suspend fun fetchApkSizeBytes(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                        ?: response.body?.contentLength()?.takeIf { it > 0 }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Streams the APK to [dest] with (downloaded, total) progress. Throws on failure;
     * a partially written file never survives.
     */
    suspend fun downloadApk(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "APK download HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "APK empty body" }
            val total = body.contentLength().takeIf { it > 0 }
            StreamCopier.copy(body.byteStream(), dest) { written -> onProgress(written, total) }
        }
    }

    companion object {
        const val LATEST_JSON_URL =
            "https://raw.githubusercontent.com/Deeper1deep/GameLingo-Updates/main/latest.json"
    }
}
