package com.vortex.player.spotify

/** Una canción tal y como la describe Spotify. El audio nunca sale de aquí. */
data class SpotifyTrack(
    /**
     * Identificador de la pista en el catálogo. Es lo que permite saber que una canción
     * ya se descargó aunque el fichero se haya renombrado o movido después.
     */
    val id: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val trackNumber: Int,
    val totalTracks: Int
) {
    /**
     * Consulta de búsqueda. Se antepone el artista porque YouTube pondera mucho el
     * primer término, y "Artista - Título" acierta bastante más que al revés.
     */
    val searchQuery: String get() = "$artist - $title"
}

enum class SpotifyKind { TRACK, ALBUM, PLAYLIST }

data class SpotifyCollection(
    val kind: SpotifyKind,
    val name: String,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>,
    /**
     * La página de embed corta las listas en 100 pistas y no pagina. Cuando se llega a
     * ese tope y tampoco se ha podido completar por la API, esto queda a `true`: la
     * lista está recortada y hay que decírselo al usuario en vez de fingir que cabía.
     */
    val partial: Boolean = false,
    /** Total real según Spotify, si se llegó a saber. 0 = desconocido. */
    val totalTracks: Int = 0
) {
    /** Nombre de carpeta para álbumes y listas; una canción suelta no crea carpeta. */
    val folderName: String? get() = if (kind == SpotifyKind.TRACK) null else name
}

/** Resultado de intentar resolver un enlace, con el motivo del fallo si lo hubo. */
sealed interface SpotifyResult {
    data class Ok(val collection: SpotifyCollection) : SpotifyResult
    data class Error(val message: String) : SpotifyResult
}
