package com.vortex.player.ui.spotify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.data.MediaRepository
import com.vortex.player.data.db.SpotifyPlaylistEntity
import com.vortex.player.data.db.SpotifyTrackEntity
import com.vortex.player.spotify.LocalAudioCandidate
import com.vortex.player.spotify.SpotifyAccountState
import com.vortex.player.spotify.SpotifyAuth
import com.vortex.player.spotify.SpotifyLibraryRepository
import com.vortex.player.spotify.SpotifyLocalMatch
import com.vortex.player.spotify.SpotifyLocalMatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SpotifyTrackUi(
    val track: SpotifyTrackEntity,
    val localMatch: SpotifyLocalMatch?
)

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyHubViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SpotifyLibraryRepository.get(app)
    private val mediaRepository = MediaRepository.get(app)

    val account: StateFlow<SpotifyAccountState> = SpotifyAuth.state
    private val accountId = account.map { (it as? SpotifyAccountState.Connected)?.accountId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playlists: StateFlow<List<SpotifyPlaylistEntity>> = accountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.playlists(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    val selectedPlaylist: StateFlow<SpotifyPlaylistEntity?> =
        combine(playlists, selectedPlaylistId) { lists, id -> lists.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val cachedTracks: StateFlow<List<SpotifyTrackEntity>> =
        combine(accountId, selectedPlaylistId) { id, playlist -> id to playlist }
            .flatMapLatest { (id, playlist) ->
                if (id == null || playlist == null) flowOf(emptyList())
                else repository.tracks(id, playlist)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tracks: StateFlow<List<SpotifyTrackUi>> =
        combine(cachedTracks, mediaRepository.library) { tracks, library ->
            val local = library.audios.map { entry ->
                LocalAudioCandidate(
                    uri = entry.uri.toString(),
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    durationMs = entry.durationMs
                )
            }
            tracks.map { track ->
                SpotifyTrackUi(track, SpotifyLocalMatcher.best(track, local))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        SpotifyAuth.initialize(app)
        if (mediaRepository.library.value.loading) mediaRepository.refresh()
    }

    fun refreshPlaylists() {
        val id = accountId.value ?: return
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            repository.syncPlaylists(id).onFailure { _error.value = it.userMessage() }
            _refreshing.value = false
        }
    }

    fun openPlaylist(id: String) {
        _selectedPlaylistId.value = id
        refreshSelectedPlaylist()
    }

    fun closePlaylist() {
        _selectedPlaylistId.value = null
    }

    fun refreshSelectedPlaylist() {
        val id = accountId.value ?: return
        val playlist = selectedPlaylistId.value ?: return
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            repository.syncPlaylistTracks(id, playlist)
                .onFailure { _error.value = it.userMessage() }
            _refreshing.value = false
        }
    }

    fun consumeError() {
        _error.value = null
    }

    private fun Throwable.userMessage(): String = message ?: "No se pudo sincronizar Spotify"
}
