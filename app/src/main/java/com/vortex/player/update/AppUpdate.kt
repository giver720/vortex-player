package com.vortex.player.update

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vortex.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Context.updateDataStore by preferencesDataStore("vortex_updates")

/** Un APK concreto publicado en la release, ya asociado a su arquitectura. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val abi: String?
)

data class AppRelease(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>
) {
    /**
     * El APK que le sirve a este dispositivo. Se recorre `SUPPORTED_ABIS` en orden de
     * preferencia, así que un móvil arm64 coge el de 64 bits y sólo cae al de 32 si no
     * hubiera otro.
     */
    fun assetForThisDevice(): ReleaseAsset? {
        Build.SUPPORTED_ABIS.forEach { abi ->
            assets.firstOrNull { it.abi == abi }?.let { return it }
        }
        return null
    }
}

object AppUpdate {

    private const val REPO = "giver720/vortex-player"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    private val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
    private val LAST_CHECK = longPreferencesKey("last_check_at")

    /** Espaciado entre comprobaciones automáticas. */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun shouldAutoCheck(context: Context): Boolean {
        val last = runCatching {
            context.updateDataStore.data.first()[LAST_CHECK] ?: 0L
        }.getOrDefault(0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    suspend fun markChecked(context: Context) {
        context.updateDataStore.edit { it[LAST_CHECK] = System.currentTimeMillis() }
    }

    suspend fun skippedVersion(context: Context): String? =
        runCatching { context.updateDataStore.data.first()[SKIPPED_VERSION] }.getOrNull()

    suspend fun skipVersion(context: Context, versionName: String) {
        context.updateDataStore.edit { it[SKIPPED_VERSION] = versionName }
    }

    /** Consulta la última release publicada. Devuelve `null` si no hay red o no se pudo leer. */
    suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                // GitHub exige un User-Agent y devuelve 403 sin él.
                setRequestProperty("User-Agent", "Vortex-Player")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            connection.use { parseRelease(JSONObject(it.inputStream.bufferedReader().readText())) }
        }.getOrNull()
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            if (responseCode !in 200..299) error("HTTP $responseCode")
            block(this)
        } finally {
            disconnect()
        }

    private fun parseRelease(json: JSONObject): AppRelease? {
        // Las prereleases y los borradores no se ofrecen como actualización.
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

        val tag = json.optString("tag_name").ifBlank { return null }
        val assetsJson: JSONArray = json.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (i in 0 until assetsJson.length()) {
                val asset = assetsJson.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                add(
                    ReleaseAsset(
                        name = name,
                        downloadUrl = asset.optString("browser_download_url"),
                        sizeBytes = asset.optLong("size"),
                        abi = KNOWN_ABIS.firstOrNull { name.contains(it, ignoreCase = true) }
                    )
                )
            }
        }

        return AppRelease(
            versionName = tag.removePrefix("v").trim(),
            tagName = tag,
            notes = json.optString("body").trim(),
            htmlUrl = json.optString("html_url"),
            assets = assets
        )
    }

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * Compara versiones de tipo `1.2.3`. Devuelve `true` si [candidate] es posterior a
     * [current]. Cualquier sufijo no numérico se ignora, de modo que `0.2.0-rc1` cuenta
     * como `0.2.0` en vez de romper la comparación.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.toVersionParts()
        val b = current.toVersionParts()
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun String.toVersionParts(): List<Int> =
        split('.', '-', '+')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}
