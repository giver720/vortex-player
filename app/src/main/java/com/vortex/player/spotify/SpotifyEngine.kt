package com.vortex.player.spotify

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Context.spotifyEngineDataStore by preferencesDataStore("vortex_spotify_engine")

/**
 * Reglas de lectura del catálogo de Spotify.
 *
 * Sólo estas rutas JSON pueden actualizarse sin publicar otro APK. Los hosts, el tipo de
 * petición y el código del parser siguen compilados en Vortex: una actualización remota
 * nunca puede ejecutar código ni redirigir el token de Spotify a otro servidor.
 */
data class SpotifyEngineConfig(
    val version: Int,
    val label: String,
    val entityPaths: List<String>,
    val tokenPaths: List<String>
) {
    fun toJson(): String = JSONObject().apply {
        put("schema", SpotifyEngine.SCHEMA)
        put("version", version)
        put("label", label)
        put("entityPaths", entityPaths)
        put("tokenPaths", tokenPaths)
    }.toString()
}

/** Motor actualizable del catálogo; el audio continúa a cargo de yt-dlp. */
object SpotifyEngine {

    internal const val SCHEMA = 1
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/giver720/vortex-player/main/spotify-engine.json"
    private const val MAX_MANIFEST_CHARS = 32_768
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private val CONFIG = stringPreferencesKey("rules_json")
    private val LAST_CHECK_AT = longPreferencesKey("last_check_at")
    private val LAST_RESULT = stringPreferencesKey("last_result")
    private val AUTO_UPDATE = booleanPreferencesKey("auto_update")

    val bundled = SpotifyEngineConfig(
        version = 2,
        label = "2.0",
        entityPaths = listOf(
            "/props/pageProps/state/data/entity",
            "/props/pageProps/entity"
        ),
        tokenPaths = listOf(
            "/props/pageProps/state/settings/session/accessToken"
        )
    )

    fun version(context: Context): Flow<String> = context.spotifyEngineDataStore.data.map { prefs ->
        parse(prefs[CONFIG])?.label ?: bundled.label
    }

    fun lastResult(context: Context): Flow<String?> =
        context.spotifyEngineDataStore.data.map { it[LAST_RESULT] }

    fun autoUpdateEnabled(context: Context): Flow<Boolean> =
        context.spotifyEngineDataStore.data.map { it[AUTO_UPDATE] ?: true }

    suspend fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.spotifyEngineDataStore.edit { it[AUTO_UPDATE] = enabled }
    }

    suspend fun current(context: Context): SpotifyEngineConfig {
        val raw = runCatching { context.spotifyEngineDataStore.data.first()[CONFIG] }.getOrNull()
        return parse(raw) ?: bundled
    }

    suspend fun shouldAutoUpdate(context: Context): Boolean {
        val prefs = runCatching { context.spotifyEngineDataStore.data.first() }.getOrNull()
            ?: return false
        if (prefs[AUTO_UPDATE] == false) return false
        return System.currentTimeMillis() - (prefs[LAST_CHECK_AT] ?: 0L) > UPDATE_INTERVAL_MS
    }

    /**
     * Descarga un manifiesto pequeño y estrictamente validado. No contiene URLs ni código:
     * únicamente rutas alternativas para sobrevivir a cambios de estructura de Next.js.
     */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        val result = runCatching {
            val raw = downloadManifest()
            val remote = parse(raw) ?: error("manifiesto incompatible")
            val current = current(context)
            if (remote.version < current.version) {
                error("la versión remota es anterior a la instalada")
            }
            context.spotifyEngineDataStore.edit { prefs ->
                prefs[CONFIG] = remote.toJson()
                prefs[LAST_CHECK_AT] = System.currentTimeMillis()
                prefs[LAST_RESULT] = if (remote.version > current.version) {
                    "Motor Spotify actualizado a ${remote.label}"
                } else {
                    "Motor Spotify ${remote.label} ya estaba al día"
                }
            }
            SpotifyResolver.clearRuntimeState()
            if (remote.version > current.version) {
                "Motor Spotify actualizado a ${remote.label}"
            } else {
                "Motor Spotify ${remote.label} ya estaba al día"
            }
        }.getOrElse { error ->
            val message = "No se pudo actualizar Spotify: ${error.message ?: "error de red"}"
            context.spotifyEngineDataStore.edit { prefs ->
                prefs[LAST_CHECK_AT] = System.currentTimeMillis()
                prefs[LAST_RESULT] = message
            }
            message
        }
        result
    }

    internal fun parse(raw: String?): SpotifyEngineConfig? {
        if (raw.isNullOrBlank() || raw.length > MAX_MANIFEST_CHARS) return null
        return runCatching {
            val json = JSONObject(raw)
            require(json.optInt("schema") == SCHEMA)
            val version = json.getInt("version")
            require(version >= bundled.version)
            val label = json.getString("label")
            require(label.matches(Regex("[A-Za-z0-9._-]{1,32}")))
            val entity = json.getJSONArray("entityPaths").toStringList()
            val token = json.getJSONArray("tokenPaths").toStringList()
            require(entity.isNotEmpty() && entity.size <= 8)
            require(token.isNotEmpty() && token.size <= 8)
            require((entity + token).all(::validPath))
            SpotifyEngineConfig(version, label, entity, token)
        }.getOrNull()
    }

    private fun validPath(path: String): Boolean =
        path.length <= 180 &&
            path.startsWith("/props/pageProps/") &&
            path.split('/').drop(1).all { it.matches(Regex("[A-Za-z0-9_-]+")) }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun downloadManifest(): String {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Vortex-Spotify-Engine/${bundled.label}")
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val out = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(out.length + count <= MAX_MANIFEST_CHARS) { "manifiesto demasiado grande" }
                    out.append(buffer, 0, count)
                }
            }
            return out.toString()
        } finally {
            connection.disconnect()
        }
    }
}
