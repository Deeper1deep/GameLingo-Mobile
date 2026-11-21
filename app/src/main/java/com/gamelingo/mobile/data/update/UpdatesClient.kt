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
     * Streams the APK to [dest] with (downloaded, total) progress. Throws on
     * failure; a partially written file never survives. Enforces
     * [MAX_APK_BYTES] both up front (Content-Length) and mid-stream (a server
     * that lies about the length): an update APK beyond the cap is not an
     * update, it is a hijack or a mistake, and it never reaches the verifier.
     * Cancelling the coroutine cancels the socket.
     */
    suspend fun downloadApk(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        activeCall = call
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) call.cancel()
            }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "APK download HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "APK empty body" }
                val total = body.contentLength().takeIf { it > 0 }
                check(total == null || total <= MAX_APK_BYTES) {
                    "APK too large: $total bytes (cap $MAX_APK_BYTES)"
                }
                StreamCopier.copy(body.byteStream(), dest) { written ->
                    if (written > MAX_APK_BYTES) {
                        throw java.io.IOException(
                            "APK exceeded the size cap mid-download ($MAX_APK_BYTES bytes)",
                        )
                    }
                    onProgress(written, total)
                }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    /** The in-flight APK download call, so a user cancel can cut the socket. */
    @Volatile
    private var activeCall: okhttp3.Call? = null

    /** Cancels the in-flight APK download, if any. */
    fun cancelActiveDownload() {
        activeCall?.cancel()
    }

    companion object {
        const val LATEST_JSON_URL =
            "https://raw.githubusercontent.com/Deeper1deep/GameLingo-Updates/main/latest.json"

        /**
         * Hard cap on an update APK. The real APK is ~80MB; four times that
         * leaves room for growth while refusing absurd payloads early (the
         * user pays for these bytes on a mobile plan).
         */
        const val MAX_APK_BYTES = 350L * 1_000_000L
    }
}
