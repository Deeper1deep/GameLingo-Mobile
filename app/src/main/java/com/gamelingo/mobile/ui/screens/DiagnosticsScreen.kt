package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.CaptureHealth
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.timing.PipelineTiming
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.InfoRow
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import kotlinx.coroutines.delay

/**
 * Live diagnostics: what the capture and translation pipeline is actually
 * doing right now. Reads the always-on liveness record ([CaptureHealth]),
 * the worker status stream and the last end-to-end timing; refreshes every
 * second while visible. Answers "is it stuck, and where" without a logcat.
 */
@Composable
fun DiagnosticsScreen(liveClock: Boolean = true) {
    val workerStatus by TranslationSessionBus.workerStatus.collectAsStateWithLifecycle()
    val sessionState by TranslationSessionBus.state.collectAsStateWithLifecycle()

    // Snapshot + clock tick: ages re-render once per second while visible.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(liveClock) {
        while (liveClock) {
            delay(1_000)
            tick = System.currentTimeMillis()
        }
    }
    val snapshot = CaptureHealth.snapshot()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GlSpacing.md),
    ) {
        Text(
            text = stringResource(R.string.nav_diagnostics),
            style = MaterialTheme.typography.headlineMedium,
            color = GlColors.TextPrimary,
        )

        SectionHeader(stringResource(R.string.diag_section_capture))
        GlCard {
            InfoRow(
                label = stringResource(R.string.diag_session_state),
                value = sessionState.name,
            )
            InfoRow(
                label = stringResource(R.string.diag_last_frame),
                value = ago(snapshot.lastFrameAcquiredAt, tick),
            )
            InfoRow(
                label = stringResource(R.string.diag_last_ocr),
                value = ago(snapshot.lastOcrRunAt, tick),
            )
            InfoRow(
                label = stringResource(R.string.diag_last_ocr_skipped),
                value = ago(snapshot.lastOcrSkippedAt, tick),
            )
            InfoRow(
                label = stringResource(R.string.diag_last_line),
                value = ago(snapshot.lastLinePublishedAt, tick),
            )
            InfoRow(
                label = stringResource(R.string.diag_last_rebuild),
                value = ago(snapshot.lastDisplayRebuildAt, tick),
            )
            InfoRow(
                label = stringResource(R.string.diag_last_stop),
                value = stopReasonText(snapshot.lastStopReason, snapshot.lastStopAt, tick),
            )
        }

        SectionHeader(stringResource(R.string.diag_section_worker))
        GlCard {
            InfoRow(
                label = stringResource(R.string.diag_worker_state),
                value = workerStatus.state.name,
            )
            InfoRow(
                label = stringResource(R.string.diag_queue_depth),
                value = workerStatus.queueDepth.toString(),
            )
            InfoRow(
                label = stringResource(R.string.diag_recovery_count),
                value = workerStatus.recoveryCount.toString(),
            )
            InfoRow(
                label = stringResource(R.string.diag_last_success),
                value = ago(workerStatus.lastSuccessAtMillis, tick),
            )
            InfoRow(
                label = stringResource(R.string.diag_cooldown),
                value = if (workerStatus.cooldownUntilMillis == Long.MAX_VALUE) {
                    stringResource(R.string.diag_cooldown_blocked)
                } else if (workerStatus.cooldownUntilMillis > tick) {
                    stringResource(
                        R.string.diag_cooldown_fmt,
                        ((workerStatus.cooldownUntilMillis - tick) / 1_000L).coerceAtLeast(0L),
                    )
                } else {
                    stringResource(R.string.diag_none)
                },
            )
            workerStatus.lastError?.let { error ->
                HintText(stringResource(R.string.diag_last_error_fmt, error))
            }
            workerStatus.lastProviderLimit?.let { limit ->
                HintText(
                    stringResource(
                        R.string.diag_last_provider_limit_fmt,
                        limit,
                        ago(workerStatus.lastProviderLimitAtMillis, tick),
                    ),
                )
            }
        }

        SectionHeader(stringResource(R.string.diag_section_timing))
        GlCard {
            val endToEnd = PipelineTiming.lastEndToEndMs()
            InfoRow(
                label = stringResource(R.string.diag_end_to_end),
                value = if (endToEnd >= 0) {
                    stringResource(R.string.diag_ms_fmt, endToEnd)
                } else {
                    stringResource(R.string.diag_none)
                },
            )
            HintText(stringResource(R.string.diag_timing_hint))
        }

        val context = LocalContext.current
        GlCard {
            HintText(stringResource(R.string.diag_geometry_export_hint))
            GlButton(
                text = stringResource(R.string.diag_geometry_export),
                onClick = { shareGeometryReport(context) },
                style = GlButtonStyle.Secondary,
            )
        }

        snapshot.lastError?.let { error ->
            SectionHeader(stringResource(R.string.diag_last_error))
            GlCard {
                HintText(stringResource(R.string.diag_last_error_fmt, error))
            }
        }
    }
}

/** "12 ثانية" style age for a timestamp, or the placeholder when it never happened. */
/**
 * The recorded reason the last capture session stopped (user stop button,
 * the system ending screen sharing, the system killing the service, or a
 * failure), with how long ago it happened. "لسه" when no stop was recorded.
 */
@Composable
private fun stopReasonText(reason: String?, stopAtMillis: Long, now: Long): String {
    if (reason == null || stopAtMillis <= 0L) {
        return stringResource(R.string.diag_none)
    }
    val label = when (reason) {
        CaptureHealth.StopReasons.USER_BUTTON -> stringResource(R.string.diag_stop_user)
        CaptureHealth.StopReasons.PROJECTION_ENDED -> stringResource(R.string.diag_stop_projection)
        CaptureHealth.StopReasons.SERVICE_DESTROYED -> stringResource(R.string.diag_stop_service)
        CaptureHealth.StopReasons.MISSING_CONSENT_DATA,
        CaptureHealth.StopReasons.FOREGROUND_START_FAILED,
        CaptureHealth.StopReasons.PROJECTION_REJECTED,
        CaptureHealth.StopReasons.SETTINGS_UNAVAILABLE,
        CaptureHealth.StopReasons.CAPTURE_PERSISTENT_FAILURE,
        -> stringResource(R.string.diag_stop_error)

        else -> stringResource(R.string.diag_stop_unknown)
    }
    return stringResource(R.string.diag_stop_fmt, label, ago(stopAtMillis, now))
}

@Composable
private fun ago(timestampMillis: Long, now: Long): String =
    if (timestampMillis <= 0L) {
        stringResource(R.string.diag_none)
    } else {
        val seconds = ((now - timestampMillis) / 1_000L).coerceAtLeast(0L)
        stringResource(R.string.diag_ago_fmt, seconds)
    }

/** Offers the geometry report through the share sheet. */
private fun shareGeometryReport(context: android.content.Context) {
    runCatching {
        context.startActivity(
            com.gamelingo.mobile.core.diagnostics.GeometryReportExporter.createShareIntent(
                context, context.getString(R.string.diag_geometry_share_title),
            ),
        )
    }.onFailure { android.util.Log.w("Diagnostics", "geometry export failed", it) }
}
