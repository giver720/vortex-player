package com.vortex.player.download

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.engineDataStore by preferencesDataStore("vortex_engine")

/**
 * Estado del motor de descargas entre sesiones.
 *
 * yt-dlp envejece mal: YouTube cambia su cifrado de firmas cada pocas semanas y una
 * versión de hace un mes empieza a fallar en descargas que antes iban. Por eso la
 * actualización no es una opción escondida en ajustes sino algo que ocurre solo.
 */
object EnginePreferences {

    private val LAST_UPDATE_AT = longPreferencesKey("last_engine_update_at")
    private val LAST_UPDATE_RESULT = stringPreferencesKey("last_engine_update_result")
    private val AUTO_UPDATE = booleanPreferencesKey("auto_update_engine")
    private val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
    private val ADAPTIVE_CONCURRENCY = booleanPreferencesKey("adaptive_concurrency")
    private val YOUTUBE_LIMIT = intPreferencesKey("youtube_concurrency_limit")
    private val OTHER_LIMIT = intPreferencesKey("other_concurrency_limit")
    private val WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
    private val CHARGING_ONLY = booleanPreferencesKey("downloads_charging_only")
    private val DOWNLOAD_SCHEDULE = stringPreferencesKey("download_schedule")
    private val BANDWIDTH_LIMIT = intPreferencesKey("download_bandwidth_limit_kbps")
    private val MAX_AUTOMATIC_RETRIES = intPreferencesKey("download_max_automatic_retries")

    /** Una vez al día es suficiente: yt-dlp publica versiones cada pocos días. */
    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    fun autoUpdateEnabled(context: Context): Flow<Boolean> =
        context.engineDataStore.data.map { it[AUTO_UPDATE] ?: true }

    fun lastResult(context: Context): Flow<String?> =
        context.engineDataStore.data.map { it[LAST_UPDATE_RESULT] }

    /** Cantidad de procesos yt-dlp que pueden trabajar al mismo tiempo. */
    fun concurrentDownloads(context: Context): Flow<Int> =
        context.engineDataStore.data.map { prefs ->
            DownloadConcurrency.clamp(prefs[CONCURRENT_DOWNLOADS] ?: DownloadConcurrency.DEFAULT)
        }

    fun downloadPolicy(context: Context): Flow<DownloadPolicy> =
        context.engineDataStore.data.map { prefs ->
            DownloadPolicy(
                adaptiveConcurrency = prefs[ADAPTIVE_CONCURRENCY] ?: true,
                youtubeLimit = DownloadConcurrency.clamp(prefs[YOUTUBE_LIMIT] ?: 2),
                otherLimit = DownloadConcurrency.clamp(prefs[OTHER_LIMIT] ?: 4),
                wifiOnly = prefs[WIFI_ONLY] ?: false,
                chargingOnly = prefs[CHARGING_ONLY] ?: false,
                schedule = prefs[DOWNLOAD_SCHEDULE]
                    ?.let { runCatching { DownloadSchedule.valueOf(it) }.getOrNull() }
                    ?: DownloadSchedule.ANYTIME,
                bandwidthLimitKbps = (prefs[BANDWIDTH_LIMIT] ?: 0).coerceAtLeast(0),
                maxAutomaticRetries = (prefs[MAX_AUTOMATIC_RETRIES] ?: 3).coerceIn(0, 5)
            )
        }

    suspend fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.engineDataStore.edit { it[AUTO_UPDATE] = enabled }
    }

    suspend fun setConcurrentDownloads(context: Context, value: Int) {
        context.engineDataStore.edit {
            it[CONCURRENT_DOWNLOADS] = DownloadConcurrency.clamp(value)
        }
    }

    suspend fun setAdaptiveConcurrency(context: Context, enabled: Boolean) {
        context.engineDataStore.edit { it[ADAPTIVE_CONCURRENCY] = enabled }
    }

    suspend fun setSourceLimits(context: Context, youtube: Int, other: Int) {
        context.engineDataStore.edit {
            it[YOUTUBE_LIMIT] = DownloadConcurrency.clamp(youtube)
            it[OTHER_LIMIT] = DownloadConcurrency.clamp(other)
        }
    }

    suspend fun setWifiOnly(context: Context, enabled: Boolean) {
        context.engineDataStore.edit { it[WIFI_ONLY] = enabled }
    }

    suspend fun setChargingOnly(context: Context, enabled: Boolean) {
        context.engineDataStore.edit { it[CHARGING_ONLY] = enabled }
    }

    suspend fun setDownloadSchedule(context: Context, schedule: DownloadSchedule) {
        context.engineDataStore.edit { it[DOWNLOAD_SCHEDULE] = schedule.name }
    }

    suspend fun setBandwidthLimit(context: Context, kbps: Int) {
        context.engineDataStore.edit { it[BANDWIDTH_LIMIT] = kbps.coerceAtLeast(0) }
    }

    suspend fun setMaxAutomaticRetries(context: Context, retries: Int) {
        context.engineDataStore.edit { it[MAX_AUTOMATIC_RETRIES] = retries.coerceIn(0, 5) }
    }

    suspend fun shouldAutoUpdate(context: Context): Boolean {
        val prefs = runCatching { context.engineDataStore.data.first() }.getOrNull()
            ?: return false
        if (prefs[AUTO_UPDATE] == false) return false
        val last = prefs[LAST_UPDATE_AT] ?: 0L
        return System.currentTimeMillis() - last > INTERVAL_MS
    }

    private val SPONSOR_MODE = stringPreferencesKey("sponsor_mode")
    private val SPONSOR_CATEGORIES = stringPreferencesKey("sponsor_categories")

    fun sponsorSettings(context: Context): Flow<SponsorSettings> =
        context.engineDataStore.data.map { prefs ->
            val mode = prefs[SPONSOR_MODE]
                ?.let { runCatching { SponsorMode.valueOf(it) }.getOrNull() }
                ?: SponsorMode.OFF
            val categories = prefs[SPONSOR_CATEGORIES]
                ?.let { SponsorCategory.parse(it) }
                ?: SponsorCategory.DEFAULT_VIDEO
            SponsorSettings(mode, categories)
        }

    suspend fun setSponsor(context: Context, settings: SponsorSettings) {
        context.engineDataStore.edit {
            it[SPONSOR_MODE] = settings.mode.name
            it[SPONSOR_CATEGORIES] = settings.categoriesCsv
        }
    }

    suspend fun record(context: Context, result: String) {
        context.engineDataStore.edit {
            it[LAST_UPDATE_AT] = System.currentTimeMillis()
            it[LAST_UPDATE_RESULT] = result
        }
    }
}
