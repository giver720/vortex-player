package com.vortex.player.spotify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class SpotifyLibraryPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val imageUrl: String?,
    val itemCount: Int,
    val spotifyUrl: String?,
    val snapshotId: String?
)

data class SpotifyLibraryTrack(
    val position: Int,
    val id: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val imageUrl: String?,
    val spotifyUrl: String?,
    val isrc: String?
)

data class SpotifyLibraryPage<T>(
    val items: List<T>,
    val offset: Int,
    val total: Int,
    val nextOffset: Int?
)

/** Cliente oficial mínimo. No expone ninguna operación de descarga ni audio. */
object SpotifyWebApi {
    private const val API = "https://api.spotify.com/v1"

    suspend fun currentUserPlaylists(
        context: Context,
        offset: Int = 0,
        limit: Int = 50
    ): Result<SpotifyLibraryPage<SpotifyLibraryPlaylist>> = withContext(Dispatchers.IO) {
        spotifyResult {
            val token = SpotifyAuth.accessToken(context)
                ?: throw IllegalStateException(tokenUnavailableMessage())
            val safeLimit = limit.coerceIn(1, 50)
            val safeOffset = offset.coerceAtLeast(0)
            val json = getJson(
                "$API/me/playlists?limit=$safeLimit&offset=$safeOffset",
                token
            )
            val array = json.optJSONArray("items")
            val items = buildList {
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val images = item.optJSONArray("images")
                        add(
                            SpotifyLibraryPlaylist(
                                id = item.optString("id"),
                                name = item.optString("name").ifBlank { "Playlist" },
                                owner = item.optJSONObject("owner")
                                    ?.optString("display_name").orEmpty(),
                                imageUrl = images?.optJSONObject(0)?.optString("url")
                                    ?.takeIf(String::isNotBlank),
                                itemCount = item.optJSONObject("tracks")
                                    ?.optInt("total")
                                    ?: item.optJSONObject("items")?.optInt("total")
                                    ?: 0,
                                spotifyUrl = item.optJSONObject("external_urls")
                                    ?.optString("spotify")?.takeIf(String::isNotBlank),
                                snapshotId = item.optString("snapshot_id").takeIf(String::isNotBlank)
                            )
                        )
                    }
                }
            }
            val total = json.optInt("total", items.size)
            SpotifyLibraryPage(
                items = items,
                offset = safeOffset,
                total = total,
                nextOffset = SpotifyPageCursor.nextOffset(json, safeOffset)
            )
        }
    }

    suspend fun playlistItems(
        context: Context,
        playlistId: String,
        offset: Int = 0,
        limit: Int = 50
    ): Result<SpotifyLibraryPage<SpotifyLibraryTrack>> = withContext(Dispatchers.IO) {
        spotifyResult {
            val token = SpotifyAuth.accessToken(context)
                ?: throw IllegalStateException(tokenUnavailableMessage())
            val safeLimit = limit.coerceIn(1, 50)
            val safeOffset = offset.coerceAtLeast(0)
            val json = getJson(
                "$API/playlists/$playlistId/items?limit=$safeLimit&offset=$safeOffset",
                token
            )
            val array = json.optJSONArray("items")
            val items = buildList {
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val wrapper = array.optJSONObject(index) ?: continue
                        // En 2026 Spotify migró gradualmente de `track` a `item`.
                        val item = wrapper.optJSONObject("item")
                            ?: wrapper.optJSONObject("track")
                            ?: continue
                        if (item.optString("type", "track") != "track" ||
                            item.optBoolean("is_local") || wrapper.optBoolean("is_local")) continue
                        val artists = item.optJSONArray("artists")
                        val artist = buildList {
                            if (artists != null) {
                                for (artistIndex in 0 until artists.length()) {
                                    artists.optJSONObject(artistIndex)?.optString("name")
                                        ?.takeIf(String::isNotBlank)?.let(::add)
                                }
                            }
                        }.joinToString(", ")
                        val album = item.optJSONObject("album")
                        val images = album?.optJSONArray("images")
                        add(
                            SpotifyLibraryTrack(
                                position = safeOffset + index,
                                id = item.optString("id").takeIf(String::isNotBlank),
                                title = item.optString("name").ifBlank { "Canción" },
                                artist = artist,
                                album = album?.optString("name").orEmpty(),
                                durationMs = item.optLong("duration_ms"),
                                imageUrl = images?.optJSONObject(0)?.optString("url")
                                    ?.takeIf(String::isNotBlank),
                                spotifyUrl = item.optJSONObject("external_urls")
                                    ?.optString("spotify")?.takeIf(String::isNotBlank),
                                isrc = item.optJSONObject("external_ids")?.optString("isrc")
                                    ?.takeIf(String::isNotBlank)
                            )
                        )
                    }
                }
            }
            val total = json.optInt("total", safeOffset + items.size)
            SpotifyLibraryPage(
                items = items,
                offset = safeOffset,
                total = total,
                nextOffset = SpotifyPageCursor.nextOffset(json, safeOffset)
            )
        }
    }

    private suspend fun getJson(url: String, accessToken: String): JSONObject {
        repeat(SpotifyRetryPolicy.MAX_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            var retryDelay: Long? = null
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                retryDelay = SpotifyRetryPolicy.delayMs(
                    status, connection.getHeaderField("Retry-After"), attempt
                )
                if (retryDelay == null) {
                    if (status !in 200..299) {
                        throw IllegalStateException(SpotifyRetryPolicy.failureMessage(status))
                    }
                    val text = connection.inputStream.bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                    return JSONObject(text)
                }
            } catch (network: java.io.IOException) {
                retryDelay = SpotifyRetryPolicy.delayMs(-1, null, attempt) ?: throw network
            } finally {
                connection.disconnect()
            }
            // La conexión se libera antes de esperar, también al cancelar el trabajo.
            retryDelay?.let { delay(it) }
        }
        throw IllegalStateException("Spotify limitó temporalmente las solicitudes")
    }

    private fun tokenUnavailableMessage(): String =
        if (SpotifyAuth.state.value is SpotifyAccountState.Connected) {
            "No se pudo renovar Spotify. Revisa la conexión e inténtalo de nuevo"
        } else {
            "Conecta tu cuenta de Spotify primero"
        }
}
