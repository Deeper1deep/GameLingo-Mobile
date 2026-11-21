package com.gamelingo.mobile.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.core.update.LatestRelease
import com.gamelingo.mobile.data.update.ApkInstaller
import com.gamelingo.mobile.data.update.UpdateRepository
import com.gamelingo.mobile.data.update.UpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Drives the in-app update check that runs when the app opens. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    val apkInstaller: ApkInstaller,
) : ViewModel() {

    val state: StateFlow<UpdateUiState> = repository.state

    /** Called once when the app opens; repeats only after an explicit retry. */
    fun checkOnAppOpen() {
        viewModelScope.launch { repository.checkOnce() }
    }

    fun retry() {
        viewModelScope.launch { repository.checkNow() }
    }

    /** User-triggered "Check for updates" from the Update Center. */
    fun checkNow() {
        viewModelScope.launch { repository.checkNow() }
    }

    fun startDownload(release: LatestRelease, mandatory: Boolean) {
        viewModelScope.launch { repository.downloadAndVerify(release, mandatory) }
    }

    fun verifiedApk(state: UpdateUiState.ReadyToInstall): File? = repository.verifiedApk(state)

    fun dismiss() = repository.dismiss()
}
