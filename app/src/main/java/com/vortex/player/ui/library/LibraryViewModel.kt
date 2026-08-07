package com.vortex.player.ui.library

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.data.LibraryPreferences
import com.vortex.player.data.LibraryPrefs
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaDeleter
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.MediaRepository
import com.vortex.player.data.PlaylistWithItems
import com.vortex.player.data.SearchResults
import com.vortex.player.data.SortField
import com.vortex.player.data.ViewMode
import com.vortex.player.data.searchLibrary
import com.vortex.player.data.sortedBy
import com.vortex.player.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) {
    ALL("TODO"),
    VIDEO("VÍDEO"),
    AUDIO("AUDIO"),
    FOLDERS("CARPETAS"),
    PLAYLISTS("LISTAS")
}

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

    /** Diálogo de confirmación del sistema pendiente de lanzar tras un borrado. */
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() = repository.refresh()

    /** Hay un reescaneo en curso; alimenta el indicador de tirar para refrescar. */
    val refreshing: StateFlow<Boolean> = repository.refreshing

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

    fun clearSelection() { _selection.value = emptySet() }

    fun selectedEntries(): List<MediaEntry> {
        val state = library.value
        return _selection.value.mapNotNull { state.entryFor(it) }
    }

    fun favoriteSelection() {
        val entries = selectedEntries()
        // Si algo de lo seleccionado no es favorito, la acción marca; si ya lo son todos,
        // desmarca. Una sola tecla para las dos intenciones.
        val allFavorite = entries.all { library.value.stateFor(it)?.isFavorite == true }
        entries.forEach { repository.setFavorite(it.uri.toString(), !allFavorite) }
        _message.value = if (allFavorite) "Quitado de favoritos" else "Añadido a favoritos"
        clearSelection()
    }

    fun queueSelection() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        PlaybackService.play(getApplication(), entries, 0)
        _message.value = "${entries.size} en la cola"
        clearSelection()
    }

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
        repository.createPlaylist(name, if (withSelection) selectedEntries() else emptyList())
        _message.value = "Lista «$name» creada"
        clearSelection()
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
        if (_openPlaylist.value == id) _openPlaylist.value = null
    }

    fun renamePlaylist(id: Long, name: String) = repository.renamePlaylist(id, name)

    fun removeFromPlaylist(playlistId: Long, uri: String) =
        repository.removeFromPlaylist(playlistId, uri)

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
        }
        // Una lista hecha a mano ya tiene un orden: el que le dio su dueño.
        return if (playlistKeepsOrder()) base else base.sortedBy(prefs.value)
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
    }
}
