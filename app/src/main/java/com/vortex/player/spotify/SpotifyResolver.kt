package com.vortex.player.spotify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traduce un enlace de Spotify a una lista de canciones **leyendo sólo metadatos**.
 *
 * El audio de Spotify va cifrado y no se toca: lo único que se obtiene aquí es el
 * catálogo (título, artista, álbum, duración, portada) que luego sirve para buscar la
 * canción en YouTube. Es el mismo enfoque de spotDL.
 *
 * Hay dos vías. La página pública de *embed* devuelve la lista al instante y sin
 * credenciales, pero **corta en 100 pistas y no pagina**. Para superar ese tope se
 * aprovecha que esa misma página trae un token de sesión anónima, con el que se puede
 * pedir la lista completa a la API pública de cien en cien. Ese token está limitado y
 * responde 429 con frecuencia, así que la vía rápida es un extra, no la base: si falla,
 * se devuelven las 100 del embed marcadas como incompletas.
 */
object SpotifyResolver {

    private const val TAG = "SpotifyResolver"

    /** Tope duro del embed. Llegar a él significa "seguramente hay más". */
    private const val EMBED_MAX = 100

    /** Tamaño máximo documentado actualmente por Spotify para elementos de playlist. */
    private const val PAGE = 50

    /** Un token del reproductor web suele vivir una hora; se descarta antes. */
    private const val TOKEN_CACHE_MS = 30 * 60 * 1000L

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    private val LINK = Regex(
        """(?:open\.spotify\.com/(?:intl-[a-z-]+/)?|spotify:)(track|album|playlist)[/:]([A-Za-z0-9]+)"""
    )
    private val SHORT_LINK = Regex("""https?://(?:spotify\.link|spoti\.fi)/[A-Za-z0-9_-]+""")

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedTokenAt: Long = 0L

    fun isSpotifyLink(input: String): Boolean =
        LINK.containsMatchIn(input) || SHORT_LINK.containsMatchIn(input)

    /**
     * Resuelve el enlace. Las canciones llegan por bloques a [onPage] conforme se
     * obtienen, para que la cola empiece a moverse con el primero en vez de esperar a
     * que se resuelva una lista de trescientas.
     *
     * El resultado final contiene la lista completa de lo que se pudo obtener.
     */
    suspend fun resolve(
        context: Context,
        input: String,
        onPage: (suspend (folder: String?, page: List<SpotifyTrack>) -> Unit)? = null
    ): SpotifyResult = withContext(Dispatchers.IO) {
        val match = LINK.find(input)
            ?: SHORT_LINK.find(input)?.value?.let(::expandShortLink)?.let(LINK::find)
            ?: return@withContext SpotifyResult.Error("Ese enlace de Spotify no se reconoce")

        val kindName = match.groupValues[1]
        val id = match.groupValues[2]
        val kind = when (kindName) {
            "track" -> SpotifyKind.TRACK
            "album" -> SpotifyKind.ALBUM
            else -> SpotifyKind.PLAYLIST
        }

        val config = SpotifyEngine.current(context)
        val html = fetch("https://open.spotify.com/embed/$kindName/$id")
            ?: return@withContext SpotifyResult.Error(
                "No se pudo abrir el enlace. Comprueba tu conexión o que sea público."
            )

        val json = extractNextData(html)
            ?: return@withContext SpotifyResult.Error(
                "Spotify ha cambiado el formato de su página y ya no se puede leer la lista."
            )

        val entity = entityOf(json, config)
            ?: return@withContext SpotifyResult.Error(
                "Spotify ha cambiado la estructura de la página."
            )

        val name = entity.optString("name")
            .ifBlank { entity.optString("title") }
            .ifBlank { "Spotify" }
        val cover = largestCover(entity.optJSONObject("coverArt"))

        // --- Canción suelta ---------------------------------------------------
        val trackList = entity.optJSONArray("trackList")
        if (kind == SpotifyKind.TRACK || trackList == null || trackList.length() == 0) {
            val title = entity.optString("title").ifBlank { name }
            val track = SpotifyTrack(
                id = entity.optString("id").takeIf { it.isNotBlank() } ?: id,
                title = title,
                artist = artistsOf(entity).ifBlank { entity.optString("subtitle") },
                album = entity.optString("albumName").ifBlank { title },
                durationMs = entity.optLong("duration"),
                coverUrl = cover,
                trackNumber = 1,
                totalTracks = 1
            )
            onPage?.invoke(null, listOf(track))
            return@withContext SpotifyResult.Ok(
                SpotifyCollection(SpotifyKind.TRACK, title, cover, listOf(track), false, 1)
            )
        }

        val embedTracks = parseEmbedTracks(trackList, name, cover)
        val looksTruncated = trackList.length() >= EMBED_MAX

        // --- Vía rápida: sólo hace falta si el embed se quedó corto -----------
        if (looksTruncated) {
            val freshToken = config.tokenPaths.firstNotNullOfOrNull { path ->
                stringAt(json, path)
            }
            if (freshToken != null) {
                cachedToken = freshToken
                cachedTokenAt = System.currentTimeMillis()
            }
            val token = freshToken ?: cachedToken?.takeIf {
                System.currentTimeMillis() - cachedTokenAt < TOKEN_CACHE_MS
            }

            if (token != null) {
                // `name` es también el nombre de la carpeta: se conoce desde el embed,
                // antes de empezar a paginar, así que cada bloque puede encolarse ya.
                val paged = fetchAllPages(kind, id, token, name, cover, onPage)
                // En cuanto la API entregó algo hay que quedarse con ello aunque se
                // cortara a medias: esas canciones ya se emitieron por `onPage`, y caer
                // al respaldo del embed las encolaría por segunda vez.
                if (paged.tracks.isNotEmpty()) {
                    return@withContext SpotifyResult.Ok(
                        SpotifyCollection(
                            kind = kind,
                            name = name,
                            coverUrl = cover,
                            tracks = paged.tracks,
                            partial = !paged.complete,
                            totalTracks = paged.total
                        )
                    )
                }
            }
        }

        // --- Respaldo: lo que dio el embed -----------------------------------
        onPage?.invoke(name, embedTracks)
        SpotifyResult.Ok(
            SpotifyCollection(
                kind = kind,
                name = name,
                coverUrl = cover,
                tracks = embedTracks,
                partial = looksTruncated,
                totalTracks = embedTracks.size
            )
        )
    }

    /** Lo obtenido de la API y si se llegó hasta el final de la lista. */
    private class Paged(
        val tracks: List<SpotifyTrack>,
        val complete: Boolean,
        val total: Int
    )

    /**
     * Recorre la lista de cien en cien con el token del embed.
     *
     * Devuelve lo conseguido aunque se corte a mitad: el token es el de la sesión
     * anónima del reproductor incrustado y Spotify lo limita, así que un 429 en la
     * tercera página es un desenlace normal, no una excepción.
     */
    private suspend fun fetchAllPages(
        kind: SpotifyKind,
        id: String,
        token: String,
        collectionName: String,
        collectionCover: String?,
        onPage: (suspend (folder: String?, page: List<SpotifyTrack>) -> Unit)?
    ): Paged {
        val all = mutableListOf<SpotifyTrack>()
        var offset = 0
        var total = Int.MAX_VALUE
        var declaredTotal = 0

        while (offset < total) {
            val url = when (kind) {
                SpotifyKind.ALBUM ->
                    "https://api.spotify.com/v1/albums/$id/tracks?limit=50&offset=$offset"
                else ->
                    "https://api.spotify.com/v1/playlists/$id/tracks" +
                        "?limit=$PAGE&offset=$offset" +
                        "&fields=total,items(track(name,duration_ms,track_number," +
                        "artists(name),album(name,images)))"
            }

            val page = fetch(url, token) ?: run {
                Log.w(TAG, "La API cortó en offset=$offset; se usa lo obtenido hasta aquí")
                return Paged(all, complete = false, total = declaredTotal)
            }

            val json = runCatching { JSONObject(page) }.getOrNull()
                ?: return Paged(all, complete = false, total = declaredTotal)

            total = json.optInt("total", 0).takeIf { it > 0 } ?: break
            declaredTotal = total
            val items = json.optJSONArray("items") ?: break
            if (items.length() == 0) break

            val batch = mutableListOf<SpotifyTrack>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // En playlists la canción viene anidada; en álbumes el elemento ya lo es.
                val track = item.optJSONObject("track") ?: item
                val title = track.optString("name")
                if (title.isBlank()) continue

                val album = track.optJSONObject("album")
                batch += SpotifyTrack(
                    id = track.optString("id").takeIf { it.isNotBlank() },
                    title = title,
                    artist = artistsOf(track),
                    // Aquí sí llega el álbum de verdad, no el nombre de la lista.
                    album = album?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: collectionName,
                    durationMs = track.optLong("duration_ms"),
                    coverUrl = album?.let { largestImage(it.optJSONArray("images")) }
                        ?: collectionCover,
                    trackNumber = all.size + batch.size + 1,
                    totalTracks = total
                )
            }

            if (batch.isEmpty()) break
            all += batch
            onPage?.invoke(collectionName, batch)
            offset += items.length()
        }

        // Se considera completa si se alcanzó el total que declaró Spotify. Algunas
        // listas tienen pistas retiradas del catálogo, que la API devuelve nulas y aquí
        // se descartan, así que la cuenta puede quedar por debajo sin que falte nada.
        val complete = declaredTotal > 0 && offset >= declaredTotal
        return Paged(all, complete, declaredTotal)
    }

    private fun parseEmbedTracks(
        trackList: JSONArray,
        collectionName: String,
        cover: String?
    ): List<SpotifyTrack> {
        val total = trackList.length()
        return buildList {
            for (i in 0 until total) {
                val item = trackList.optJSONObject(i) ?: continue
                val title = item.optString("title")
                if (title.isBlank()) continue
                add(
                    SpotifyTrack(
                        // El embed da el URI completo: "spotify:track:XXXX".
                        id = item.optString("uri").substringAfterLast(':').takeIf {
                            it.isNotBlank() && it != item.optString("uri")
                        },
                        title = title,
                        // En el embed el artista viene en `subtitle`, ya combinado si
                        // la canción tiene varios intérpretes.
                        artist = item.optString("subtitle"),
                        // El embed no expone el álbum real de cada canción; se usa el
                        // nombre de la colección para no dejar "Álbum desconocido".
                        album = collectionName,
                        durationMs = item.optLong("duration"),
                        coverUrl = cover,
                        trackNumber = i + 1,
                        totalTracks = total
                    )
                )
            }
        }
    }

    /** Limpia sólo estado efímero; las reglas validadas permanecen en DataStore. */
    fun clearRuntimeState() {
        cachedToken = null
        cachedTokenAt = 0L
    }

    /** Sigue únicamente los acortadores oficiales antes de aplicar el parser normal. */
    private fun expandShortLink(shortUrl: String): String? = runCatching {
        val connection = (URL(shortUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", UA)
        }
        try {
            connection.responseCode
            connection.url.toString().takeIf { LINK.containsMatchIn(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private data class HttpResponse(
        val status: Int,
        val body: String?,
        val retryAfterSeconds: Long?
    )

    /**
     * Spotify limita con 429 las sesiones anónimas. Respetar Retry-After y reintentar
     * respuestas transitorias evita convertir una ráfaga breve en una lista incompleta.
     */
    private suspend fun fetch(url: String, bearer: String? = null): String? {
        repeat(3) { attempt ->
            val response = fetchOnce(url, bearer)
            if (response != null && response.status in 200..299) return response.body

            val status = response?.status ?: -1
            val retryable = status == -1 || status == 429 || status in 500..504
            if (!retryable || attempt == 2) {
                Log.w(TAG, "HTTP $status en $url")
                return null
            }
            val waitMs = if (status == 429) {
                ((response?.retryAfterSeconds ?: 1L) * 1000L).coerceIn(1_000L, 8_000L)
            } else {
                700L * (attempt + 1)
            }
            Log.w(TAG, "HTTP $status en Spotify; reintento ${attempt + 2}/3")
            delay(waitMs)
        }
        return null
    }

    private fun fetchOnce(url: String, bearer: String? = null): HttpResponse? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 15_000
            // Sin un User-Agent de navegador, Spotify devuelve una página vacía.
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "es,en;q=0.8")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
            HttpResponse(
                status = status,
                body = body,
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
            )
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

    /** La ruta ha cambiado alguna vez entre versiones del embed; se prueban ambas. */
    private fun entityOf(root: JSONObject, config: SpotifyEngineConfig): JSONObject? =
        config.entityPaths.firstNotNullOfOrNull { path -> objectAt(root, path) }

    private fun objectAt(root: JSONObject, path: String): JSONObject? {
        var current: JSONObject = root
        for (segment in path.split('/').drop(1)) {
            current = current.optJSONObject(segment) ?: return null
        }
        return current
    }

    private fun stringAt(root: JSONObject, path: String): String? {
        val segments = path.split('/').drop(1)
        if (segments.isEmpty()) return null
        var current = root
        for (segment in segments.dropLast(1)) {
            current = current.optJSONObject(segment) ?: return null
        }
        return current.optString(segments.last()).takeIf { it.isNotBlank() }
    }

    private fun artistsOf(entity: JSONObject): String {
        val artists = entity.optJSONArray("artists") ?: return ""
        return (0 until artists.length())
            .mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            .joinToString(", ")
    }

    /** La portada más grande disponible; las miniaturas de 64 px no sirven para etiquetar. */
    private fun largestCover(coverArt: JSONObject?): String? =
        largestImage(coverArt?.optJSONArray("sources"))

    private fun largestImage(sources: JSONArray?): String? {
        if (sources == null) return null
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
        // Algunas portadas vienen sin `width`; ahí Spotify las ordena de menor a mayor.
        return if (bestWidth > 0) best else last
    }
}
