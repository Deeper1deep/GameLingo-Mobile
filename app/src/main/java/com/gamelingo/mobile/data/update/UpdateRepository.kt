package com.gamelingo.mobile.data.update

import android.content.Context
import com.gamelingo.mobile.BuildConfig
import com.gamelingo.mobile.core.update.LatestRelease
import com.gamelingo.mobile.core.update.UpdateDecision
import com.gamelingo.mobile.core.update.UpdateDecider
import com.gamelingo.mobile.core.util.Sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/** Why an update attempt failed; the UI maps each kind to an Arabic string. */
enum class UpdateError {
    NETWORK,
    BAD_METADATA,
    CHECKSUM_MISMATCH,
    SIGNATURE_MISMATCH,
    PACKAGE_MISMATCH,
    WRITE_FAILED,
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(
        val release: LatestRelease,
        val sizeBytes: Long?,
        val mandatory: Boolean,
    ) : UpdateUiState
    data class Downloading(
        val release: LatestRelease,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : UpdateUiState
    data class ReadyToInstall(val release: LatestRelease, val apkPath: String) : UpdateUiState
    data class Failed(val error: UpdateError, val mandatory: Boolean) : UpdateUiState
}

/**
 * Reads latest.json from GameLingo-Updates, downloads the APK to a temporary
 * file, verifies it (manifest validation + SHA-256 + package name + signing
 * certificate) and only then moves it into place for the system installer.
 * Only ever writes to its own updates/ directory: a failed or partial
 * download is deleted, and user data is never touched.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: UpdatesClient,
    private val signatureVerifier: SignatureVerifier,
) {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Serializes downloads: two downloads into the same file would corrupt it. */
    private val downloadMutex = Mutex()

    private val updatesDir: File
        get() = File(context.filesDir, UPDATES_DIR_NAME)

    /** One check per app start; later calls in the same session are ignored. */
    suspend fun checkOnce() {
        if (_state.value !is UpdateUiState.Idle) return
        checkNow()
    }

    /** User-triggered re-check (e.g. retry after a failure). */
    suspend fun checkNow() {
        _state.value = UpdateUiState.Checking
        val release = try {
            client.fetchLatestRelease()
        } catch (cancelled: CancellationException) {
            // Cancellation is not a network error: let it cancel the caller.
            throw cancelled
        } catch (parse: SerializationException) {
            _state.value = UpdateUiState.Failed(UpdateError.BAD_METADATA, mandatory = false)
            return
        } catch (illegal: IllegalArgumentException) {
            _state.value = UpdateUiState.Failed(UpdateError.BAD_METADATA, mandatory = false)
            return
        } catch (_: IOException) {
            _state.value = UpdateUiState.Failed(UpdateError.NETWORK, mandatory = false)
            return
        } catch (_: Exception) {
            _state.value = UpdateUiState.Failed(UpdateError.NETWORK, mandatory = false)
            return
        }
        when (val decision = UpdateDecider.decide(release, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID)) {
            is UpdateDecision.UpToDate -> _state.value = UpdateUiState.UpToDate
            is UpdateDecision.Available -> presentAvailable(release, decision.mandatory)
            is UpdateDecision.Forced -> presentAvailable(release, mandatory = true)
        }
    }

    private suspend fun presentAvailable(release: LatestRelease, mandatory: Boolean) {
        if (release.validationError() != null) {
            _state.value = UpdateUiState.Failed(UpdateError.BAD_METADATA, mandatory)
            return
        }
        val size = release.fileSizeBytes ?: client.fetchApkSizeBytes(release.downloadUrl)
        _state.value = UpdateUiState.Available(release, size, mandatory)
    }

    /**
     * Downloads the APK to a .part temp file, verifies the finished download,
     * then renames it into place. A temp file is deleted on any failure and
     * never reaches the installer path half-written. Concurrent calls are
     * serialized behind [downloadMutex].
     */
    suspend fun downloadAndVerify(release: LatestRelease, mandatory: Boolean) {
        downloadMutex.withLock {
            downloadAndVerifyLocked(release, mandatory)
        }
    }

    private suspend fun downloadAndVerifyLocked(release: LatestRelease, mandatory: Boolean) {
        updatesDir.mkdirs()
        // Clear older downloaded APKs only; nothing else lives in this directory.
        updatesDir.listFiles()?.forEach { it.delete() }
        val tempFile = File(updatesDir, APK_TEMP_FILE_NAME)
        val dest = File(updatesDir, APK_FILE_NAME)
        _state.value = UpdateUiState.Downloading(release, 0L, release.fileSizeBytes)
        try {
            client.downloadApk(release.downloadUrl, tempFile) { downloaded, total ->
                _state.value = UpdateUiState.Downloading(release, downloaded, total)
            }
        } catch (cancelled: CancellationException) {
            tempFile.delete()
            throw cancelled
        } catch (_: Exception) {
            tempFile.delete()
            _state.value = UpdateUiState.Failed(UpdateError.NETWORK, mandatory)
            return
        }
        if (!Sha256.matches(tempFile, release.sha256)) {
            tempFile.delete()
            _state.value = UpdateUiState.Failed(UpdateError.CHECKSUM_MISMATCH, mandatory)
            return
        }
        val apkPackage = signatureVerifier.apkPackageName(tempFile)
        if (apkPackage == null) {
            tempFile.delete()
            _state.value = UpdateUiState.Failed(UpdateError.WRITE_FAILED, mandatory)
            return
        }
        if (apkPackage != BuildConfig.APPLICATION_ID) {
            tempFile.delete()
            _state.value = UpdateUiState.Failed(UpdateError.PACKAGE_MISMATCH, mandatory)
            return
        }
        if (!signatureVerifier.isGenuineUpdate(tempFile)) {
            tempFile.delete()
            _state.value = UpdateUiState.Failed(UpdateError.SIGNATURE_MISMATCH, mandatory)
            return
        }
        // Fully verified: atomically move into the install slot.
        dest.delete()
        if (!tempFile.renameTo(dest)) {
            tempFile.delete()
            _state.value = UpdateUiState.Failed(UpdateError.WRITE_FAILED, mandatory)
            return
        }
        _state.value = UpdateUiState.ReadyToInstall(release, dest.absolutePath)
    }

    /** The verified APK file referenced by a ReadyToInstall state, if still present. */
    fun verifiedApk(state: UpdateUiState.ReadyToInstall): File? =
        File(state.apkPath).takeIf { it.exists() }

    /** Dismiss a non-mandatory prompt; mandatory updates stay on screen. */
    fun dismiss() {
        val current = _state.value
        val mandatory = when (current) {
            is UpdateUiState.Available -> current.mandatory
            is UpdateUiState.Failed -> current.mandatory
            else -> false
        }
        if (!mandatory) _state.value = UpdateUiState.Idle
    }

    private companion object {
        const val UPDATES_DIR_NAME = "updates"
        const val APK_FILE_NAME = "gamelingo-latest.apk"
        const val APK_TEMP_FILE_NAME = "gamelingo-latest.apk.part"
    }
}
