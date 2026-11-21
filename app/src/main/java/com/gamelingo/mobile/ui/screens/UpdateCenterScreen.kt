package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.BuildConfig
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.update.LatestRelease
import com.gamelingo.mobile.data.update.UpdateError
import com.gamelingo.mobile.data.update.UpdateUiState
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.InfoRow
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SkeletonBox
import com.gamelingo.mobile.ui.components.StatusChip
import com.gamelingo.mobile.ui.components.StatusTone
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import com.gamelingo.mobile.ui.update.UpdateViewModel
import java.io.File
import java.util.Locale

/**
 * Update Center: the existing update system (latest.json from GameLingo-Updates,
 * SHA-256 + signature + package verification, system installer) presented as a
 * full screen. It reads and drives the same UpdateRepository state as the
 * startup dialog; no update logic lives here.
 */
@Composable
fun UpdateCenterScreen(
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var needsInstallPermission by remember { mutableStateOf(false) }

    val release: LatestRelease? = when (val s = state) {
        is UpdateUiState.Available -> s.release
        is UpdateUiState.Downloading -> s.release
        is UpdateUiState.ReadyToInstall -> s.release
        else -> null
    }
    val sizeBytes: Long? = when (val s = state) {
        is UpdateUiState.Available -> s.sizeBytes
        is UpdateUiState.Downloading -> s.totalBytes ?: s.release.fileSizeBytes
        is UpdateUiState.ReadyToInstall -> s.release.fileSizeBytes
        else -> null
    }
    val upToDate = state is UpdateUiState.UpToDate

    SettingsColumn {
        // Versions and status
        GlCard(elevated = true) {
            InfoRow(
                label = stringResource(R.string.update_current),
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            InfoRow(
                label = stringResource(R.string.update_latest),
                value = when {
                    release != null -> release.latestVersion
                    upToDate -> BuildConfig.VERSION_NAME
                    else -> stringResource(R.string.update_unknown)
                },
            )
            StatusChip(
                label = statusLabel(state),
                tone = statusTone(state),
                pulsing = state is UpdateUiState.Checking || state is UpdateUiState.Downloading,
                modifier = Modifier.padding(top = GlSpacing.sm),
            )
        }

        if (state is UpdateUiState.Checking) {
            GlCard {
                SkeletonBox(height = 18.dp)
                SkeletonBox(modifier = Modifier.padding(top = GlSpacing.sm), height = 18.dp)
            }
        }

        if (state is UpdateUiState.Idle) {
            HintText(stringResource(R.string.update_no_release_info))
        }

        // Release details
        if (release != null) {
            SectionHeader(stringResource(R.string.update_changelog))
            GlCard {
                Text(
                    text = release.changelog,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.TextPrimary,
                )
                InfoRow(
                    label = stringResource(R.string.update_release_date),
                    value = release.releaseDate,
                    modifier = Modifier.padding(top = GlSpacing.md),
                )
                InfoRow(
                    label = stringResource(R.string.update_apk_size),
                    value = sizeBytes?.let {
                        stringResource(R.string.update_size_mb, formatMb(it))
                    } ?: stringResource(R.string.update_unknown),
                )
            }
        }

        // Download progress
        (state as? UpdateUiState.Downloading)?.let { downloading ->
            GlCard {
                val total = downloading.totalBytes
                if (total != null && total > 0) {
                    val fraction = (downloading.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = GlColors.Primary,
                        trackColor = GlColors.OutlineStrong,
                    )
                    Text(
                        text = stringResource(
                            R.string.update_progress_line,
                            formatMb(downloading.downloadedBytes),
                            formatMb(total),
                        ) + "  (" + (fraction * 100).toInt() + "%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlColors.TextSecondary,
                        modifier = Modifier.padding(top = GlSpacing.sm),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = GlColors.Primary,
                        trackColor = GlColors.OutlineStrong,
                    )
                    Text(
                        text = stringResource(
                            R.string.update_downloaded_line,
                            formatMb(downloading.downloadedBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = GlColors.TextSecondary,
                        modifier = Modifier.padding(top = GlSpacing.sm),
                    )
                }
            }
        }

        // File verification
        if (state is UpdateUiState.Downloading ||
            state is UpdateUiState.ReadyToInstall ||
            (state as? UpdateUiState.Failed)?.error in verificationErrors
        ) {
            SectionHeader(stringResource(R.string.update_verification))
            GlCard {
                val ok = state is UpdateUiState.ReadyToInstall
                val failed = state is UpdateUiState.Failed
                StatusChip(
                    label = stringResource(
                        when {
                            ok -> R.string.update_status_verified
                            failed -> R.string.update_status_failed
                            else -> R.string.update_status_downloading
                        },
                    ),
                    tone = when {
                        ok -> StatusTone.Success
                        failed -> StatusTone.Error
                        else -> StatusTone.Info
                    },
                )
                HintText(
                    text = stringResource(
                        when {
                            ok -> R.string.update_verification_ok
                            failed -> R.string.update_verification_failed
                            else -> R.string.update_verification_pending
                        },
                    ),
                    modifier = Modifier.padding(top = GlSpacing.sm),
                )
            }
        }

        (state as? UpdateUiState.Failed)?.let { failed ->
            GlCard {
                Text(
                    text = stringResource(errorText(failed.error)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.Error,
                )
            }
        }

        if (needsInstallPermission) {
            HintText(stringResource(R.string.update_need_install_permission))
        }

        // Actions
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GlSpacing.sm)) {
            when (val s = state) {
                is UpdateUiState.Available -> GlButton(
                    text = stringResource(R.string.update_download),
                    onClick = {
                        if (viewModel.apkInstaller.canRequestInstalls()) {
                            needsInstallPermission = false
                            viewModel.startDownload(s.release, s.mandatory)
                        } else {
                            needsInstallPermission = true
                            runCatching {
                                context.startActivity(
                                    viewModel.apkInstaller.unknownSourcesSettingsIntent(),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                is UpdateUiState.ReadyToInstall -> GlButton(
                    text = stringResource(R.string.update_install),
                    onClick = {
                        val apk: File? = viewModel.verifiedApk(s)
                        if (apk != null) {
                            runCatching {
                                context.startActivity(viewModel.apkInstaller.installIntent(apk))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
            GlButton(
                text = stringResource(R.string.update_check),
                onClick = viewModel::checkNow,
                style = if (state is UpdateUiState.Available || state is UpdateUiState.ReadyToInstall) {
                    GlButtonStyle.Secondary
                } else {
                    GlButtonStyle.Primary
                },
                icon = Icons.Filled.Refresh,
                loading = state is UpdateUiState.Checking,
                enabled = state !is UpdateUiState.Downloading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val verificationErrors = setOf(
    UpdateError.CHECKSUM_MISMATCH,
    UpdateError.SIGNATURE_MISMATCH,
    UpdateError.PACKAGE_MISMATCH,
    UpdateError.WRITE_FAILED,
)

@Composable
private fun statusLabel(state: UpdateUiState): String = stringResource(
    when (state) {
        UpdateUiState.Idle -> R.string.update_status_idle
        UpdateUiState.Checking -> R.string.update_status_checking
        UpdateUiState.UpToDate -> R.string.update_status_uptodate
        is UpdateUiState.Available -> R.string.update_status_available
        is UpdateUiState.Downloading -> R.string.update_status_downloading
        is UpdateUiState.ReadyToInstall -> R.string.update_status_verified
        is UpdateUiState.Failed -> R.string.update_status_failed
    },
)

private fun statusTone(state: UpdateUiState): StatusTone = when (state) {
    UpdateUiState.Idle -> StatusTone.Neutral
    UpdateUiState.Checking -> StatusTone.Info
    UpdateUiState.UpToDate -> StatusTone.Success
    is UpdateUiState.Available -> StatusTone.Accent
    is UpdateUiState.Downloading -> StatusTone.Info
    is UpdateUiState.ReadyToInstall -> StatusTone.Success
    is UpdateUiState.Failed -> StatusTone.Error
}

private fun errorText(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.BAD_METADATA -> R.string.update_error_metadata
    UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
    UpdateError.PACKAGE_MISMATCH -> R.string.update_error_package
    UpdateError.WRITE_FAILED -> R.string.update_error_write
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
