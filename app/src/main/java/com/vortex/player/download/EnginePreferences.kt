package com.vortex.player.download

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    /** Una vez al día es suficiente: yt-dlp publica versiones cada pocos días. */
    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    fun autoUpdateEnabled(context: Context): Flow<Boolean> =
        context.engineDataStore.data.map { it[AUTO_UPDATE] ?: true }

    fun lastResult(context: Context): Flow<String?> =
        context.engineDataStore.data.map { it[LAST_UPDATE_RESULT] }

    suspend fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.engineDataStore.edit { it[AUTO_UPDATE] = enabled }
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
