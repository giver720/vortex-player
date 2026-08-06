package com.vortex.player.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traduce un enlace de Spotify a una lista de canciones **leyendo sólo metadatos**.
 *
 * El audio de Spotify va cifrado y no se toca: lo único que se obtiene aquí es el
 * catálogo (título, artista, duración, portada) que luego sirve para buscar la canción
 * en YouTube Music. Es el mismo enfoque de spotDL.
 *
 * La fuente es la página pública de *embed*, la que usa cualquiera que incrusta un
 * reproductor de Spotify en su web. No hace falta cuenta ni credenciales, a cambio de
 * depender de una estructura que Spotify puede cambiar sin avisar; por eso los errores
 * se devuelven explicados en vez de como un fallo genérico.
 */
object SpotifyResolver {

    private val LINK = Regex(
        """(?:open\.spotify\.com/(?:intl-[a-z-]+/)?|spotify:)(track|album|playlist)[/:]([A-Za-z0-9]+)"""
    )

    fun isSpotifyLink(input: String): Boolean = LINK.containsMatchIn(input)

    suspend fun resolve(input: String): SpotifyResult = withContext(Dispatchers.IO) {
        val match = LINK.find(input)
            ?: return@withContext SpotifyResult.Error("Ese enlace de Spotify no se reconoce")

        val kind = when (match.groupValues[1]) {
            "track" -> SpotifyKind.TRACK
            "album" -> SpotifyKind.ALBUM
            else -> SpotifyKind.PLAYLIST
        }
        val id = match.groupValues[2]

        val html = fetch("https://open.spotify.com/embed/${match.groupValues[1]}/$id")
            ?: return@withContext SpotifyResult.Error(
                "No se pudo abrir el enlace. Comprueba tu conexión o que sea público."
            )

        val json = extractNextData(html)
            ?: return@withContext SpotifyResult.Error(
                "Spotify ha cambiado el formato de su página y ya no se puede leer la lista."
            )

        runCatching { parse(json, kind) }
            .getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }
            ?.let { SpotifyResult.Ok(it) }
            ?: SpotifyResult.Error("No se encontró ninguna canción en ese enlace")
    }

    private fun fetch(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 15_000
            // Sin un User-Agent de navegador, Spotify devuelve una página vacía.
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            setRequestProperty("Accept-Language", "es,en;q=0.8")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Extrae el bloque JSON que Next.js deja incrustado en la página de embed. */
    private fun extractNextData(html: String): JSONObject? {
        val marker = "__NEXT_DATA__"
        val scriptStart = html.indexOf(marker).takeIf { it >= 0 } ?: return null
        val jsonStart = html.indexOf('{', scriptStart).takeIf { it >= 0 } ?: return null
        val jsonEnd = html.indexOf("</script>", jsonStart).takeIf { it >= 0 } ?: return null
        return runCatching {
            JSONObject(html.substring(jsonStart, jsonEnd).trim())
        }.getOrNull()
    }

    private fun parse(root: JSONObject, kind: SpotifyKind): SpotifyCollection? {
        // La ruta ha cambiado alguna vez entre versiones del embed; se prueban ambas.
        val entity = root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.let { page ->
                page.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
                    ?: page.optJSONObject("entity")
            }
            ?: return null

        val collectionName = entity.optString("name")
            .ifBlank { entity.optString("title") }
            .ifBlank { "Spotify" }
        val collectionCover = largestCover(entity.optJSONObject("coverArt"))

        val trackList: JSONArray? = entity.optJSONArray("trackList")

        // Una canción suelta no trae `trackList`: la propia entidad es la canción.
        if (kind == SpotifyKind.TRACK || trackList == null || trackList.length() == 0) {
            val title = entity.optString("title").ifBlank { collectionName }
            val artist = artistsOf(entity).ifBlank { entity.optString("subtitle") }
            return SpotifyCollection(
                kind = SpotifyKind.TRACK,
                name = title,
                coverUrl = collectionCover,
                tracks = listOf(
                    SpotifyTrack(
                        title = title,
                        artist = artist,
                        album = entity.optString("albumName").ifBlank { title },
                        durationMs = entity.optLong("duration"),
                        coverUrl = collectionCover,
                        trackNumber = 1,
                        totalTracks = 1
                    )
                )
            )
        }

        val total = trackList.length()
        val tracks = buildList {
            for (i in 0 until total) {
                val item = trackList.optJSONObject(i) ?: continue
                val title = item.optString("title")
                if (title.isBlank()) continue
                add(
                    SpotifyTrack(
                        title = title,
                        // En el embed el artista viene en `subtitle`, ya combinado si
                        // la canción tiene varios intérpretes.
                        artist = item.optString("subtitle"),
                        // El embed de una lista no expone el álbum real de cada canción,
                        // así que se usa el nombre de la lista: deja la biblioteca
                        // agrupada en vez de llena de "Álbum desconocido".
                        album = collectionName,
                        durationMs = item.optLong("duration"),
                        coverUrl = collectionCover,
                        trackNumber = i + 1,
                        totalTracks = total
                    )
                )
            }
        }

        return SpotifyCollection(kind, collectionName, collectionCover, tracks)
    }

    private fun artistsOf(entity: JSONObject): String {
        val artists = entity.optJSONArray("artists") ?: return ""
        return (0 until artists.length())
            .mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            .joinToString(", ")
    }

    /** La portada más grande disponible; las miniaturas de 64 px no sirven para etiquetar. */
    private fun largestCover(coverArt: JSONObject?): String? {
        val sources = coverArt?.optJSONArray("sources") ?: return null
        var best: String? = null
        var bestWidth = -1
        var last: String? = null
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val url = source.optString("url").takeIf { it.isNotBlank() } ?: continue
            last = url
            val width = source.optInt("width")
            if (width > bestWidth) {
                bestWidth = width
                best = url
            }
        }
        // Las portadas de lista suelen venir sin `width`; ahí Spotify las ordena de
        // menor a mayor, así que la última es la buena.
        return if (bestWidth > 0) best else last
    }
}
