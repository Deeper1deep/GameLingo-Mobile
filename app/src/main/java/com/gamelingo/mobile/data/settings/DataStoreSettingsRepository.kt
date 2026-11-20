package com.gamelingo.mobile.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gamelingo_settings",
)

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val dataStore = context.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            aiMode = prefs[KEY_AI_MODE]
                ?.let { stored -> runCatching { AiMode.valueOf(stored) }.getOrNull() }
                ?: AiMode.AUTO,
            captureIntervalMs = prefs[KEY_CAPTURE_INTERVAL_MS]
                ?.coerceIn(AppSettings.MIN_CAPTURE_INTERVAL_MS, AppSettings.MAX_CAPTURE_INTERVAL_MS)
                ?: AppSettings.DEFAULT_CAPTURE_INTERVAL_MS,
            restrictToTextRegion = prefs[KEY_RESTRICT_TO_REGION] ?: true,
            overlayOpacity = prefs[KEY_OVERLAY_OPACITY]?.coerceIn(0.2f, 1f)
                ?: AppSettings.DEFAULT_OVERLAY_OPACITY,
            overlayTextSizeSp = prefs[KEY_OVERLAY_TEXT_SIZE]
                ?.coerceIn(AppSettings.MIN_OVERLAY_TEXT_SIZE_SP, AppSettings.MAX_OVERLAY_TEXT_SIZE_SP)
                ?: AppSettings.DEFAULT_OVERLAY_TEXT_SIZE_SP,
            showOriginalText = prefs[KEY_SHOW_ORIGINAL] ?: true,
        )
    }

    override suspend fun setAiMode(mode: AiMode) {
        dataStore.edit { it[KEY_AI_MODE] = mode.name }
    }

    override suspend fun setCaptureIntervalMs(intervalMs: Long) {
        dataStore.edit {
            it[KEY_CAPTURE_INTERVAL_MS] = intervalMs.coerceIn(
                AppSettings.MIN_CAPTURE_INTERVAL_MS,
                AppSettings.MAX_CAPTURE_INTERVAL_MS,
            )
        }
    }

    override suspend fun setRestrictToTextRegion(restrict: Boolean) {
        dataStore.edit { it[KEY_RESTRICT_TO_REGION] = restrict }
    }

    override suspend fun setOverlayOpacity(opacity: Float) {
        dataStore.edit { it[KEY_OVERLAY_OPACITY] = opacity.coerceIn(0.2f, 1f) }
    }

    override suspend fun setOverlayTextSizeSp(sizeSp: Int) {
        dataStore.edit {
            it[KEY_OVERLAY_TEXT_SIZE] = sizeSp.coerceIn(
                AppSettings.MIN_OVERLAY_TEXT_SIZE_SP,
                AppSettings.MAX_OVERLAY_TEXT_SIZE_SP,
            )
        }
    }

    override suspend fun setShowOriginalText(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_ORIGINAL] = show }
    }

    private companion object {
        val KEY_AI_MODE = stringPreferencesKey("ai_mode")
        val KEY_CAPTURE_INTERVAL_MS = longPreferencesKey("capture_interval_ms")
        val KEY_RESTRICT_TO_REGION = booleanPreferencesKey("restrict_to_text_region")
        val KEY_OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val KEY_OVERLAY_TEXT_SIZE = intPreferencesKey("overlay_text_size_sp")
        val KEY_SHOW_ORIGINAL = booleanPreferencesKey("show_original_text")
    }
}
