package com.vortex.player.spotify

/** Por qué se considera que una canción ya está en el dispositivo. */
enum class OwnedReason(val label: String) {
    /** Hay un registro de descarga completada con ese mismo identificador de pista. */
    HISTORY("YA DESCARGADA"),

    /** Existe en la biblioteca un archivo con el nombre que tendría esta canción. */
    FILE("YA EN LA BIBLIOTECA")
}

data class SelectableTrack(
    val track: SpotifyTrack,
    val selected: Boolean,
    val owned: OwnedReason?
)

/**
 * Estado de la pantalla de selección.
 *
 * Se llena por bloques, igual que la resolución: en una lista de trescientas, la primera
 * página ya es utilizable mientras llegan las demás.
 */
data class PlaylistSelection(
    val name: String,
    val kind: SpotifyKind,
    val coverUrl: String?,
    val tracks: List<SelectableTrack> = emptyList(),
    val resolving: Boolean = true,
    val partial: Boolean = false,
    val sourceUrl: String = ""
) {
    val selectedCount: Int get() = tracks.count { it.selected }
    val ownedCount: Int get() = tracks.count { it.owned != null }
    val folderName: String? get() = if (kind == SpotifyKind.TRACK) null else name

    fun selectedTracks(): List<SpotifyTrack> = tracks.filter { it.selected }.map { it.track }

    fun withAll(selected: Boolean): PlaylistSelection =
        copy(tracks = tracks.map { it.copy(selected = selected) })

    /** Deja marcado sólo lo que falta: el gesto de "resincronizar" en un toque. */
    fun withOnlyMissing(): PlaylistSelection =
        copy(tracks = tracks.map { it.copy(selected = it.owned == null) })

    fun toggle(index: Int): PlaylistSelection {
        if (index !in tracks.indices) return this
        val updated = tracks.toMutableList()
        updated[index] = updated[index].let { it.copy(selected = !it.selected) }
        return copy(tracks = updated)
    }
}

/**
 * Decide qué canciones ya están en el dispositivo.
 *
 * Se combinan dos señales a propósito. El identificador de pista es exacto y aguanta que
 * renombres o muevas el archivo, pero se pierde si borras los datos de la app. El nombre
 * de archivo sobrevive a eso y además reconoce canciones que bajaste antes de que
 * existiera esta función, aunque falla si las renombraste. Juntas cubren casi todo.
 */
fun markOwned(
    tracks: List<SpotifyTrack>,
    folder: String?,
    completedIds: Set<String>,
    libraryFileNames: Set<String>
): List<SelectableTrack> = tracks.map { track ->
    val byHistory = track.id != null && track.id in completedIds
    val expected = SpotifyJobs.outputName(track, folder).substringAfterLast('/').lowercase()
    val byFile = expected in libraryFileNames

    val owned = when {
        byHistory -> OwnedReason.HISTORY
        byFile -> OwnedReason.FILE
        else -> null
    }
    SelectableTrack(track = track, selected = owned == null, owned = owned)
}
