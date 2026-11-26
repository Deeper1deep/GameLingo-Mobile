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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gamelingo_settings",
)

/**
 * The encrypted API key lives in its own DataStore file, excluded from cloud
 * backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml):
 * its ciphertext is bound to this device's Keystore and is useless (and a
 * needless leak surface) anywhere else. Everything else stays in
 * "gamelingo_settings" and is backed up normally.
 */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gamelingo_secrets",
)

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val apiKeyCipher: ApiKeyCipher,
) : SettingsRepository {

    private val dataStore = context.settingsDataStore
    private val secretStore = context.secretsDataStore
    private val migrationDone = java.util.concurrent.atomic.AtomicBoolean(false)

    // flowOn(IO) is load-bearing: the map resolves the API key, which can
    // touch the Android Keystore. That work must never run on a collector's
    // thread: the overlay service and Compose screens collect on the main
    // thread, and a blocking keystore call there froze the app on session
    // start (v0.2.0 hotfix).
    override val settings: Flow<AppSettings> = combine(dataStore.data, secretStore.data) { prefs, secrets ->
        val region = SettingsConstraints.clampRegionFractions(
            top = prefs[KEY_REGION_TOP] ?: SettingsConstraints.DEFAULT_REGION_TOP,
            bottom = prefs[KEY_REGION_BOTTOM] ?: SettingsConstraints.DEFAULT_REGION_BOTTOM,
        )
        AppSettings(
            showOriginalText = prefs[KEY_SHOW_ORIGINAL] ?: true,
            preserveSourceColors = prefs[KEY_PRESERVE_COLORS] ?: false,
            overlayStyle = com.gamelingo.mobile.core.overlay.OverlayStyle.decodeOrDefault(prefs[KEY_OVERLAY_STYLE]),
            customOverlayPreset = com.gamelingo.mobile.core.overlay.OverlayAppearance.decodeOrNull(prefs[KEY_CUSTOM_PRESET]),
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
            customRegionEnabled = prefs[KEY_CUSTOM_REGION_ON] ?: false,
            customRegions = com.gamelingo.mobile.core.region.OrientedRegions(
                portrait = com.gamelingo.mobile.core.region.NormalizedRegion.decodeOrNull(prefs[KEY_CUSTOM_REGION_PORTRAIT]),
                landscape = com.gamelingo.mobile.core.region.NormalizedRegion.decodeOrNull(prefs[KEY_CUSTOM_REGION_LANDSCAPE]),
            ),
            translationPlacement = com.gamelingo.mobile.core.overlay.TranslationPlacement
                .decodeOrDefault(prefs[KEY_TRANSLATION_PLACEMENT]),
            showRegionBorder = prefs[KEY_REGION_BORDER] ?: true,
            regionSensitivity = parseEnumOrDefault(
                prefs[KEY_REGION_SENSITIVITY],
                com.gamelingo.mobile.core.region.RegionSensitivity.BALANCED,
            ),
            regionPreview = prefs[KEY_REGION_PREVIEW] ?: false,
            dedupEnabled = prefs[KEY_DEDUP] ?: true,
            ignoreUnstableText = prefs[KEY_IGNORE_UNSTABLE] ?: true,
            batterySaverEnabled = prefs[KEY_BATTERY_SAVER] ?: true,
            aiMode = parseEnumOrDefault(prefs[KEY_AI_MODE], AiMode.AUTO),
            onlineApiKey = resolveApiKey(prefs, secrets),
            cloudTranslationOptIn = prefs[KEY_CLOUD_OPT_IN] ?: false,
            autoOnlineFallback = prefs[KEY_AUTO_ONLINE_FALLBACK] ?: false,
            localAiWifiOnly = prefs[KEY_LOCAL_AI_WIFI_ONLY] ?: true,
            gemmaHdOptIn = prefs[KEY_GEMMA_HD_OPT_IN] ?: false,
            arabicTranslationMode = parseEnumOrDefault(
                prefs[KEY_ARABIC_TRANSLATION_MODE],
                ArabicTranslationMode.EGYPTIAN_ARABIC,
            ),
            userGlossaryTerms = com.gamelingo.mobile.data.settings.parseUserGlossary(prefs[KEY_USER_GLOSSARY]),
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
    }.flowOn(Dispatchers.IO)

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

    override suspend fun setCustomRegionEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CUSTOM_REGION_ON] = enabled }
    }

    override suspend fun setCustomRegion(
        landscape: Boolean,
        region: com.gamelingo.mobile.core.region.NormalizedRegion?,
    ) {
        val key = if (landscape) KEY_CUSTOM_REGION_LANDSCAPE else KEY_CUSTOM_REGION_PORTRAIT
        // Only a valid rectangle is ever stored; anything else clears the key.
        val clean = region?.sanitizedOrNull()
        dataStore.edit { if (clean == null) it.remove(key) else it[key] = clean.encode() }
    }

    override suspend fun setTranslationPlacement(
        placement: com.gamelingo.mobile.core.overlay.TranslationPlacement,
    ) {
        dataStore.edit {
            it[KEY_TRANSLATION_PLACEMENT] =
                com.gamelingo.mobile.core.overlay.TranslationPlacement.encode(placement)
        }
    }

    override suspend fun setShowRegionBorder(show: Boolean) {
        dataStore.edit { it[KEY_REGION_BORDER] = show }
    }

    override suspend fun setRegionSensitivity(sensitivity: com.gamelingo.mobile.core.region.RegionSensitivity) {
        dataStore.edit { it[KEY_REGION_SENSITIVITY] = sensitivity.name }
    }

    override suspend fun setRegionPreview(enabled: Boolean) {
        dataStore.edit { it[KEY_REGION_PREVIEW] = enabled }
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
        val trimmed = key.trim()
        // Keystore encryption off the caller's thread: this is invoked from
        // view models on the main thread.
        val encrypted = if (trimmed.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) { apiKeyCipher.encrypt(trimmed) }
        }
        // New location first; only then drop the legacy copies, so a crash
        // in between can never leave the user with no key at all.
        secretStore.edit { secrets ->
            if (encrypted == null) {
                secrets.remove(KEY_ONLINE_API_KEY_ENC)
            } else {
                secrets[KEY_ONLINE_API_KEY_ENC] = encrypted
            }
        }
        dataStore.edit { prefs ->
            prefs.remove(KEY_ONLINE_API_KEY_ENC)
            prefs.remove(KEY_ONLINE_API_KEY)
        }
    }

    /**
     * Reads the key from the secrets store, falling back to a not-yet-migrated
     * legacy value (encrypted or plaintext) so the key keeps working between
     * the update and the one-time migration. A corrupt or undecryptable value
     * - for example ciphertext restored onto another device - reads as no key.
     */
    private fun resolveApiKey(prefs: Preferences, secrets: Preferences): String {
        val encrypted = secrets[KEY_ONLINE_API_KEY_ENC] ?: prefs[KEY_ONLINE_API_KEY_ENC]
        if (encrypted != null) {
            return apiKeyCipher.decrypt(encrypted).orEmpty()
        }
        return prefs[KEY_ONLINE_API_KEY].orEmpty()
    }

    /**
     * One-time, race-safe move of the key into the secrets store.
     *  - never overwrites a key the user saved meanwhile (decided inside the
     *    atomic secrets edit);
     *  - legacy plaintext is removed only after the new copy decrypts back;
     *  - an undecryptable legacy ciphertext (restored from another device) is
     *    discarded instead of being copied forward;
     *  - a failure leaves the legacy value in place and allows a retry.
     */
    override suspend fun migrateApiKeyIfNeeded() {
        if (!migrationDone.compareAndSet(false, true)) return
        try {
            val snapshot = dataStore.data.first()
            val legacyEnc = snapshot[KEY_ONLINE_API_KEY_ENC]
            val plaintext = snapshot[KEY_ONLINE_API_KEY].orEmpty()
            if (legacyEnc.isNullOrBlank() && plaintext.isBlank()) return

            val usableLegacyEnc = legacyEnc?.takeIf { it.isNotBlank() && apiKeyCipher.decrypt(it) != null }
            val candidate: String? = usableLegacyEnc ?: if (plaintext.isNotBlank()) {
                runCatching { apiKeyCipher.encrypt(plaintext) }.getOrNull()
                    ?.takeIf { apiKeyCipher.decrypt(it) == plaintext }
            } else {
                null
            }
            if (candidate == null) {
                if (plaintext.isNotBlank()) {
                    // Could not encrypt: keep the working plaintext, try again later.
                    migrationDone.set(false)
                } else {
                    // Only an undecryptable ciphertext remained: it can never work here.
                    dataStore.edit { it.remove(KEY_ONLINE_API_KEY_ENC) }
                }
                return
            }
            secretStore.edit { secrets ->
                if (secrets[KEY_ONLINE_API_KEY_ENC].isNullOrBlank()) {
                    secrets[KEY_ONLINE_API_KEY_ENC] = candidate
                }
            }
            val stored = secretStore.data.first()[KEY_ONLINE_API_KEY_ENC]
            if (!stored.isNullOrBlank() && apiKeyCipher.decrypt(stored) != null) {
                dataStore.edit { prefs ->
                    prefs.remove(KEY_ONLINE_API_KEY_ENC)
                    prefs.remove(KEY_ONLINE_API_KEY)
                }
            } else {
                migrationDone.set(false)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            migrationDone.set(false)
            throw cancelled
        } catch (_: Exception) {
            migrationDone.set(false)
        }
    }

    override suspend fun setCloudTranslationOptIn(enabled: Boolean) {
        dataStore.edit { it[KEY_CLOUD_OPT_IN] = enabled }
    }

    override suspend fun setAutoOnlineFallback(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_ONLINE_FALLBACK] = enabled }
    }

    override suspend fun setLocalAiWifiOnly(enabled: Boolean) {
        dataStore.edit { it[KEY_LOCAL_AI_WIFI_ONLY] = enabled }
    }

    override suspend fun setGemmaHdOptIn(enabled: Boolean) {
        dataStore.edit { it[KEY_GEMMA_HD_OPT_IN] = enabled }
    }

    override suspend fun setArabicTranslationMode(mode: ArabicTranslationMode) {
        dataStore.edit { it[KEY_ARABIC_TRANSLATION_MODE] = mode.name }
    }

    override suspend fun setUserGlossaryTerms(terms: List<UserGlossaryTerm>) {
        dataStore.edit { it[KEY_USER_GLOSSARY] = userGlossaryJson.encodeToString(terms) }
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

    override suspend fun setOverlayStyle(style: com.gamelingo.mobile.core.overlay.OverlayStyle) {
        dataStore.edit { it[KEY_OVERLAY_STYLE] = com.gamelingo.mobile.core.overlay.OverlayStyle.encode(style) }
    }

    override suspend fun setCustomOverlayPreset(preset: com.gamelingo.mobile.core.overlay.OverlayAppearance?) {
        dataStore.edit {
            if (preset == null) it.remove(KEY_CUSTOM_PRESET)
            else it[KEY_CUSTOM_PRESET] = com.gamelingo.mobile.core.overlay.OverlayAppearance.encode(preset)
        }
    }

    override suspend fun setPreserveSourceColors(enabled: Boolean) {
        dataStore.edit { it[KEY_PRESERVE_COLORS] = enabled }
    }

    override suspend fun setOverlayPosition(position: OverlayPosition) {
        dataStore.edit { it[KEY_OVERLAY_POSITION] = position.name }
    }

    private companion object {
        val KEY_SHOW_ORIGINAL = booleanPreferencesKey("show_original_text")
        val KEY_PRESERVE_COLORS = booleanPreferencesKey("preserve_source_colors")
        val KEY_CUSTOM_REGION_ON = booleanPreferencesKey("custom_region_enabled")
        val KEY_CUSTOM_REGION_PORTRAIT = stringPreferencesKey("custom_region_portrait")
        val KEY_CUSTOM_REGION_LANDSCAPE = stringPreferencesKey("custom_region_landscape")
        val KEY_TRANSLATION_PLACEMENT = stringPreferencesKey("translation_placement_json")
        val KEY_REGION_BORDER = booleanPreferencesKey("custom_region_border")
        val KEY_REGION_SENSITIVITY = stringPreferencesKey("custom_region_sensitivity")
        val KEY_REGION_PREVIEW = booleanPreferencesKey("custom_region_preview")
        val KEY_OVERLAY_STYLE = stringPreferencesKey("overlay_style_json")
        val KEY_CUSTOM_PRESET = stringPreferencesKey("overlay_custom_preset_json")
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
        val KEY_ONLINE_API_KEY_ENC = stringPreferencesKey("online_api_key_enc")
        val KEY_CLOUD_OPT_IN = booleanPreferencesKey("cloud_translation_opt_in")
        val KEY_AUTO_ONLINE_FALLBACK = booleanPreferencesKey("auto_online_fallback")
        val KEY_LOCAL_AI_WIFI_ONLY = booleanPreferencesKey("local_ai_wifi_only")
        val KEY_GEMMA_HD_OPT_IN = booleanPreferencesKey("gemma_hd_opt_in")
        val KEY_ARABIC_TRANSLATION_MODE = stringPreferencesKey("arabic_translation_mode")
        val KEY_USER_GLOSSARY = stringPreferencesKey("user_glossary_terms")

        internal val userGlossaryJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
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
