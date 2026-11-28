package com.gamelingo.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.GlDialog
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.InfoRow
import com.gamelingo.mobile.ui.components.glTextFieldColors
import com.gamelingo.mobile.ui.navigation.HistoryViewModel
import com.gamelingo.mobile.ui.system.LocalSnackbarHostState
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

/** Days since the epoch in the device time zone: the grouping key for the list. */
private fun dayKey(millis: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val totalCount by viewModel.historyCount.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TranslationHistoryEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<TranslationHistoryEntity?>(null) }
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.history_copied)

    fun copy(label: String, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, text))
        scope.launch { snackbar?.showSnackbar(copiedMessage) }
    }

    val todayKey = dayKey(System.currentTimeMillis())
    val yesterdayKey = dayKey(
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis,
    )
    val groups = history.groupBy { dayKey(it.createdAt) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = GlSpacing.lg)
            .padding(top = GlSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_history),
                style = MaterialTheme.typography.headlineMedium,
                color = GlColors.TextPrimary,
            )
            if (totalCount > 0) {
                GlTextButton(
                    text = stringResource(R.string.history_clear_all),
                    onClick = { showClearDialog = true },
                    danger = true,
                )
            }
        }
        Text(
            text = if (query.isBlank() && totalCount > history.size && history.isNotEmpty()) {
                stringResource(R.string.history_showing_recent, history.size, totalCount)
            } else {
                stringResource(R.string.home_history_count, totalCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = GlColors.TextSecondary,
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = GlSpacing.md),
            singleLine = true,
            colors = glTextFieldColors(),
            shape = MaterialTheme.shapes.medium,
            placeholder = { Text(stringResource(R.string.history_search)) },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
        )

        when {
            history.isEmpty() && query.isBlank() -> EmptyMessage(
                text = stringResource(R.string.history_empty),
            )
            history.isEmpty() -> EmptyMessage(
                text = stringResource(R.string.history_no_results),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(GlSpacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = GlSpacing.lg,
                ),
            ) {
                groups.forEach { (day, entries) ->
                    item(key = "header-$day") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = GlSpacing.sm),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = when (day) {
                                    todayKey -> stringResource(R.string.history_today)
                                    yesterdayKey -> stringResource(R.string.history_yesterday)
                                    else -> DateFormat.getDateInstance().format(Date(day))
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = GlColors.Accent,
                            )
                            Text(
                                text = stringResource(R.string.history_group_count, entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = GlColors.TextSecondary,
                            )
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        GlCard(onClick = { selected = entry }) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = DateFormat.getTimeInstance(DateFormat.SHORT)
                                            .format(Date(entry.createdAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlColors.TextSecondary,
                                    )
                                    HistoryEntryTexts(entry)
                                }
                                IconButton(onClick = { pendingDelete = entry }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.history_delete),
                                        tint = GlColors.Error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        GlDialog(
            title = stringResource(R.string.history_detail_title),
            onDismiss = { selected = null },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { selected = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
                Text(
                    text = stringResource(R.string.history_translated),
                    style = MaterialTheme.typography.labelMedium,
                    color = GlColors.Secondary,
                )
                Text(
                    text = if (entry.isUntranslated) untranslatedLabel(entry.status) else entry.translatedText,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.isUntranslated) GlColors.Error else GlColors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.history_original),
                    style = MaterialTheme.typography.labelMedium,
                    color = GlColors.TextSecondary,
                )
                Text(
                    text = entry.sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.TextPrimary,
                )
                InfoRow(
                    label = stringResource(R.string.history_engine),
                    value = engineIdLabel(entry.engine),
                )
                InfoRow(
                    label = stringResource(R.string.history_source_label),
                    value = stringResource(
                        if (entry.wasCached) R.string.history_cached_yes else R.string.history_cached_no,
                    ),
                )
                InfoRow(
                    label = stringResource(R.string.history_time_label),
                    value = DateFormat.getDateTimeInstance().format(Date(entry.createdAt)),
                )
                GlButton(
                    text = stringResource(R.string.history_copy),
                    onClick = {
                        if (entry.isUntranslated) copy("original", entry.sourceText) else copy("translation", entry.translatedText)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                GlButton(
                    text = stringResource(R.string.history_copy_original),
                    onClick = { copy("original", entry.sourceText) },
                    style = GlButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        GlDialog(
            title = stringResource(R.string.history_delete_confirm_title),
            onDismiss = { pendingDelete = null },
            confirmText = stringResource(R.string.history_delete),
            dismissText = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                viewModel.deleteEntry(entry.id)
                if (selected?.id == entry.id) selected = null
                pendingDelete = null
            },
        ) {
            Text(
                text = stringResource(R.string.history_delete_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextSecondary,
            )
        }
    }

    if (showClearDialog) {
        GlDialog(
            title = stringResource(R.string.history_clear_confirm_title),
            onDismiss = { showClearDialog = false },
            confirmText = stringResource(R.string.history_clear_all),
            dismissText = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                viewModel.clearAll()
                showClearDialog = false
            },
        ) {
            Text(
                text = stringResource(R.string.history_clear_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = GlColors.TextSecondary,
        )
    }
}

/** The translation (or, for a saved original that was never translated, the reason) over the source text. */
@Composable
internal fun HistoryEntryTexts(entry: com.gamelingo.mobile.data.local.TranslationHistoryEntity) {
    Column {
        Text(
            text = if (entry.isUntranslated) untranslatedLabel(entry.status) else entry.translatedText,
            style = MaterialTheme.typography.titleMedium,
            color = if (entry.isUntranslated) GlColors.Error else GlColors.TextPrimary,
            modifier = Modifier.padding(top = GlSpacing.xs),
        )
        Text(
            text = entry.sourceText,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.isUntranslated) GlColors.TextPrimary else GlColors.TextSecondary,
        )
    }
}

@Composable
internal fun untranslatedLabel(status: String): String = stringResource(
    when (status.removePrefix(com.gamelingo.mobile.data.local.TranslationHistoryEntity.STATUS_PREFIX_UNTRANSLATED)) {
        "overflow" -> R.string.history_untranslated_overflow
        "expired" -> R.string.history_untranslated_expired
        "retries" -> R.string.history_untranslated_retries
        "stopped" -> R.string.history_untranslated_stopped
        else -> R.string.history_untranslated_failed
    },
)
