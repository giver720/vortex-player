package com.vortex.player.data

data class SearchResults(
    val query: String = "",
    val folders: List<FolderNode> = emptyList(),
    val videos: List<MediaEntry> = emptyList(),
    val audios: List<MediaEntry> = emptyList()
) {
    val total: Int get() = folders.size + videos.size + audios.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * Búsqueda sobre toda la biblioteca a la vez: nombre del fichero, carpeta que lo contiene
 * y ruta completa. Buscar sólo por nombre de fichero obliga a recordar cómo se llamaba
 * exactamente; buscando también por carpeta basta con recordar dónde estaba.
 */
fun searchLibrary(state: LibraryState, rawQuery: String, limitPerGroup: Int = 60): SearchResults {
    val query = rawQuery.trim()
    if (query.length < 2) return SearchResults(query)

    val folders = mutableListOf<FolderNode>()
    collectMatchingFolders(state.folderTree, query, folders)

    val matches = state.entries.filter { entry ->
        entry.title.contains(query, ignoreCase = true) ||
            entry.displayName.contains(query, ignoreCase = true) ||
            entry.folderName.contains(query, ignoreCase = true) ||
            entry.folderPath.contains(query, ignoreCase = true) ||
            entry.artist?.contains(query, ignoreCase = true) == true ||
            entry.album?.contains(query, ignoreCase = true) == true
    }

    return SearchResults(
        query = query,
        // Las carpetas con más contenido primero: si buscas "vacaciones" quieres antes
        // la carpeta con 200 fotos que la que tiene un vídeo suelto.
        folders = folders.sortedByDescending { it.totalFiles }.take(limitPerGroup),
        videos = matches.filter { it.isVideo }.take(limitPerGroup),
        audios = matches.filter { !it.isVideo }.take(limitPerGroup)
    )
}

private fun collectMatchingFolders(
    node: FolderNode,
    query: String,
    out: MutableList<FolderNode>
) {
    // La raíz sintética no es una carpeta real del dispositivo, así que no se ofrece.
    if (node.depth > 0 && node.name.contains(query, ignoreCase = true)) out += node
    node.children.forEach { collectMatchingFolders(it, query, out) }
}

/** Rangos de coincidencia para poder resaltar el fragmento encontrado en la interfaz. */
fun highlightRanges(text: String, query: String): List<IntRange> {
    if (query.length < 2) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val index = text.indexOf(query, from, ignoreCase = true)
        if (index < 0) break
        ranges += index until (index + query.length)
        from = index + query.length
    }
    return ranges
}
