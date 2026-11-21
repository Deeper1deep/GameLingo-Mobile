package com.gamelingo.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.BuildConfig
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.LogoMark
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

private const val SOURCE_REPO_URL = "https://github.com/Deeper1deep/GameLingo-Mobile"
private const val UPDATES_REPO_URL = "https://github.com/Deeper1deep/GameLingo-Updates"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    SettingsColumn {
        GlCard(elevated = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(size = 64.dp)
                Column(modifier = Modifier.padding(start = GlSpacing.lg)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = GlColors.TextPrimary,
                    )
                    Text(
                        text = stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlColors.TextSecondary,
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_headline),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextPrimary,
                modifier = Modifier.padding(top = GlSpacing.md),
            )
        }
        GlCard {
            Text(
                text = stringResource(R.string.about_package, BuildConfig.APPLICATION_ID),
                style = MaterialTheme.typography.titleSmall,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.about_package_hint))
        }
        GlCard {
            Text(
                text = stringResource(R.string.about_updates_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.about_updates))
        }
        GlCard {
            Text(
                text = stringResource(R.string.about_stack_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.about_stack_body))
        }
        Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
            GlButton(
                text = stringResource(R.string.about_open_repo),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL)))
                    }
                },
                style = GlButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            GlButton(
                text = stringResource(R.string.about_open_updates),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATES_REPO_URL)))
                    }
                },
                style = GlButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
