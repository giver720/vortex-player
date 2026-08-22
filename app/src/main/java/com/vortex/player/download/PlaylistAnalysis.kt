package com.vortex.player.download

/** Un elemento ligero devuelto por `yt-dlp --flat-playlist`. */
data class PlaylistEntry(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String = "",
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0,
    val index: Int = 0
)

/** Resultado de analizar una colección sin descargar sus medios. */
data class PlaylistAnalysis(
    val name: String,
    val uploader: String = "",
    val coverUrl: String? = null,
    val entries: List<PlaylistEntry>,
    val isPlaylist: Boolean
)

data class SelectablePlaylistEntry(
    val entry: PlaylistEntry,
    val selected: Boolean,
    val alreadyDownloaded: Boolean
)

/** Estado de selección para listas de YouTube y otras fuentes compatibles con yt-dlp. */
data class SourcePlaylistSelection(
    val name: String,
    val uploader: String,
    val coverUrl: String?,
    val entries: List<SelectablePlaylistEntry>,
    val sourceUrl: String,
    /** `null` para resultados de búsqueda, que deben guardarse como archivos sueltos. */
    val folderName: String?
) {
    val selectedCount: Int get() = entries.count { it.selected }
    val ownedCount: Int get() = entries.count { it.alreadyDownloaded }

    fun selectedEntries(): List<PlaylistEntry> =
        entries.filter { it.selected }.map { it.entry }

    fun withAll(selected: Boolean): SourcePlaylistSelection =
        copy(entries = entries.map { it.copy(selected = selected) })

    fun withOnlyMissing(): SourcePlaylistSelection =
        copy(entries = entries.map { it.copy(selected = !it.alreadyDownloaded) })

    fun toggle(index: Int): SourcePlaylistSelection {
        if (index !in entries.indices) return this
        val updated = entries.toMutableList()
        updated[index] = updated[index].let { it.copy(selected = !it.selected) }
        return copy(entries = updated)
    }
}

fun PlaylistAnalysis.toSelection(
    sourceUrl: String,
    completedIds: Set<String>,
    folderName: String? = name
): SourcePlaylistSelection = SourcePlaylistSelection(
    name = name,
    uploader = uploader,
    coverUrl = coverUrl,
    entries = entries.map { entry ->
        val owned = entry.id in completedIds
        SelectablePlaylistEntry(entry, selected = !owned, alreadyDownloaded = owned)
    },
    sourceUrl = sourceUrl,
    folderName = folderName
)
