package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.R

/**
 * Placeholder for the future audio-translation feature. The brief asks for the
 * architecture to be ready for it, without shipping it as a finished feature in v1.
 */
@Composable
fun VoiceTranslationScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.voice_status_title)) },
                supportingContent = { Text(stringResource(R.string.voice_coming_soon)) },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.voice_planned_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                listOf(
                    R.string.voice_planned_1,
                    R.string.voice_planned_2,
                    R.string.voice_planned_3,
                ).forEach { lineRes ->
                    Text(
                        text = stringResource(R.string.voice_planned_line, stringResource(lineRes)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
