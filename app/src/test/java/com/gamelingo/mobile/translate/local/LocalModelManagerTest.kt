package com.gamelingo.mobile.translate.local

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the model manager end-to-end on the JVM via Robolectric: manifest
 * parsing, states, resumable Range downloads, the SHA-256 gate, old-version
 * cleanup, Wi-Fi consent, and deletion. The network is a scripted OkHttp
 * interceptor, so no byte ever leaves the test process. Download tests run
 * against a tiny injected manifest so they stay fast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalModelManagerTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var server: FakeServer
    private lateinit var manager: LocalModelManager

    private val encoderBytes = ByteArray(1024) { (it * 7 % 253).toByte() }
    private val decoderBytes = ByteArray(2048) { (it * 13 % 241).toByte() }

    @Before
    fun setUp() {
        File(context.filesDir, "local_ai").deleteRecursively()
        server = FakeServer()
        manager = LocalModelManager(
            context,
            OkHttpClient.Builder().addInterceptor(server).build(),
        )
        manager.manifestOverride = fakeManifest()
    }

    @Test
    fun `bundled manifest exposes both downloadable models`() {
        val realManager = LocalModelManager(context, OkHttpClient())
        val specs = realManager.specs()
        assertEquals(
            setOf(LocalModelManager.MODEL_MARIAN, LocalModelManager.MODEL_TRANSLATEGEMMA),
            specs.map { it.id }.toSet(),
        )
        for (spec in specs) {
            assertTrue(spec.files.isNotEmpty())
            assertTrue(spec.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
            assertTrue(spec.files.all { it.url.startsWith("https://") })
            assertTrue(spec.attribution.isNotBlank())
            assertTrue(spec.files.all { it.size > 0 })
        }
    }

    @Test
    fun `models start not installed`() {
        assertEquals(LocalAiModelState.NotInstalled, manager.stateOf(FAKE_MODEL))
        assertNull(manager.modelDir(FAKE_MODEL))
    }

    @Test
    fun `download installs after sha verification and reports the installed state`() = runTest {
        serveGood()
        manager.download(FAKE_MODEL, preferWifi = false)
        val state = manager.stateOf(FAKE_MODEL)
        assertTrue(state is LocalAiModelState.Installed)
        assertEquals(FAKE_VERSION, (state as LocalAiModelState.Installed).version)
        assertArrayEquals(encoderBytes, manager.modelFile(FAKE_MODEL, "encoder.bin")!!.readBytes())
        assertArrayEquals(decoderBytes, manager.modelFile(FAKE_MODEL, "decoder.bin")!!.readBytes())
    }

    @Test
    fun `sha mismatch refuses the install and deletes the bad file`() = runTest {
        server.serve(ENCODER_URL, ByteArray(encoderBytes.size) { 9 })
        server.serve(DECODER_URL, decoderBytes)
        val thrown = runCatching { manager.download(FAKE_MODEL, preferWifi = false) }
            .exceptionOrNull()
        assertTrue(thrown is LocalModelManager.ModelManagerException)
        assertEquals("verify_failed", (thrown as LocalModelManager.ModelManagerException).code)
        assertTrue(manager.stateOf(FAKE_MODEL) is LocalAiModelState.Failed)
        assertNull(manager.modelDir(FAKE_MODEL))
    }

    @Test
    fun `interrupted download resumes with a range request`() = runTest {
        serveGood()
        // Seed a .part with the first 100 bytes, as a cancelled attempt would leave.
        val targetDir = File(context.filesDir, "local_ai/${FAKE_MODEL}_v$FAKE_VERSION")
        targetDir.mkdirs()
        File(targetDir, "encoder.bin.part").writeBytes(encoderBytes.copyOfRange(0, 100))
        manager.download(FAKE_MODEL, preferWifi = false)
        assertArrayEquals(encoderBytes, manager.modelFile(FAKE_MODEL, "encoder.bin")!!.readBytes())
        assertEquals("bytes=100-", server.rangeHeaders[ENCODER_URL])
        assertEquals(206, server.responseCodes[ENCODER_URL])
        assertNull(server.rangeHeaders[DECODER_URL])
    }

    @Test
    fun `server ignoring range restarts the file cleanly`() = runTest {
        serveGood()
        server.ignoreRange(ENCODER_URL)
        val targetDir = File(context.filesDir, "local_ai/${FAKE_MODEL}_v$FAKE_VERSION")
        targetDir.mkdirs()
        File(targetDir, "encoder.bin.part").writeBytes(ByteArray(100) { 99 })
        manager.download(FAKE_MODEL, preferWifi = false)
        assertArrayEquals(encoderBytes, manager.modelFile(FAKE_MODEL, "encoder.bin")!!.readBytes())
    }

    @Test
    fun `wifi consent throws on a metered connection`() = runTest {
        // Robolectric reports no active network, which counts as metered here.
        val thrown = runCatching { manager.download(FAKE_MODEL, preferWifi = true) }
            .exceptionOrNull()
        assertTrue(thrown is LocalModelManager.MeteredConnectionException)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `http failure keeps partial files and reports failed`() = runTest {
        server.serve(ENCODER_URL, encoderBytes)
        // DECODER_URL unserved: 404.
        val thrown = runCatching { manager.download(FAKE_MODEL, preferWifi = false) }
            .exceptionOrNull()
        assertTrue(thrown is LocalModelManager.ModelManagerException)
        assertTrue(manager.stateOf(FAKE_MODEL) is LocalAiModelState.Failed)
        assertNull(manager.modelDir(FAKE_MODEL))
    }

    @Test
    fun `delete removes the installed model and its partials`() = runTest {
        serveGood()
        manager.download(FAKE_MODEL, preferWifi = false)
        assertNotNull(manager.modelDir(FAKE_MODEL))
        manager.deleteModel(FAKE_MODEL)
        assertNull(manager.modelDir(FAKE_MODEL))
        assertEquals(LocalAiModelState.NotInstalled, manager.stateOf(FAKE_MODEL))
    }

    @Test
    fun `a newer verified version cleans up older installs`() = runTest {
        serveGood()
        manager.download(FAKE_MODEL, preferWifi = false)
        // Pretend an older install still sits next to it; a fresh install removes it.
        val v0 = File(context.filesDir, "local_ai/${FAKE_MODEL}_v0")
        v0.mkdirs()
        File(v0, "stale.bin").writeBytes(ByteArray(3))
        manager.download(FAKE_MODEL, preferWifi = false)
        assertTrue("old version dir must be gone", !v0.exists())
    }

    private fun serveGood() {
        server.serve(ENCODER_URL, encoderBytes)
        server.serve(DECODER_URL, decoderBytes)
    }

    private fun fakeManifest(): String = """
        {"models":[{
          "id":"$FAKE_MODEL",
          "version":$FAKE_VERSION,
          "modelName":"Fake test model",
          "license":"CC-BY-4.0",
          "attribution":"test fixture",
          "files":[
            {"name":"encoder.bin","url":"$ENCODER_URL","sha256":"${sha256(encoderBytes)}","size":${encoderBytes.size}},
            {"name":"decoder.bin","url":"$DECODER_URL","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}}
          ]
        }]}
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** In-memory file server honoring HTTP Range, recording the last request. */
    private class FakeServer : Interceptor {
        private val files = LinkedHashMap<String, ByteArray>()
        private val noRange = HashSet<String>()
        var callCount = 0
            private set
        val rangeHeaders = LinkedHashMap<String, String?>()
        val responseCodes = LinkedHashMap<String, Int>()

        fun serve(url: String, bytes: ByteArray) {
            files[url] = bytes
        }

        fun ignoreRange(url: String) {
            noRange += url
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            callCount++
            val request = chain.request()
            val url = request.url.toString()
            val bytes = files[url]
            rangeHeaders[url] = request.header("Range")
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .message("fake")
            if (bytes == null) {
                responseCodes[url] = 404
                return builder.code(404).body("missing".toResponseBody("text/plain".toMediaType())).build()
            }
            val range = request.header("Range")
            val serveRange = range != null && url !in noRange && range.startsWith("bytes=")
            return if (serveRange) {
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                responseCodes[url] = 206
                builder.code(206)
                    .body(bytes.copyOfRange(start, bytes.size).toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            } else {
                responseCodes[url] = 200
                builder.code(200)
                    .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        }
    }

    private companion object {
        const val FAKE_MODEL = "fake-model"
        const val FAKE_VERSION = 3
        const val ENCODER_URL = "https://example.test/fake/encoder.bin"
        const val DECODER_URL = "https://example.test/fake/decoder.bin"
    }
}
