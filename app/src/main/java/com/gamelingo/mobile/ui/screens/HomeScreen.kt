package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.navigation.GameLingoRoutes
import com.gamelingo.mobile.ui.navigation.HistoryViewModel
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startPendingMessage = stringResource(R.string.home_start_pending)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_headline),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.home_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.home_history_count, historyCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch { snackbarHostState.showSnackbar(startPendingMessage) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_start))
                }
            }
        }

        val entries = listOf(
            GameLingoRoutes.TRANSLATION_SETTINGS to R.string.nav_translation_settings,
            GameLingoRoutes.OCR_SETTINGS to R.string.nav_ocr_settings,
            GameLingoRoutes.AI_SETTINGS to R.string.nav_ai_settings,
            GameLingoRoutes.OVERLAY_SETTINGS to R.string.nav_overlay_settings,
            GameLingoRoutes.HISTORY to R.string.nav_history,
            GameLingoRoutes.VOICE to R.string.nav_voice,
            GameLingoRoutes.ABOUT to R.string.nav_about,
        )
        entries.forEach { (route, titleRes) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(route) },
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(titleRes)) },
                )
            }
        }
    }
}
