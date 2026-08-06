package com.vortex.player.spotify

/** Una canción tal y como la describe Spotify. El audio nunca sale de aquí. */
data class SpotifyTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val trackNumber: Int,
    val totalTracks: Int
) {
    /**
     * Consulta de búsqueda. Se antepone el artista porque YouTube Music pondera mucho el
     * primer término, y "Artista - Título" acierta bastante más que al revés.
     */
    val searchQuery: String get() = "$artist - $title"
}

enum class SpotifyKind { TRACK, ALBUM, PLAYLIST }

data class SpotifyCollection(
    val kind: SpotifyKind,
    val name: String,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>
) {
    /** Nombre de carpeta para álbumes y listas; una canción suelta no crea carpeta. */
    val folderName: String? get() = if (kind == SpotifyKind.TRACK) null else name
}

/** Resultado de intentar resolver un enlace, con el motivo del fallo si lo hubo. */
sealed interface SpotifyResult {
    data class Ok(val collection: SpotifyCollection) : SpotifyResult
    data class Error(val message: String) : SpotifyResult
}
