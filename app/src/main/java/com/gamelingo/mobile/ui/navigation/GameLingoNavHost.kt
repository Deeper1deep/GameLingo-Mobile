package com.gamelingo.mobile.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.screens.AboutScreen
import com.gamelingo.mobile.ui.screens.AiSettingsScreen
import com.gamelingo.mobile.ui.screens.HistoryScreen
import com.gamelingo.mobile.ui.screens.HomeScreen
import com.gamelingo.mobile.ui.screens.OcrSettingsScreen
import com.gamelingo.mobile.ui.screens.OverlaySettingsScreen
import com.gamelingo.mobile.ui.screens.TranslationSettingsScreen
import com.gamelingo.mobile.ui.screens.VoiceTranslationScreen

object GameLingoRoutes {
    const val HOME = "home"
    const val TRANSLATION_SETTINGS = "settings/translation"
    const val OCR_SETTINGS = "settings/ocr"
    const val AI_SETTINGS = "settings/ai"
    const val OVERLAY_SETTINGS = "settings/overlay"
    const val HISTORY = "history"
    const val ABOUT = "about"
    const val VOICE = "voice"
}

private data class DestinationMeta(val route: String, val titleRes: Int)

private val destinationMeta = listOf(
    DestinationMeta(GameLingoRoutes.HOME, R.string.nav_home),
    DestinationMeta(GameLingoRoutes.TRANSLATION_SETTINGS, R.string.nav_translation_settings),
    DestinationMeta(GameLingoRoutes.OCR_SETTINGS, R.string.nav_ocr_settings),
    DestinationMeta(GameLingoRoutes.AI_SETTINGS, R.string.nav_ai_settings),
    DestinationMeta(GameLingoRoutes.OVERLAY_SETTINGS, R.string.nav_overlay_settings),
    DestinationMeta(GameLingoRoutes.HISTORY, R.string.nav_history),
    DestinationMeta(GameLingoRoutes.ABOUT, R.string.nav_about),
    DestinationMeta(GameLingoRoutes.VOICE, R.string.nav_voice),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLingoAppRoot(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val titleRes = destinationMeta.firstOrNull { it.route == currentRoute }?.titleRes
        ?: R.string.nav_home

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    if (currentRoute != GameLingoRoutes.HOME) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = GameLingoRoutes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(GameLingoRoutes.HOME) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(GameLingoRoutes.TRANSLATION_SETTINGS) { TranslationSettingsScreen() }
            composable(GameLingoRoutes.OCR_SETTINGS) { OcrSettingsScreen() }
            composable(GameLingoRoutes.AI_SETTINGS) { AiSettingsScreen() }
            composable(GameLingoRoutes.OVERLAY_SETTINGS) { OverlaySettingsScreen() }
            composable(GameLingoRoutes.HISTORY) { HistoryScreen() }
            composable(GameLingoRoutes.ABOUT) { AboutScreen() }
            composable(GameLingoRoutes.VOICE) { VoiceTranslationScreen() }
        }
    }
}
