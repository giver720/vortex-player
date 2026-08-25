package com.vortex.player.ui.library

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.data.LibraryPreferences
import com.vortex.player.data.LibraryFilter
import com.vortex.player.data.LibraryIntelligence
import com.vortex.player.data.LibraryIntelligenceEngine
import com.vortex.player.data.LibraryPrefs
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaDeleter
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.MediaRepository
import com.vortex.player.data.ContainerFilter
import com.vortex.player.data.DurationFilter
import com.vortex.player.data.ResolutionFilter
import com.vortex.player.data.SizeFilter
import com.vortex.player.data.PlaylistWithItems
import com.vortex.player.data.M3uCodec
import com.vortex.player.data.PlaylistOrganizer
import com.vortex.player.data.PlaylistResolvedItem
import com.vortex.player.data.PlaylistSortMode
import com.vortex.player.data.SmartPlaylistRule
import com.vortex.player.data.SearchResults
import com.vortex.player.data.SortField
import com.vortex.player.data.ViewMode
import com.vortex.player.data.searchLibrary
import com.vortex.player.data.sortedBy
import com.vortex.player.playback.PlaybackService
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.data.db.PlaylistItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab(val label: String) {
    ALL("TODO"),
    VIDEO("VÍDEO"),
    AUDIO("AUDIO"),
    FOLDERS("CARPETAS"),
    PLAYLISTS("LISTAS"),
    SMART("SMART")
}

@SuppressLint("UnsafeOptInUsageError")
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MediaRepository.get(app)

    val library: StateFlow<LibraryState> = repository.library
    val playlists: StateFlow<List<PlaylistWithItems>> = repository.playlists

    val prefs: StateFlow<LibraryPrefs> = LibraryPreferences.observe(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryPrefs())

    private val _tab = MutableStateFlow(LibraryTab.ALL)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    // ------------------------------------------------------------ búsqueda

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    // -------------------------------------------------------------- árbol

    /** Ramas abiertas del árbol de carpetas, por ruta. */
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    /** Carpeta abierta en la rejilla; `null` significa que se está viendo el árbol. */
    private val _openFolder = MutableStateFlow<String?>(null)
    val openFolder: StateFlow<String?> = _openFolder.asStateFlow()

    // ---------------------------------------------------------- selección

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** Lista abierta en la pestaña LISTAS; `null` muestra el índice de listas. */
    private val _openPlaylist = MutableStateFlow<Long?>(null)
    val openPlaylist: StateFlow<Long?> = _openPlaylist.asStateFlow()

    private val _playlistUndoAvailable = MutableStateFlow(false)
    val playlistUndoAvailable: StateFlow<Boolean> = _playlistUndoAvailable.asStateFlow()
    private var removedPlaylistItems: Pair<Long, List<PlaylistItemEntity>>? = null

    /** Diálogo de confirmación del sistema pendiente de lanzar tras un borrado. */
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() = repository.refresh()

    /** Hay un reescaneo en curso; alimenta el indicador de tirar para refrescar. */
    val refreshing: StateFlow<Boolean> = repository.refreshing
    val lastScan = repository.lastScan

    private val _filter = MutableStateFlow(LibraryFilter())
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    private var indexedEntries: List<MediaEntry>? = null
    private var indexedFilter = LibraryFilter()
    private var intelligenceCache = LibraryIntelligence()

    fun toggleAutoRefresh() {
        viewModelScope.launch {
            LibraryPreferences.setAutoRefresh(getApplication(), !prefs.value.autoRefresh)
        }
    }

    fun consumeMessage() { _message.value = null }
    fun consumeDeleteRequest() { _deleteRequest.value = null }

    fun selectTab(value: LibraryTab) {
        _tab.value = value
        clearSelection()
        if (value != LibraryTab.FOLDERS) _openFolder.value = null
        if (value != LibraryTab.PLAYLISTS) _openPlaylist.value = null
    }

    // ------------------------------------------------------------ búsqueda

    fun openSearch() { _searchOpen.value = true }

    fun closeSearch() {
        _searchOpen.value = false
        _query.value = ""
        _results.value = SearchResults()
    }

    fun setQuery(value: String) {
        _query.value = value
        _results.value = searchLibrary(library.value, value)
    }

    // -------------------------------------------------------------- árbol

    fun toggleBranch(path: String) {
        _expanded.value = if (path in _expanded.value) {
            // Cerrar una rama cierra también su descendencia: si no, al reabrirla
            // reaparecería desplegada de una forma que el usuario no pidió.
            _expanded.value.filterNot { it == path || it.startsWith("$path/") }.toSet()
        } else {
            _expanded.value + path
        }
    }

    fun openFolder(path: String?) {
        _openFolder.value = path
        clearSelection()
    }

    /** Abre el árbol hasta dejar visible la carpeta indicada (usado desde la búsqueda). */
    fun revealFolder(path: String) {
        val ancestors = mutableSetOf<String>()
        var current = path
        while (current.contains('/')) {
            ancestors += current
            current = current.substringBeforeLast('/')
        }
        _expanded.value = _expanded.value + ancestors
        _tab.value = LibraryTab.FOLDERS
        _openFolder.value = path
        closeSearch()
    }

    // ------------------------------------------------------ orden y vista

    fun setSort(field: SortField) {
        val current = prefs.value
        // Volver a tocar el criterio activo invierte el sentido, que es lo que espera
        // cualquiera que haya usado un explorador de archivos.
        val descending = if (current.sortField == field) !current.descending else true
        viewModelScope.launch {
            LibraryPreferences.setSort(getApplication(), field, descending)
        }
    }

    fun toggleViewMode() {
        val next = if (prefs.value.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        viewModelScope.launch { LibraryPreferences.setViewMode(getApplication(), next) }
    }

    fun cycleResolutionFilter() {
        val values = ResolutionFilter.entries
        _filter.value = _filter.value.copy(
            resolution = values[(_filter.value.resolution.ordinal + 1) % values.size]
        )
    }

    fun cycleDurationFilter() {
        val values = DurationFilter.entries
        _filter.value = _filter.value.copy(
            duration = values[(_filter.value.duration.ordinal + 1) % values.size]
        )
    }

    fun cycleSizeFilter() {
        val values = SizeFilter.entries
        _filter.value = _filter.value.copy(
            size = values[(_filter.value.size.ordinal + 1) % values.size]
        )
    }

    fun cycleContainerFilter() {
        val values = ContainerFilter.entries
        _filter.value = _filter.value.copy(
            container = values[(_filter.value.container.ordinal + 1) % values.size]
        )
    }

    fun clearFilters() { _filter.value = LibraryFilter() }

    // ---------------------------------------------------------- selección

    fun toggleSelection(entry: MediaEntry) {
        val uri = entry.uri.toString()
        _selection.value = if (uri in _selection.value) {
            _selection.value - uri
        } else {
            _selection.value + uri
        }
    }

    fun selectAll(entries: List<MediaEntry>) {
        _selection.value = entries.map { it.uri.toString() }.toSet()
    }

    fun selectCopies(entries: List<MediaEntry>) {
        _selection.value = entries.map { it.uri.toString() }.toSet()
        _message.value = "${entries.size} posibles copias marcadas para revisar"
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun selectedEntries(order: List<MediaEntry> = library.value.entries): List<MediaEntry> =
        order.filter { it.uri.toString() in _selection.value }

    fun favoriteSelection() {
        val entries = selectedEntries()
        // Si algo de lo seleccionado no es favorito, la acción marca; si ya lo son todos,
        // desmarca. Una sola tecla para las dos intenciones.
        val allFavorite = entries.all { library.value.stateFor(it)?.isFavorite == true }
        entries.forEach { repository.setFavorite(it.uri.toString(), !allFavorite) }
        _message.value = if (allFavorite) "Quitado de favoritos" else "Añadido a favoritos"
        clearSelection()
    }

    fun playSelection(order: List<MediaEntry> = library.value.entries) {
        val entries = selectedEntries(order)
        if (entries.isEmpty()) return
        PlaybackService.play(getApplication(), entries, 0)
        _message.value = "Reproduciendo ${entries.size} archivos seleccionados"
        clearSelection()
    }

    fun addSelectionToQueue(
        playNext: Boolean,
        order: List<MediaEntry> = library.value.entries
    ) {
        val entries = selectedEntries(order)
        if (entries.isEmpty()) return
        addEntriesToQueue(entries, playNext)
        clearSelection()
    }

    fun addEntriesToQueue(entries: List<MediaEntry>, playNext: Boolean) {
        if (entries.isEmpty()) return
        PlaybackService.addToQueue(getApplication(), entries, playNext)
        _message.value = if (playNext) {
            "${entries.size} se reproducirán a continuación"
        } else {
            "${entries.size} añadidos al final de la cola"
        }
    }

    /** Compatibilidad con superficies antiguas: reproducir la selección crea una cola. */
    fun queueSelection() = playSelection()

    fun shareSelection() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (entries.all { !it.isVideo }) "audio/*" else "video/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList(entries.map { it.uri })
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        clearSelection()
    }

    fun deleteSelection() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        val sender = MediaDeleter.requestDelete(getApplication(), entries.map { it.uri })
        if (sender != null) {
            _deleteRequest.value = sender
        } else {
            _message.value = "${entries.size} eliminados"
            refresh()
        }
        clearSelection()
    }

    /** Llamada por la actividad cuando el diálogo del sistema termina. */
    fun onDeleteResolved() {
        refresh()
        _message.value = "Biblioteca actualizada"
    }

    // ------------------------------------------------------------- listas

    fun openPlaylist(id: Long?) {
        _openPlaylist.value = id
        clearSelection()
    }

    fun createPlaylist(name: String, withSelection: Boolean) {
        if (name.isBlank()) return
        val initial = if (withSelection) selectedEntries() else emptyList()
        viewModelScope.launch {
            val id = repository.createPlaylistNow(name, initial)
            _message.value = "Lista «$name» creada"
            clearSelection()
            if (!withSelection && _tab.value == LibraryTab.PLAYLISTS) _openPlaylist.value = id
        }
    }

    fun addSelectionToPlaylist(playlistId: Long) {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        repository.addToPlaylist(playlistId, entries)
        _message.value = "${entries.size} añadidos a la lista"
        clearSelection()
    }

    fun deletePlaylist(id: Long) {
        repository.deletePlaylist(id)
        if (removedPlaylistItems?.first == id) {
            removedPlaylistItems = null
            _playlistUndoAvailable.value = false
        }
        if (_openPlaylist.value == id) _openPlaylist.value = null
    }

    fun renamePlaylist(id: Long, name: String) = repository.renamePlaylist(id, name)

    fun removeFromPlaylist(playlistId: Long, uri: String) =
        repository.removeFromPlaylist(playlistId, uri)

    fun updatePlaylistDetails(id: Long, name: String, description: String, coverUri: String?) {
        if (name.isBlank()) return
        repository.updatePlaylistDetails(id, name, description, coverUri)
        _message.value = "Playlist actualizada"
    }

    fun resolvedPlaylist(id: Long): List<PlaylistResolvedItem> {
        val list = playlists.value.firstOrNull { it.playlist.id == id } ?: return emptyList()
        return PlaylistOrganizer.resolve(list, library.value)
    }

    fun createSmartPlaylist(name: String, rule: SmartPlaylistRule) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createSmartPlaylist(name, rule)
            _openPlaylist.value = id
            _message.value = "Playlist inteligente creada"
        }
    }

    fun playPlaylist(id: Long, shuffled: Boolean = false) {
        val available = resolvedPlaylist(id).mapNotNull { it.media }
        if (available.isEmpty()) {
            _message.value = "La playlist no tiene archivos disponibles"
            return
        }
        val queue = if (shuffled) available.shuffled() else available
        PlaybackService.play(getApplication(), queue, 0)
    }

    fun addPlaylistToQueue(id: Long, playNext: Boolean) {
        val available = resolvedPlaylist(id).mapNotNull { it.media }
        if (available.isEmpty()) {
            _message.value = "No hay archivos disponibles para añadir"
            return
        }
        PlaybackService.addToQueue(getApplication(), available, playNext)
        _message.value = if (playNext) {
            "${available.size} pistas reproducirán después"
        } else {
            "${available.size} pistas añadidas a la cola"
        }
    }

    fun saveCurrentQueue(name: String) {
        if (name.isBlank()) return
        val queue = PlaybackHub.queue.value
        if (queue.isEmpty()) {
            _message.value = "La cola está vacía"
            return
        }
        viewModelScope.launch {
            val id = repository.createPlaylistNow(name, queue, source = "QUEUE")
            _message.value = "Cola guardada como «$name»"
            _openPlaylist.value = id
        }
    }

    fun addEntriesToPlaylist(id: Long, entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        repository.addToPlaylist(id, entries)
        _message.value = "${entries.size} añadidos"
    }

    fun removePlaylistItems(id: Long, itemIds: List<Long>) {
        val removed = playlists.value.firstOrNull { it.playlist.id == id }
            ?.items?.filter { it.id in itemIds }.orEmpty()
        if (removed.isEmpty()) return
        removedPlaylistItems = id to removed
        _playlistUndoAvailable.value = true
        repository.removeFromPlaylist(id, removed.map { it.id })
        _message.value = if (removed.size == 1) "Pista quitada" else "${removed.size} pistas quitadas"
    }

    fun undoPlaylistRemoval() {
        val (id, items) = removedPlaylistItems ?: return
        repository.restorePlaylistItems(id, items)
        removedPlaylistItems = null
        _playlistUndoAvailable.value = false
        _message.value = "Cambio deshecho"
    }

    fun canUndoPlaylistRemoval(id: Long): Boolean = removedPlaylistItems?.first == id

    fun removeUnavailable(id: Long) {
        if (playlists.value.firstOrNull { it.playlist.id == id }?.playlist?.smartRule != null) return
        val missing = resolvedPlaylist(id).filterNot { it.available }.map { it.stored.id }
        if (missing.isEmpty()) {
            _message.value = "No hay elementos ausentes"
        } else {
            removePlaylistItems(id, missing)
        }
    }

    fun sortPlaylist(id: Long, mode: PlaylistSortMode) {
        if (playlists.value.firstOrNull { it.playlist.id == id }?.playlist?.smartRule != null) return
        val resolved = resolvedPlaylist(id)
        repository.reorderPlaylist(id, PlaylistOrganizer.sortedIds(resolved, mode))
        _message.value = "Playlist ordenada por ${mode.name.lowercase()}"
    }

    fun movePlaylistItem(id: Long, itemId: Long, direction: Int) {
        val ids = playlists.value.firstOrNull { it.playlist.id == id }
            ?.items?.sortedBy { it.position }?.map { it.id }.orEmpty()
        val from = ids.indexOf(itemId)
        if (from < 0) return
        val to = (from + direction).coerceIn(ids.indices)
        if (from != to) {
            repository.reorderPlaylist(id, PlaylistOrganizer.move(ids, from, to))
        }
    }

    fun reorderPlaylist(id: Long, orderedIds: List<Long>) {
        repository.reorderPlaylist(id, orderedIds)
    }

    fun importM3u(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            buildString {
                                val buffer = CharArray(8_192)
                                while (length < MAX_M3U_CHARS) {
                                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_M3U_CHARS - length))
                                    if (count < 0) break
                                    append(buffer, 0, count)
                                }
                            }
                        } ?: error("No se pudo abrir el archivo")
                }
                val document = M3uCodec.decode(text)
                require(document.entries.isNotEmpty()) { "La lista está vacía" }
                val fallback = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Playlist importada"
                val id = repository.createPlaylistFromDrafts(
                    name = document.name ?: fallback,
                    entries = M3uCodec.toDrafts(document, library.value),
                    source = "M3U",
                    description = "Importada desde M3U/M3U8"
                )
                _openPlaylist.value = id
                _message.value = "${document.entries.size} elementos importados"
            }.onFailure { _message.value = it.message ?: "No se pudo importar la playlist" }
        }
    }

    fun exportM3u(id: Long, uri: Uri) {
        val list = playlists.value.firstOrNull { it.playlist.id == id } ?: return
        val text = M3uCodec.encode(list.playlist.name, resolvedPlaylist(id))
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                        ?: error("No se pudo crear el archivo")
                }
                _message.value = "Playlist exportada"
            }.onFailure { _message.value = it.message ?: "No se pudo exportar" }
        }
    }

    fun toggleFavorite(entry: MediaEntry) = repository.toggleFavorite(entry.uri.toString())

    // ---------------------------------------------------------- contenido

    /**
     * Arranca la reproducción con la lista visible como cola, de modo que "siguiente"
     * respete lo que el usuario está viendo y no un orden global invisible.
     */
    fun play(entry: MediaEntry, visibleList: List<MediaEntry>, audioOnly: Boolean = false) {
        val queue = visibleList.ifEmpty { listOf(entry) }
        val index = queue.indexOf(entry).coerceAtLeast(0)
        val resumeFrom = library.value.stateFor(entry)
            ?.takeIf { !it.isFinished && it.positionMs > 10_000 }
            ?.positionMs
            ?: 0L
        PlaybackService.play(
            context = getApplication(),
            entries = queue,
            startIndex = index,
            positionMs = resumeFrom,
            audioOnly = audioOnly
        )
    }

    /** Lista que corresponde a la pestaña, la carpeta abierta y el orden actuales. */
    fun visibleEntries(state: LibraryState): List<MediaEntry> {
        val base = when (_tab.value) {
            LibraryTab.ALL -> state.entries
            LibraryTab.VIDEO -> state.videos
            LibraryTab.AUDIO -> state.audios
            LibraryTab.FOLDERS -> _openFolder.value
                ?.let { path -> state.folderTree.find(path)?.files.orEmpty() }
                ?: emptyList()
            LibraryTab.PLAYLISTS -> playlistEntries(state)
            LibraryTab.SMART -> emptyList()
        }
        // Una lista hecha a mano ya tiene un orden: el que le dio su dueño.
        val filtered = LibraryIntelligenceEngine.filter(base, _filter.value)
        return if (playlistKeepsOrder()) filtered else filtered.sortedBy(prefs.value)
    }

    fun intelligence(state: LibraryState): LibraryIntelligence {
        val activeFilter = _filter.value
        if (indexedEntries !== state.entries || indexedFilter != activeFilter) {
            indexedEntries = state.entries
            indexedFilter = activeFilter
            intelligenceCache = LibraryIntelligenceEngine.analyze(
                LibraryIntelligenceEngine.filter(state.entries, activeFilter)
            )
        }
        return intelligenceCache
    }

    private fun playlistEntries(state: LibraryState): List<MediaEntry> {
        val id = _openPlaylist.value ?: return emptyList()
        if (id == FAVORITES_ID) return state.favorites
        val list = playlists.value.firstOrNull { it.playlist.id == id } ?: return emptyList()
        // Se respeta el orden de la lista, no el de la biblioteca.
        return list.items.mapNotNull { state.entryFor(it.uri) }
    }

    /** Las listas de reproducción conservan su propio orden; el criterio global no aplica. */
    fun playlistKeepsOrder(): Boolean =
        _tab.value == LibraryTab.PLAYLISTS && _openPlaylist.value != FAVORITES_ID

    companion object {
        /** Identificador reservado para la lista sintética de favoritos. */
        const val FAVORITES_ID = -1L
        private const val MAX_M3U_CHARS = 8 * 1024 * 1024
    }
}
