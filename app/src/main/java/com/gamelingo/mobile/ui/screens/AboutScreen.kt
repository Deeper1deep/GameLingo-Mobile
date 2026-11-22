package com.gamelingo.mobile.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.heightIn
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

/** Developer Telegram account. The path carries no "@". */
internal const val DEVELOPER_TELEGRAM_URL = "https://t.me/llil7y"
private const val DEVELOPER_TELEGRAM_APP_URI = "tg://resolve?domain=llil7y"

/**
 * Opens the developer's Telegram: the Telegram app when installed, otherwise
 * the browser. Never throws; returns false only when nothing could open it.
 */
internal fun openDeveloperTelegram(context: Context): Boolean {
    fun launch(uri: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    return launch(DEVELOPER_TELEGRAM_APP_URI) || launch(DEVELOPER_TELEGRAM_URL)
}

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
        GlCard {
            // Literal "© 2026 @Deeper" order whatever the layout direction.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.about_copyright_prefix),
                        style = MaterialTheme.typography.titleMedium,
                        color = GlColors.TextPrimary,
                    )
                    val openLabel = stringResource(R.string.about_developer_cd)
                    Text(
                        text = stringResource(R.string.about_developer_handle),
                        style = MaterialTheme.typography.titleMedium,
                        color = GlColors.Secondary,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(
                                onClickLabel = openLabel,
                                role = Role.Button,
                            ) { openDeveloperTelegram(context) }
                            .padding(vertical = GlSpacing.sm),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
            GlButton(
                text = stringResource(R.string.about_contact_developer),
                onClick = { openDeveloperTelegram(context) },
                modifier = Modifier.fillMaxWidth(),
            )
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
