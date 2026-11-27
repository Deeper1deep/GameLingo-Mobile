package com.gamelingo.mobile.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.engine.EngineHealth
import com.gamelingo.mobile.core.engine.EngineKey
import com.gamelingo.mobile.core.engine.EngineRow
import com.gamelingo.mobile.core.engine.EngineStatus
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.InfoRow
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.navigation.EngineStatusViewModel
import com.gamelingo.mobile.ui.theme.GlColors

/** Read-only: shows what each engine is doing. It has no buttons, so it cannot start a download. */
@Composable
fun EngineStatusScreen(viewModel: EngineStatusViewModel = hiltViewModel()) {
    var status by remember { mutableStateOf<EngineStatus?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            status = viewModel.snapshot()
            kotlinx.coroutines.delay(2_000)
        }
    }
    status?.let { EngineStatusContent(it) }
}

@Composable
fun EngineStatusContent(status: EngineStatus) {
    SettingsColumn {
        SectionHeader(stringResource(R.string.engine_status_title))
        GlCard {
            InfoRow(label = stringResource(R.string.engine_planned), value = status.plannedEngineId)
            HintText(stringResource(R.string.engine_status_hint))
        }
        status.advice?.let { adv ->
            GlCard {
                Text(
                    text = stringResource(R.string.engine_memory_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlColors.TextPrimary,
                )
                InfoRow(
                    label = stringResource(R.string.engine_memory_level),
                    value = stringResource(
                        when (adv.level) {
                            com.gamelingo.mobile.core.engine.ResourceAdvisor.Level.COMFORTABLE -> R.string.engine_mem_ok
                            com.gamelingo.mobile.core.engine.ResourceAdvisor.Level.TIGHT -> R.string.engine_mem_tight
                            com.gamelingo.mobile.core.engine.ResourceAdvisor.Level.RISKY -> R.string.engine_mem_risky
                        },
                    ),
                )
                InfoRow(label = stringResource(R.string.engine_mem_available), value = "\u2066${adv.availableMb} MB\u2069")
                adv.appPssMb?.let { InfoRow(label = stringResource(R.string.engine_mem_app), value = "\u2066$it MB\u2069") }
                InfoRow(label = stringResource(R.string.engine_mem_files), value = "\u2066${adv.modelStorageMb} MB\u2069")
                HintText(stringResource(R.string.engine_memory_hint))
            }
        }
        status.rows.forEach { row ->
            GlCard {
                Text(
                    text = stringResource(nameOf(row.key)),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlColors.TextPrimary,
                )
                InfoRow(label = stringResource(R.string.engine_state), value = stateText(row))
            }
        }
    }
}

private fun nameOf(k: EngineKey): Int = when (k) {
    EngineKey.GEMMA -> R.string.engine_gemma
    EngineKey.MARIAN -> R.string.engine_marian
    EngineKey.ASR -> R.string.engine_asr
    EngineKey.TTS -> R.string.engine_tts
}

@Composable
private fun stateText(row: EngineRow): String {
    val base = stringResource(
        when (row.health) {
            EngineHealth.READY -> R.string.engine_ready
            EngineHealth.OFF -> R.string.engine_off
            EngineHealth.NOT_INSTALLED -> R.string.engine_not_installed
            EngineHealth.DOWNLOADING -> R.string.engine_downloading
            EngineHealth.VERIFYING -> R.string.engine_verifying
            EngineHealth.FAILED -> R.string.engine_failed
        },
    )
    val extra = when {
        row.percent != null -> " \u2066${row.percent}%\u2069"
        row.sizeBytes != null -> " (\u2066${row.sizeBytes / 1_000_000} MB\u2069)"
        row.reason != null -> " (${row.reason})"
        else -> ""
    }
    return base + extra
}
