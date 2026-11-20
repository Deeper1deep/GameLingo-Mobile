package com.gamelingo.mobile.ui.update

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.R
import com.gamelingo.mobile.data.update.UpdateError
import com.gamelingo.mobile.data.update.UpdateUiState
import java.io.File
import java.util.Locale

/**
 * The update prompt shown over whatever screen is open: changelog and size,
 * download progress, verification result, then the system installer. Mandatory
 * updates cannot be dismissed.
 */
@Composable
fun UpdateDialogHost(
    state: UpdateUiState,
    viewModel: UpdateViewModel,
) {
    when (state) {
        is UpdateUiState.Available -> AvailableDialog(state, viewModel)
        is UpdateUiState.Downloading -> DownloadingDialog(state)
        is UpdateUiState.ReadyToInstall -> ReadyDialog(state, viewModel)
        is UpdateUiState.Failed -> FailedDialog(state, viewModel::retry, viewModel::dismiss)
        else -> Unit
    }
}

@Composable
private fun AvailableDialog(
    state: UpdateUiState.Available,
    viewModel: UpdateViewModel,
) {
    val context = LocalContext.current
    var needsInstallPermission by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!state.mandatory) viewModel.dismiss() },
        title = {
            Text(
                stringResource(
                    if (state.mandatory) R.string.update_mandatory_title
                    else R.string.update_available_title,
                ),
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.update_version_line,
                        state.release.latestVersion,
                        state.release.releaseDate,
                    ),
                )
                state.sizeBytes?.let {
                    Text(stringResource(R.string.update_size_line, formatMegabytes(it)))
                }
                Spacer(Modifier.height(8.dp))
                Text(state.release.changelog, style = MaterialTheme.typography.bodyMedium)
                if (needsInstallPermission) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_need_install_permission),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (viewModel.apkInstaller.canRequestInstalls()) {
                        viewModel.startDownload(state.release, state.mandatory)
                    } else {
                        needsInstallPermission = true
                        runCatching {
                            context.startActivity(viewModel.apkInstaller.unknownSourcesSettingsIntent())
                        }
                    }
                },
            ) { Text(stringResource(R.string.update_download_and_install)) }
        },
        dismissButton = if (state.mandatory) {
            null
        } else {
            @Composable {
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.update_later))
                }
            }
        },
    )
}

@Composable
private fun DownloadingDialog(state: UpdateUiState.Downloading) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_downloading_title)) },
        text = {
            Column {
                val total = state.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.update_progress_line,
                            formatMegabytes(state.downloadedBytes),
                            formatMegabytes(total),
                        ),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.update_downloaded_line,
                            formatMegabytes(state.downloadedBytes),
                        ),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ReadyDialog(
    state: UpdateUiState.ReadyToInstall,
    viewModel: UpdateViewModel,
) {
    val context = LocalContext.current
    var launchFailed by remember { mutableStateOf(false) }
    // Open the system installer as soon as verification succeeds.
    LaunchedEffect(state.apkPath) {
        val apk: File? = viewModel.verifiedApk(state)
        if (apk != null) {
            try {
                context.startActivity(viewModel.apkInstaller.installIntent(apk))
            } catch (_: ActivityNotFoundException) {
                launchFailed = true
            }
        } else {
            launchFailed = true
        }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_ready_title)) },
        text = {
            Text(
                stringResource(
                    if (launchFailed) R.string.update_ready_failed
                    else R.string.update_ready_body,
                ),
            )
        },
        confirmButton = {},
    )
}

@Composable
private fun FailedDialog(
    state: UpdateUiState.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.mandatory) onDismiss() },
        title = { Text(stringResource(R.string.update_failed_title)) },
        text = { Text(stringResource(errorText(state.error))) },
        confirmButton = {
            Button(onClick = onRetry) { Text(stringResource(R.string.update_retry)) }
        },
        dismissButton = if (state.mandatory) {
            null
        } else {
            @Composable {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            }
        },
    )
}

private fun errorText(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.BAD_METADATA -> R.string.update_error_metadata
    UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
    UpdateError.PACKAGE_MISMATCH -> R.string.update_error_package
    UpdateError.WRITE_FAILED -> R.string.update_error_write
}

private fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
