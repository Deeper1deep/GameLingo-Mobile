package com.gamelingo.mobile.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.BuildConfig
import com.gamelingo.mobile.R

private const val SOURCE_REPO_URL = "https://github.com/Deeper1deep/GameLingo-Mobile"
private const val UPDATES_REPO_URL = "https://github.com/Deeper1deep/GameLingo-Updates"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                    )
                },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.about_package, BuildConfig.APPLICATION_ID))
                },
                supportingContent = { Text(stringResource(R.string.about_package_hint)) },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_updates_title)) },
                supportingContent = { Text(stringResource(R.string.about_updates)) },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_stack_title)) },
                supportingContent = { Text(stringResource(R.string.about_stack_body)) },
            )
        }
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL)))
            },
        ) {
            Text(stringResource(R.string.about_open_repo))
        }
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATES_REPO_URL)))
            },
        ) {
            Text(stringResource(R.string.about_open_updates))
        }
        Text(
            text = stringResource(R.string.home_headline),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
