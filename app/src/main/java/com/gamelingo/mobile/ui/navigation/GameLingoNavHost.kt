package com.gamelingo.mobile.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.GlBottomBar
import com.gamelingo.mobile.ui.components.GlTab
import com.gamelingo.mobile.ui.components.glScreenBackground
import com.gamelingo.mobile.ui.screens.AboutScreen
import com.gamelingo.mobile.ui.screens.AiSettingsScreen
import com.gamelingo.mobile.ui.screens.DiagnosticsScreen
import com.gamelingo.mobile.ui.screens.HistoryScreen
import com.gamelingo.mobile.ui.screens.HomeScreen
import com.gamelingo.mobile.ui.screens.OcrSettingsScreen
import com.gamelingo.mobile.ui.screens.OverlaySettingsScreen
import com.gamelingo.mobile.ui.screens.SessionScreen
import com.gamelingo.mobile.ui.screens.SettingsHubScreen
import com.gamelingo.mobile.ui.screens.SplashScreen
import com.gamelingo.mobile.ui.screens.TranslationSettingsScreen
import com.gamelingo.mobile.ui.screens.UpdateCenterScreen
import com.gamelingo.mobile.ui.screens.VoiceTranslationScreen
import com.gamelingo.mobile.ui.system.LocalSnackbarHostState
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.update.UpdateDialogHost
import com.gamelingo.mobile.ui.update.UpdateViewModel

object GameLingoRoutes {
    const val HOME = "home"
    const val SESSION = "session"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val TRANSLATION_SETTINGS = "settings/translation"
    const val OCR_SETTINGS = "settings/ocr"
    const val AI_SETTINGS = "settings/ai"
    const val OVERLAY_SETTINGS = "settings/overlay"
    const val UPDATES = "updates"
    const val ABOUT = "about"
    const val VOICE = "voice"
    const val DIAGNOSTICS = "diagnostics"
}

private data class DestinationMeta(val route: String, val titleRes: Int)

private val destinationMeta = listOf(
    DestinationMeta(GameLingoRoutes.HOME, R.string.nav_home),
    DestinationMeta(GameLingoRoutes.SESSION, R.string.nav_session),
    DestinationMeta(GameLingoRoutes.HISTORY, R.string.nav_history),
    DestinationMeta(GameLingoRoutes.SETTINGS, R.string.nav_settings),
    DestinationMeta(GameLingoRoutes.TRANSLATION_SETTINGS, R.string.nav_translation_settings),
    DestinationMeta(GameLingoRoutes.OCR_SETTINGS, R.string.nav_ocr_settings),
    DestinationMeta(GameLingoRoutes.AI_SETTINGS, R.string.nav_ai_settings),
    DestinationMeta(GameLingoRoutes.OVERLAY_SETTINGS, R.string.nav_overlay_settings),
    DestinationMeta(GameLingoRoutes.UPDATES, R.string.nav_update_center),
    DestinationMeta(GameLingoRoutes.ABOUT, R.string.nav_about),
    DestinationMeta(GameLingoRoutes.VOICE, R.string.nav_voice),
    DestinationMeta(GameLingoRoutes.DIAGNOSTICS, R.string.nav_diagnostics),
)

/** Routes shown as bottom-navigation tabs; every other route is a pushed detail screen. */
private val tabRoutes = listOf(
    GameLingoRoutes.HOME,
    GameLingoRoutes.HISTORY,
    GameLingoRoutes.SESSION,
    GameLingoRoutes.SETTINGS,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLingoAppRoot(
    navController: NavHostController = rememberNavController(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    // In-app updates: one check per app start; the dialog floats over any screen.
    LaunchedEffect(Unit) { updateViewModel.checkOnAppOpen() }
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    var showSplash by rememberSaveable { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The Update Center shows the same state inline, so the floating dialog steps aside there.
    if (currentRoute != GameLingoRoutes.UPDATES && !showSplash) {
        UpdateDialogHost(state = updateState, viewModel = updateViewModel)
    }

    fun navigateTo(route: String) {
        if (route in tabRoutes) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Crossfade(
            targetState = showSplash,
            animationSpec = tween(300),
            label = "splash",
        ) { splash ->
            if (splash) {
                SplashScreen(onFinished = { showSplash = false })
            } else {
                val isTab = currentRoute == null || currentRoute in tabRoutes
                val titleRes = destinationMeta.firstOrNull { it.route == currentRoute }?.titleRes
                    ?: R.string.nav_home
                val tabs = listOf(
                    GlTab(GameLingoRoutes.HOME, stringResource(R.string.tab_home), Icons.Filled.Home),
                    GlTab(
                        GameLingoRoutes.HISTORY,
                        stringResource(R.string.tab_history),
                        Icons.AutoMirrored.Filled.List,
                    ),
                    GlTab(
                        GameLingoRoutes.SESSION,
                        stringResource(R.string.tab_session),
                        Icons.Filled.PlayArrow,
                    ),
                    GlTab(
                        GameLingoRoutes.SETTINGS,
                        stringResource(R.string.tab_settings),
                        Icons.Filled.Settings,
                    ),
                )
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .glScreenBackground(),
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (!isTab) {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = stringResource(titleRes),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { navController.popBackStack() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.back),
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    scrolledContainerColor = GlColors.Background,
                                    titleContentColor = GlColors.TextPrimary,
                                    navigationIconContentColor = GlColors.TextPrimary,
                                ),
                            )
                        }
                    },
                    bottomBar = {
                        if (isTab) {
                            GlBottomBar(
                                tabs = tabs,
                                currentRoute = currentRoute ?: GameLingoRoutes.HOME,
                                onSelect = ::navigateTo,
                            )
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = GameLingoRoutes.HOME,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        enterTransition = { fadeIn(tween(220)) },
                        exitTransition = { fadeOut(tween(140)) },
                        popEnterTransition = { fadeIn(tween(220)) },
                        popExitTransition = { fadeOut(tween(140)) },
                    ) {
                        composable(GameLingoRoutes.HOME) {
                            HomeScreen(onNavigate = ::navigateTo)
                        }
                        composable(GameLingoRoutes.SESSION) { SessionScreen() }
                        composable(GameLingoRoutes.HISTORY) { HistoryScreen() }
                        composable(GameLingoRoutes.SETTINGS) {
                            SettingsHubScreen(onNavigate = ::navigateTo)
                        }
                        composable(GameLingoRoutes.TRANSLATION_SETTINGS) { TranslationSettingsScreen() }
                        composable(GameLingoRoutes.OCR_SETTINGS) { OcrSettingsScreen() }
                        composable(GameLingoRoutes.AI_SETTINGS) { AiSettingsScreen() }
                        composable(GameLingoRoutes.OVERLAY_SETTINGS) { OverlaySettingsScreen() }
                        composable(GameLingoRoutes.UPDATES) { UpdateCenterScreen() }
                        composable(GameLingoRoutes.ABOUT) { AboutScreen() }
                        composable(GameLingoRoutes.VOICE) { VoiceTranslationScreen() }
                        composable(GameLingoRoutes.DIAGNOSTICS) { DiagnosticsScreen() }
                    }
                }
            }
        }
    }
}
