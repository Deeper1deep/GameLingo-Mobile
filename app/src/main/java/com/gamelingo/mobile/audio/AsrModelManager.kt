package com.gamelingo.mobile.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.gamelingo.mobile.core.audio.AsrFileChecks
import com.gamelingo.mobile.core.audio.AsrModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AsrModelState {
    data object NotInstalled : AsrModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AsrModelState
    data object Verifying : AsrModelState
    data class Installed(val sizeBytes: Long) : AsrModelState
    data class Failed(val reason: String) : AsrModelState
}

/**
 * The user-controlled speech model: downloaded only when the user asks,
 * Wi-Fi only by default, resumable, size + MD5 + SHA-256 checked before it is
 * unpacked, installed atomically into private storage and removable.
 */
@Singleton
class AsrModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) {
    val spec: AsrModelSpec = AsrModelSpec.SMALL_EN_US

    private val root get() = File(context.filesDir, "asr")
    private val installDir get() = File(root, spec.id)
    private val partFile get() = File(root, "${spec.id}.zip.part")
    private val mutex = Mutex()

    private val _state = MutableStateFlow<AsrModelState>(AsrModelState.NotInstalled)
    val state: StateFlow<AsrModelState> = _state.asStateFlow()

    /** The unpacked model folder when a verified install exists. */
    fun modelDir(): File? =
        File(installDir, spec.rootFolder).takeIf { it.isDirectory && File(installDir, VERIFIED).isFile }

    fun refresh() {
        _state.value = modelDir()?.let {
            AsrModelState.Installed(it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() })
        } ?: AsrModelState.NotInstalled
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Throws nothing: failures land in [state] as [AsrModelState.Failed]. */
    suspend fun download(wifiOnly: Boolean): Unit = mutex.withLock {
        if (modelDir() != null) { refresh(); return@withLock }
        if (wifiOnly && !isUnmetered()) {
            _state.value = AsrModelState.Failed("wifi_required"); return@withLock
        }
        try {
            withContext(Dispatchers.IO) {
                root.mkdirs()
                fetch()
                _state.value = AsrModelState.Verifying
                if (!AsrFileChecks.matches(partFile, spec)) {
                    partFile.delete()
                    _state.value = AsrModelState.Failed("verification_failed"); return@withContext
                }
                val staging = File(root, "${spec.id}.staging").also { it.deleteRecursively() }
                partFile.inputStream().use { AsrFileChecks.unzipSafely(it, staging, spec.unpackedBytesApprox * 2) }
                File(staging, VERIFIED).writeText("md5+sha256 ok")
                installDir.deleteRecursively()
                check(staging.renameTo(installDir)) { "install rename failed" }
                partFile.delete()
                refresh()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = AsrModelState.Failed(e.javaClass.simpleName)
        }
    }

    private suspend fun fetch() {
        var have = if (partFile.isFile) partFile.length() else 0L
        if (have > spec.sizeBytes) { partFile.delete(); have = 0L }
        if (have == spec.sizeBytes) return
        val req = Request.Builder().url(spec.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        okHttp.newCall(req).execute().use { resp ->
            check(resp.code == 200 || resp.code == 206) { "http_${resp.code}" }
            if (resp.code == 200 && have > 0) { partFile.delete(); have = 0L }
            val body = checkNotNull(resp.body)
            java.io.FileOutputStream(partFile, have > 0).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        _state.value = AsrModelState.Downloading(done, spec.sizeBytes)
                    }
                }
            }
        }
    }

    fun delete() {
        root.listFiles()?.filter { it.name.startsWith(spec.id) }?.forEach { it.deleteRecursively() }
        _state.value = AsrModelState.NotInstalled
    }

    private companion object { const val VERIFIED = ".verified" }
}
