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
        val region = SettingsConstraints.clampRegionFractions(
            top = prefs[KEY_REGION_TOP] ?: SettingsConstraints.DEFAULT_REGION_TOP,
            bottom = prefs[KEY_REGION_BOTTOM] ?: SettingsConstraints.DEFAULT_REGION_BOTTOM,
        )
        AppSettings(
            showOriginalText = prefs[KEY_SHOW_ORIGINAL] ?: true,
            captureIntervalMs = SettingsConstraints.clampCaptureIntervalMs(
                prefs[KEY_CAPTURE_INTERVAL_MS] ?: SettingsConstraints.DEFAULT_CAPTURE_INTERVAL_MS,
            ),
            restrictToTextRegion = prefs[KEY_RESTRICT_TO_REGION] ?: true,
            smartTextDetection = prefs[KEY_SMART_TEXT_DETECTION] ?: true,
            regionPreset = parseEnumOrDefault(
                prefs[KEY_REGION_PRESET],
                TextRegionPreset.LOWER_THIRD,
            ),
            regionTopFraction = region.top,
            regionBottomFraction = region.bottom,
            dedupEnabled = prefs[KEY_DEDUP] ?: true,
            ignoreUnstableText = prefs[KEY_IGNORE_UNSTABLE] ?: true,
            batterySaverEnabled = prefs[KEY_BATTERY_SAVER] ?: true,
            aiMode = parseEnumOrDefault(prefs[KEY_AI_MODE], AiMode.AUTO),
            onlineApiKey = prefs[KEY_ONLINE_API_KEY].orEmpty(),
            cloudTranslationOptIn = prefs[KEY_CLOUD_OPT_IN] ?: false,
            requestTimeoutSec = SettingsConstraints.clampTimeoutSec(
                prefs[KEY_TIMEOUT_SEC] ?: SettingsConstraints.DEFAULT_TIMEOUT_SEC,
            ),
            cacheEnabled = prefs[KEY_CACHE_ENABLED] ?: true,
            contextLines = SettingsConstraints.clampContextLines(
                prefs[KEY_CONTEXT_LINES] ?: SettingsConstraints.DEFAULT_CONTEXT_LINES,
            ),
            overlayOpacity = SettingsConstraints.clampOverlayOpacity(
                prefs[KEY_OVERLAY_OPACITY] ?: SettingsConstraints.DEFAULT_OVERLAY_OPACITY,
            ),
            overlayTextSizeSp = SettingsConstraints.clampOverlayTextSizeSp(
                prefs[KEY_OVERLAY_TEXT_SIZE] ?: SettingsConstraints.DEFAULT_OVERLAY_TEXT_SIZE_SP,
            ),
            overlayTextColor = prefs[KEY_OVERLAY_TEXT_COLOR]
                ?: com.gamelingo.mobile.core.model.OverlayColors.DEFAULT_TEXT,
            overlayBackgroundColor = prefs[KEY_OVERLAY_BG_COLOR]
                ?: com.gamelingo.mobile.core.model.OverlayColors.DEFAULT_BACKGROUND,
            overlayPosition = parseEnumOrDefault(
                prefs[KEY_OVERLAY_POSITION],
                OverlayPosition.BOTTOM,
            ),
        )
    }

    override suspend fun setShowOriginalText(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_ORIGINAL] = show }
    }

    override suspend fun setCaptureIntervalMs(intervalMs: Long) {
        dataStore.edit {
            it[KEY_CAPTURE_INTERVAL_MS] = SettingsConstraints.clampCaptureIntervalMs(intervalMs)
        }
    }

    override suspend fun setRestrictToTextRegion(restrict: Boolean) {
        dataStore.edit { it[KEY_RESTRICT_TO_REGION] = restrict }
    }

    override suspend fun setSmartTextDetection(enabled: Boolean) {
        dataStore.edit { it[KEY_SMART_TEXT_DETECTION] = enabled }
    }

    override suspend fun setRegionPreset(preset: TextRegionPreset) {
        dataStore.edit { it[KEY_REGION_PRESET] = preset.name }
    }

    override suspend fun setRegionTopFraction(fraction: Float) {
        dataStore.edit {
            val current = it[KEY_REGION_BOTTOM] ?: SettingsConstraints.DEFAULT_REGION_BOTTOM
            it[KEY_REGION_TOP] =
                SettingsConstraints.clampRegionFractions(fraction, current).top
        }
    }

    override suspend fun setRegionBottomFraction(fraction: Float) {
        dataStore.edit {
            val current = it[KEY_REGION_TOP] ?: SettingsConstraints.DEFAULT_REGION_TOP
            it[KEY_REGION_BOTTOM] =
                SettingsConstraints.clampRegionFractions(current, fraction).bottom
        }
    }

    override suspend fun setDedupEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DEDUP] = enabled }
    }

    override suspend fun setIgnoreUnstableText(ignore: Boolean) {
        dataStore.edit { it[KEY_IGNORE_UNSTABLE] = ignore }
    }

    override suspend fun setBatterySaverEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BATTERY_SAVER] = enabled }
    }

    override suspend fun setAiMode(mode: AiMode) {
        dataStore.edit { it[KEY_AI_MODE] = mode.name }
    }

    override suspend fun setOnlineApiKey(key: String) {
        dataStore.edit { it[KEY_ONLINE_API_KEY] = key.trim() }
    }

    override suspend fun setCloudTranslationOptIn(enabled: Boolean) {
        dataStore.edit { it[KEY_CLOUD_OPT_IN] = enabled }
    }

    override suspend fun setRequestTimeoutSec(seconds: Int) {
        dataStore.edit { it[KEY_TIMEOUT_SEC] = SettingsConstraints.clampTimeoutSec(seconds) }
    }

    override suspend fun setCacheEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CACHE_ENABLED] = enabled }
    }

    override suspend fun setContextLines(lines: Int) {
        dataStore.edit { it[KEY_CONTEXT_LINES] = SettingsConstraints.clampContextLines(lines) }
    }

    override suspend fun setOverlayOpacity(opacity: Float) {
        dataStore.edit { it[KEY_OVERLAY_OPACITY] = SettingsConstraints.clampOverlayOpacity(opacity) }
    }

    override suspend fun setOverlayTextSizeSp(sizeSp: Int) {
        dataStore.edit {
            it[KEY_OVERLAY_TEXT_SIZE] = SettingsConstraints.clampOverlayTextSizeSp(sizeSp)
        }
    }

    override suspend fun setOverlayTextColor(argb: Long) {
        dataStore.edit { it[KEY_OVERLAY_TEXT_COLOR] = argb }
    }

    override suspend fun setOverlayBackgroundColor(argb: Long) {
        dataStore.edit { it[KEY_OVERLAY_BG_COLOR] = argb }
    }

    override suspend fun setOverlayPosition(position: OverlayPosition) {
        dataStore.edit { it[KEY_OVERLAY_POSITION] = position.name }
    }

    private companion object {
        val KEY_SHOW_ORIGINAL = booleanPreferencesKey("show_original_text")
        val KEY_CAPTURE_INTERVAL_MS = longPreferencesKey("capture_interval_ms")
        val KEY_RESTRICT_TO_REGION = booleanPreferencesKey("restrict_to_text_region")
        val KEY_SMART_TEXT_DETECTION = booleanPreferencesKey("smart_text_detection")
        val KEY_REGION_PRESET = stringPreferencesKey("region_preset")
        val KEY_REGION_TOP = floatPreferencesKey("region_top_fraction")
        val KEY_REGION_BOTTOM = floatPreferencesKey("region_bottom_fraction")
        val KEY_DEDUP = booleanPreferencesKey("dedup_enabled")
        val KEY_IGNORE_UNSTABLE = booleanPreferencesKey("ignore_unstable_text")
        val KEY_BATTERY_SAVER = booleanPreferencesKey("battery_saver_enabled")
        val KEY_AI_MODE = stringPreferencesKey("ai_mode")
        val KEY_ONLINE_API_KEY = stringPreferencesKey("online_api_key")
        val KEY_CLOUD_OPT_IN = booleanPreferencesKey("cloud_translation_opt_in")
        val KEY_TIMEOUT_SEC = intPreferencesKey("request_timeout_sec")
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_CONTEXT_LINES = intPreferencesKey("context_lines")
        val KEY_OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val KEY_OVERLAY_TEXT_SIZE = intPreferencesKey("overlay_text_size_sp")
        val KEY_OVERLAY_TEXT_COLOR = longPreferencesKey("overlay_text_color")
        val KEY_OVERLAY_BG_COLOR = longPreferencesKey("overlay_background_color")
        val KEY_OVERLAY_POSITION = stringPreferencesKey("overlay_position")
    }
}
