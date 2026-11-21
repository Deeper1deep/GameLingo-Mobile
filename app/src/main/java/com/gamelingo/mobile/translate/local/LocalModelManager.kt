package com.gamelingo.mobile.translate.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Lifecycle of one downloadable local model. */
sealed interface LocalAiModelState {
    /** No verified model on disk. The engine must not claim it works. */
    data object NotInstalled : LocalAiModelState

    data class Downloading(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : LocalAiModelState

    data object Verifying : LocalAiModelState

    data class Installed(val version: Int, val sizeBytes: Long) : LocalAiModelState

    /** Download or verification failed; partial files were kept for resume. */
    data class Failed(val reason: String) : LocalAiModelState
}

/**
 * Owns the downloadable local translation models (Marian EN-AR and the optional
 * TranslateGemma HD model): presence check, consented download (Wi-Fi preferred,
 * resumable), SHA-256 verification before ANY load, atomic versioned install into
 * private storage, safe updates with old-file cleanup, and deletion from settings.
 * A model never runs before its hashes verify, and no model ships inside the APK.
 */
@Singleton
class LocalModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) {

    /** Test-only seam: a manifest replacing the bundled asset. */
    internal var manifestOverride: String? = null
        set(value) {
            field = value
            cachedSpecs = null
            refreshStates()
        }

    @Serializable
    class ManifestFile(val name: String, val url: String, val sha256: String, val size: Long)

    @Serializable
    class ModelSpec(
        val id: String,
        val version: Int,
        val modelName: String,
        val license: String,
        val attribution: String,
        val dialectNote: String = "",
        val files: List<ManifestFile>,
    )

    @Serializable
    private class ManifestRoot(val models: List<ModelSpec>)

    private val downloadMutex = Mutex()

    private val _states = MutableStateFlow(readAllStates())

    /** Per-model observable state for the settings screen. */
    val states: StateFlow<Map<String, LocalAiModelState>> = _states.asStateFlow()

    @Volatile
    private var cachedSpecs: Map<String, ModelSpec>? = null

    /** All models known to the bundled manifest, in display order. */
    fun specs(): List<ModelSpec> = loadSpecs().values.toList()

    fun spec(modelId: String): ModelSpec? = loadSpecs()[modelId]

    /** Current state of one model. */
    fun stateOf(modelId: String): LocalAiModelState =
        _states.value[modelId] ?: LocalAiModelState.NotInstalled

    /**
     * Directory holding the verified current install of [modelId], or null.
     * "Verified" means the completion marker exists: it is written only after
     * every file's SHA-256 passed. A directory from an older build without a
     * marker is verified lazily once (hashes re-checked, marker written); a
     * directory whose files fail verification is NOT an install: the bad file
     * is deleted so a future download restarts it, and the model reports as
     * not installed instead of loading corrupt weights.
     */
    fun modelDir(modelId: String): File? {
        val spec = spec(modelId) ?: return null
        val root = File(context.filesDir, MODEL_ROOT)
        val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("${modelId}_v") }
            ?.sortedByDescending { it.name.removePrefix("${modelId}_v").toIntOrNull() ?: -1 }
            ?: return null
        for (dir in dirs) {
            val sizesMatch = spec.files.all {
                val f = File(dir, it.name)
                f.isFile && f.length() == it.size
            }
            if (!sizesMatch) continue
            if (File(dir, VERIFIED_MARKER).isFile) return dir
            if (verifyAndMark(dir, spec)) return dir
        }
        return null
    }

    /** One-time hash check for pre-marker installs; writes the marker on success. */
    private fun verifyAndMark(dir: File, spec: ModelSpec): Boolean {
        for (file in spec.files) {
            val onDisk = File(dir, file.name)
            if (!onDisk.isFile || sha256(onDisk) != file.sha256) {
                onDisk.delete()
                return false
            }
        }
        File(dir, VERIFIED_MARKER).writeText("${spec.version}")
        return true
    }

    fun isInstalled(modelId: String): Boolean = modelDir(modelId) != null

    fun modelFile(modelId: String, name: String): File? =
        modelDir(modelId)?.let { File(it, name) }?.takeIf { it.isFile }

    fun installedVersion(modelId: String): Int? =
        modelDir(modelId)?.name?.removePrefix("${modelId}_v")?.toIntOrNull()

    fun installedSizeBytes(modelId: String): Long =
        modelDir(modelId)?.listFiles()?.sumOf { it.length() } ?: 0L

    /** Parses the bundled manifest (assets/local_ai/model_manifest.json). */
    private fun loadSpecs(): Map<String, ModelSpec> {
        cachedSpecs?.let { return it }
        val specs = (manifestOverride ?: runCatching {
            context.assets.open("$ASSET_DIR/model_manifest.json").bufferedReader().use { it.readText() }
        }.getOrNull())?.let { text ->
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<ManifestRoot>(text).models
            }.getOrNull()
        }?.associateBy { it.id } ?: emptyMap()
        cachedSpecs = specs
        return specs
    }

    /**
     * Downloads and verifies one model. Requires an unmetered connection when
     * [preferWifi] is set (the user's choice in settings); throws
     * [MeteredConnectionException] instead of burning mobile data without consent.
     * Partial files are kept so an interrupted download resumes with HTTP Range.
     * The model is loadable only after every file's SHA-256 verifies.
     */
    suspend fun download(modelId: String, preferWifi: Boolean) {
        downloadMutex.withLock {
            val spec = spec(modelId)
                ?: throw ModelManagerException("manifest_missing", "Unknown model id: $modelId")
            validateSpec(spec)
            if (preferWifi && !isUnmetered()) {
                throw MeteredConnectionException()
            }
            val targetDir = File(context.filesDir, "$MODEL_ROOT/${modelId}_v${spec.version}")
            val total = spec.files.sumOf { it.size }
            // Free-space gate: fail BEFORE the first byte instead of after
            // burning hundreds of MB of the user's data on a disk that
            // cannot hold the model anyway.
            val usable = context.filesDir.usableSpace
            val needed = total + MIN_FREE_SPACE_MARGIN_BYTES
            if (usable < needed) {
                throw ModelManagerException(
                    "no_space",
                    "Not enough free storage: need ${needed / 1_000_000}MB, have ${usable / 1_000_000}MB",
                )
            }
            var downloadedSoFar = 0L
            try {
                spec.files.forEachIndexed { index, file ->
                    emitState(modelId, LocalAiModelState.Downloading(file.name, index + 1, spec.files.size, downloadedSoFar, total))
                    downloadFile(file, targetDir) { bytes ->
                        emitState(modelId, LocalAiModelState.Downloading(file.name, index + 1, spec.files.size, downloadedSoFar + bytes, total))
                    }
                    downloadedSoFar += file.size
                }
                emitState(modelId, LocalAiModelState.Verifying)
                for (file in spec.files) {
                    val onDisk = File(targetDir, file.name)
                    if (!onDisk.isFile || onDisk.length() != file.size || sha256(onDisk) != file.sha256) {
                        onDisk.delete()
                        throw ModelManagerException("verify_failed", "SHA-256 mismatch for ${file.name}; deleted the bad file")
                    }
                }
                // Completion marker: only a fully verified install carries it,
                // and modelDir refuses directories without it (see modelDir).
                File(targetDir, VERIFIED_MARKER).writeText("${spec.version}")
                cleanupOldVersions(modelId, keepVersion = spec.version)
                emitState(modelId, LocalAiModelState.Installed(spec.version, installedSizeBytes(modelId)))
            } catch (cancelled: CancellationException) {
                emitState(modelId, LocalAiModelState.NotInstalled)
                throw cancelled
            } catch (error: Exception) {
                emitState(modelId, LocalAiModelState.Failed(error.message ?: error.javaClass.simpleName))
                throw error
            }
        }
    }

    /**
     * Deletes one installed model and its partial downloads. Serialized with
     * downloads on the same mutex so a delete can never race an in-flight
     * install of the same model.
     */
    suspend fun deleteModel(modelId: String) {
        downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                val root = File(context.filesDir, MODEL_ROOT)
                root.listFiles { f -> f.isDirectory && f.name.startsWith("${modelId}_v") }
                    ?.forEach { it.deleteRecursively() }
                emitState(modelId, LocalAiModelState.NotInstalled)
            }
        }
    }

    /** Manifest hygiene: reject traversal filenames and non-HTTPS URLs before any byte moves. */
    private fun validateSpec(spec: ModelSpec) {
        for (file in spec.files) {
            if (file.name.isBlank() || File(file.name).name != file.name || file.name.startsWith(".")) {
                throw ModelManagerException("manifest_bad_name", "Unsafe file name in manifest: ${file.name}")
            }
            if (!file.url.startsWith("https://")) {
                throw ModelManagerException("manifest_bad_url", "Non-HTTPS URL in manifest for ${file.name}")
            }
            if (file.size <= 0) {
                throw ModelManagerException("manifest_bad_size", "Non-positive size in manifest for ${file.name}")
            }
        }
    }

    /** Recomputes all states from disk (app start / settings open). */
    fun refreshStates() {
        _states.value = readAllStates()
    }

    private fun readAllStates(): Map<String, LocalAiModelState> {
        val map = LinkedHashMap<String, LocalAiModelState>()
        for (spec in loadSpecs().values) {
            val version = modelDir(spec.id)?.name?.removePrefix("${spec.id}_v")?.toIntOrNull()
            map[spec.id] = if (version != null) {
                LocalAiModelState.Installed(version, installedSizeBytes(spec.id))
            } else {
                LocalAiModelState.NotInstalled
            }
        }
        return map
    }

    private fun emitState(modelId: String, state: LocalAiModelState) {
        _states.value = _states.value + (modelId to state)
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Streams one file to disk, resuming a kept .part file with HTTP Range when possible. */
    private suspend fun downloadFile(
        file: ManifestFile,
        targetDir: File,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val finalFile = File(targetDir, file.name)
        if (finalFile.isFile && finalFile.length() == file.size) return@withContext
        val partFile = File(targetDir, "${file.name}.part")
        var existing = if (partFile.isFile) partFile.length() else 0L
        if (existing > file.size) {
            partFile.delete()
            existing = 0L
        }
        val request = Request.Builder().url(file.url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        val call = okHttp.newCall(request)
        activeCall = call
        // A cancelled coroutine must actually cut the socket: ensureActive in
        // the copy loop only fires between 64KB chunks, and a stalled read
        // would otherwise hang the cancel until the server moves.
        currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
        call.execute().use { response ->
            if (existing > 0 && response.code == 200) {
                // Server ignored the Range header: restart the file cleanly.
                partFile.delete()
                existing = 0L
            } else if (existing > 0 && response.code == 206) {
                // Validate the server resumed where we asked: a mismatched
                // Content-Range would corrupt the file silently.
                val range = response.header("Content-Range")
                val rangeStart = range
                    ?.removePrefix("bytes")
                    ?.trim()
                    ?.substringBefore('-')
                    ?.toLongOrNull()
                if (rangeStart != existing) {
                    partFile.delete()
                    existing = 0L
                    throw ModelManagerException(
                        "range_mismatch",
                        "Bad Content-Range for ${file.name}: $range; restart the download",
                    )
                }
            }
            if (response.code !in 200..299 && response.code != 206) {
                throw ModelManagerException("http_${response.code}", "HTTP ${response.code} downloading ${file.name}")
            }
            val body = response.body ?: throw ModelManagerException("empty_body", "Empty body for ${file.name}")
            body.byteStream().use { input ->
                java.io.FileOutputStream(partFile, /* append = */ existing > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = existing
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
        }
        if (partFile.length() != file.size) {
            throw ModelManagerException(
                "size_mismatch",
                "${file.name}: expected ${file.size} bytes, got ${partFile.length()} (kept for resume)",
            )
        }
        if (!partFile.renameTo(finalFile)) {
            throw ModelManagerException("rename_failed", "Could not finalize ${file.name}")
        }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    /** The in-flight download call, so cancellation can cut the socket. */
    @Volatile
    private var activeCall: okhttp3.Call? = null

    /** Cancels the in-flight download, if any; the coroutine unwinds as CancellationException. */
    fun cancelActiveDownload() {
        activeCall?.cancel()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes older version dirs of [modelId] once a new version verified cleanly. */
    private fun cleanupOldVersions(modelId: String, keepVersion: Int) {
        val root = File(context.filesDir, MODEL_ROOT)
        root.listFiles { f -> f.isDirectory && f.name.startsWith("${modelId}_v") }
            ?.filter { (it.name.removePrefix("${modelId}_v").toIntOrNull() ?: keepVersion) != keepVersion }
            ?.forEach { it.deleteRecursively() }
    }

    class ModelManagerException(val code: String, message: String) : Exception(message)

    /** Thrown when a Wi-Fi-preferred download is attempted on a metered connection. */
    class MeteredConnectionException : Exception("wifi_required")

    companion object {
        const val MODEL_MARIAN = "marian-enar"
        const val MODEL_TRANSLATEGEMMA = "translategemma-4b"
        private const val MODEL_ROOT = "local_ai"
        private const val ASSET_DIR = "local_ai"

        /** Written into an install dir only after every file's SHA-256 passed. */
        private const val VERIFIED_MARKER = ".verified"

        /** Extra headroom the free-space gate demands beyond the model size. */
        private const val MIN_FREE_SPACE_MARGIN_BYTES = 200L * 1_000_000L
    }
}
