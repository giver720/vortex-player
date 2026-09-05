package com.vortex.player.spotify

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class SpotifyCatalogPages(
    val tracks: List<SpotifyTrack>,
    val complete: Boolean,
    val total: Int
)

/** Avanza por posiciones del catálogo, incluidas las entradas retiradas o no musicales. */
internal object SpotifyCatalogPager {
    suspend fun load(
        kind: SpotifyKind,
        name: String,
        cover: String?,
        fetch: suspend (offset: Int) -> JSONObject?,
        onPage: (suspend (String?, List<SpotifyTrack>) -> Unit)? = null
    ): SpotifyCatalogPages {
        val all = mutableListOf<SpotifyTrack>()
        var offset = 0
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = fetch(offset) ?: return SpotifyCatalogPages(all, false, total)
            val declaredTotal = page.optInt("total", -1)
            val items = page.optJSONArray("items")
                ?: return SpotifyCatalogPages(all, false, total)
            if (declaredTotal < 0 || page.optInt("offset", offset) != offset) {
                return SpotifyCatalogPages(all, false, total)
            }
            total = declaredTotal
            if (items.length() == 0) return SpotifyCatalogPages(all, offset >= total, total)
            val batch = buildList {
                for (index in 0 until items.length()) {
                    val wrapper = items.optJSONObject(index) ?: continue
                    val track = if (kind == SpotifyKind.ALBUM) wrapper else {
                        wrapper.optJSONObject("item") ?: wrapper.optJSONObject("track") ?: continue
                    }
                    if (track.optString("type", "track") != "track" ||
                        track.optBoolean("is_local") || wrapper.optBoolean("is_local")) continue
                    val title = track.textOrNull("name") ?: continue
                    val artists = track.optJSONArray("artists")
                    val artist = (0 until (artists?.length() ?: 0)).mapNotNull {
                        artists?.optJSONObject(it)?.textOrNull("name")
                    }.joinToString(", ")
                    val album = track.optJSONObject("album")
                    val images = album?.optJSONArray("images")
                    val image = (0 until (images?.length() ?: 0)).mapNotNull {
                        images?.optJSONObject(it)
                    }.sortedByDescending { it.optInt("width") }
                        .firstNotNullOfOrNull { it.textOrNull("url") }
                    add(SpotifyTrack(
                        id = track.textOrNull("id"), title = title, artist = artist,
                        album = album?.textOrNull("name") ?: name,
                        durationMs = track.optLong("duration_ms").coerceAtLeast(0L),
                        coverUrl = image ?: cover,
                        trackNumber = offset + index + 1, totalTracks = total
                    ))
                }
            }
            all += batch
            currentCoroutineContext().ensureActive()
            if (batch.isNotEmpty()) onPage?.invoke(name, batch)
            // Una página de entradas nulas sigue consumiendo posiciones. No se deduplican
            // IDs: una playlist puede repetir intencionalmente la misma canción.
            val next = offset.toLong() + items.length()
            if (next >= total) return SpotifyCatalogPages(all, true, total)
            offset = next.toInt()
        }
    }

    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}
