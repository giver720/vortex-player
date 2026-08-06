package com.vortex.player.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.MediaRepository
import com.vortex.player.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryTab(val label: String) {
    ALL("TODO"),
    VIDEO("VÍDEO"),
    AUDIO("AUDIO"),
    FOLDERS("CARPETAS")
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MediaRepository.get(app)

    val library: StateFlow<LibraryState> = repository.library

    private val _tab = MutableStateFlow(LibraryTab.ALL)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _openFolder = MutableStateFlow<String?>(null)
    val openFolder: StateFlow<String?> = _openFolder.asStateFlow()

    fun refresh() = repository.refresh()

    fun selectTab(value: LibraryTab) {
        _tab.value = value
        if (value != LibraryTab.FOLDERS) _openFolder.value = null
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun openFolder(path: String?) {
        _openFolder.value = path
    }

    fun toggleFavorite(entry: MediaEntry) = repository.toggleFavorite(entry.uri.toString())

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

    /** Lista que corresponde a la pestaña y la búsqueda actuales. */
    fun visibleEntries(state: LibraryState): List<MediaEntry> {
        val base = when (_tab.value) {
            LibraryTab.ALL -> state.entries
            LibraryTab.VIDEO -> state.videos
            LibraryTab.AUDIO -> state.audios
            LibraryTab.FOLDERS -> _openFolder.value
                ?.let { path -> state.entries.filter { it.folderPath == path } }
                ?: emptyList()
        }
        val q = _query.value.trim()
        if (q.isBlank()) return base
        return base.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.displayName.contains(q, ignoreCase = true) ||
                it.folderName.contains(q, ignoreCase = true)
        }
    }
}
