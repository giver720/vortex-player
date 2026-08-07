package com.vortex.player.ui.downloads

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DestinationStore
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadRepository
import com.vortex.player.download.DownloadRequest
import com.vortex.player.download.DownloadService
import com.vortex.player.download.EnginePreferences
import com.vortex.player.download.SponsorCategory
import com.vortex.player.download.SponsorMode
import com.vortex.player.download.SponsorSettings
import com.vortex.player.download.VideoQuality
import com.vortex.player.download.YtDlpEngine
import com.vortex.player.data.MediaRepository
import com.vortex.player.spotify.PlaylistSelection
import com.vortex.player.spotify.SpotifyKind
import com.vortex.player.spotify.SpotifyResolver
import com.vortex.player.spotify.SpotifyResult
import com.vortex.player.spotify.markOwned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DownloadRepository.get(app)

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val destination: StateFlow<Uri?> = DestinationStore.observe(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeJobId: StateFlow<Long?> = DownloadService.activeJobId

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _kind = MutableStateFlow(DownloadKind.VIDEO)
    val kind: StateFlow<DownloadKind> = _kind.asStateFlow()

    private val _quality = MutableStateFlow(VideoQuality.BEST)
    val quality: StateFlow<VideoQuality> = _quality.asStateFlow()

    private val _codec = MutableStateFlow(AudioCodec.MP3)
    val codec: StateFlow<AudioCodec> = _codec.asStateFlow()

    private val _bitrate = MutableStateFlow(AudioBitrate.BEST)
    val bitrate: StateFlow<AudioBitrate> = _bitrate.asStateFlow()

    private val _playlist = MutableStateFlow(true)
    val playlist: StateFlow<Boolean> = _playlist.asStateFlow()

    private val _embedSubtitles = MutableStateFlow(false)
    val embedSubtitles: StateFlow<Boolean> = _embedSubtitles.asStateFlow()

    val sponsor: StateFlow<SponsorSettings> = EnginePreferences.sponsorSettings(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SponsorSettings())

    fun setSponsorMode(mode: SponsorMode) {
        viewModelScope.launch {
            val current = sponsor.value
            // Al activarlo por primera vez sin categorías elegidas no pasaría nada, así
            // que se siembran las habituales en lugar de dejar un ajuste inerte.
            val categories = current.categories.ifEmpty { SponsorCategory.DEFAULT_VIDEO }
            EnginePreferences.setSponsor(
                getApplication(),
                current.copy(mode = mode, categories = categories)
            )
        }
    }

    fun toggleSponsorCategory(category: SponsorCategory) {
        viewModelScope.launch {
            val current = sponsor.value
            val next = if (category in current.categories) {
                current.categories - category
            } else {
                current.categories + category
            }
            EnginePreferences.setSponsor(getApplication(), current.copy(categories = next))
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _engineReady = MutableStateFlow<Boolean?>(null)
    val engineReady: StateFlow<Boolean?> = _engineReady.asStateFlow()

    init {
        // La primera inicialización descomprime Python y ffmpeg; se hace nada más entrar
        // para que el usuario no descubra la espera justo al pulsar "descargar".
        viewModelScope.launch {
            _engineReady.value = YtDlpEngine.ensureInitialized(getApplication())
            if (_engineReady.value == false) {
                _message.value = "yt-dlp no arrancó: ${YtDlpEngine.initError}"
            }
        }
    }

    fun setUrl(value: String) {
        _url.value = value
        // Un enlace con `list=` casi siempre es una lista: se activa solo, pero el
        // usuario puede desactivarlo si sólo quiere ese vídeo.
        if (value.contains("list=")) _playlist.value = true
    }

    fun setKind(value: DownloadKind) { _kind.value = value }
    fun setQuality(value: VideoQuality) { _quality.value = value }
    fun setCodec(value: AudioCodec) { _codec.value = value }
    fun setBitrate(value: AudioBitrate) { _bitrate.value = value }
    fun togglePlaylist() { _playlist.value = !_playlist.value }
    fun toggleSubtitles() { _embedSubtitles.value = !_embedSubtitles.value }
    fun consumeMessage() { _message.value = null }

    fun setDestination(uri: Uri) {
        viewModelScope.launch { DestinationStore.set(getApplication(), uri) }
    }

    fun useDefaultDestination() {
        viewModelScope.launch { DestinationStore.clear(getApplication()) }
    }

    /** El enlace pegado apunta a Spotify: la interfaz lo dice y el flujo cambia. */
    val isSpotifyLink: StateFlow<Boolean> = _url
        .map { SpotifyResolver.isSpotifyLink(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    /** La última lista se quedó corta: Spotify no dejó leerla entera. */
    private val _partialWarning = MutableStateFlow(false)
    val partialWarning: StateFlow<Boolean> = _partialWarning.asStateFlow()

    fun dismissPartialWarning() { _partialWarning.value = false }

    fun enqueue() {
        val link = _url.value.trim()
        if (link.isBlank()) {
            _message.value = "Pega primero un enlace"
            return
        }
        if (!link.startsWith("http", ignoreCase = true) &&
            !link.startsWith("spotify:", ignoreCase = true)
        ) {
            _message.value = "Ese texto no parece un enlace"
            return
        }

        if (SpotifyResolver.isSpotifyLink(link)) {
            enqueueSpotify(link)
            return
        }

        viewModelScope.launch {
            repository.enqueue(
                DownloadRequest(
                    url = link,
                    kind = _kind.value,
                    videoQuality = _quality.value,
                    audioCodec = _codec.value,
                    audioBitrate = _bitrate.value,
                    playlist = _playlist.value,
                    embedSubtitles = _embedSubtitles.value,
                    sponsor = sponsor.value
                )
            )
            DownloadService.start(getApplication())
            _url.value = ""
            _message.value = "Añadido a la cola"
        }
    }

    /**
     * Resuelve el enlace contra el catálogo de Spotify y mete una entrada por canción.
     * De Spotify sólo salen metadatos; el audio se busca luego en YouTube Music.
     */
    /** Lista resuelta y a la espera de que el usuario elija qué bajar. */
    private val _selection = MutableStateFlow<PlaylistSelection?>(null)
    val selection: StateFlow<PlaylistSelection?> = _selection.asStateFlow()

    fun toggleTrack(index: Int) {
        _selection.value = _selection.value?.toggle(index)
    }

    fun selectAllTracks(selected: Boolean) {
        _selection.value = _selection.value?.withAll(selected)
    }

    fun selectOnlyMissing() {
        _selection.value = _selection.value?.withOnlyMissing()
    }

    fun cancelSelection() {
        _selection.value = null
    }

    /** Encola lo que quedó marcado y cierra la pantalla de selección. */
    fun confirmSelection() {
        val selection = _selection.value ?: return
        val chosen = selection.selectedTracks()
        if (chosen.isEmpty()) {
            _message.value = "No has marcado ninguna canción"
            return
        }
        viewModelScope.launch {
            val current = sponsor.value
            val count = repository.enqueueSpotifyTracks(
                tracks = chosen,
                folder = selection.folderName,
                base = DownloadRequest(
                    url = selection.sourceUrl,
                    kind = DownloadKind.AUDIO,
                    audioCodec = _codec.value,
                    audioBitrate = _bitrate.value,
                    sponsor = if (current.isActive) {
                        current.copy(
                            categories = current.categories + SponsorCategory.DEFAULT_AUDIO
                        )
                    } else {
                        current
                    }
                )
            )
            DownloadService.start(getApplication())
            _selection.value = null
            _url.value = ""
            _message.value = if (count == 1) {
                "1 canción en la cola"
            } else {
                "$count canciones en la cola"
            }
        }
    }

    private fun enqueueSpotify(link: String) {
        viewModelScope.launch {
            _resolving.value = true
            _message.value = "Leyendo la lista en Spotify…"

            val current = sponsor.value
            val base = DownloadRequest(
                url = link,
                kind = DownloadKind.AUDIO,
                audioCodec = _codec.value,
                audioBitrate = _bitrate.value,
                // El audio sale de un vídeo musical: si SponsorBlock está activo conviene
                // quitar además lo que no es la canción, o el MP3 arrastra la charla
                // previa y la despedida del final.
                sponsor = if (current.isActive) {
                    current.copy(categories = current.categories + SponsorCategory.DEFAULT_AUDIO)
                } else {
                    current
                }
            )

            // Lo que ya se bajó alguna vez, por identificador de pista y por nombre de
            // archivo presente en la biblioteca. Se consulta una sola vez, no por canción.
            val completedIds = repository.completedSourceIds()
            val libraryNames = MediaRepository.get(getApplication()).library.value.entries
                .map { it.displayName.substringBeforeLast('.').lowercase() }
                .toSet()

            // La pantalla de selección se llena por bloques: en una lista de trescientas
            // ya se puede empezar a mirar mientras llegan las demás páginas.
            val result = SpotifyResolver.resolve(link) { folder, page ->
                val marked = markOwned(page, folder, completedIds, libraryNames)
                val current = _selection.value
                _selection.value = if (current == null) {
                    PlaylistSelection(
                        name = folder ?: page.firstOrNull()?.title.orEmpty(),
                        kind = if (folder == null) SpotifyKind.TRACK else SpotifyKind.PLAYLIST,
                        coverUrl = page.firstOrNull()?.coverUrl,
                        tracks = marked,
                        sourceUrl = link
                    )
                } else {
                    current.copy(tracks = current.tracks + marked)
                }
            }

            when (result) {
                is SpotifyResult.Error -> {
                    _message.value = result.message
                    _selection.value = null
                }
                is SpotifyResult.Ok -> {
                    val collection = result.collection
                    _selection.value = _selection.value?.copy(
                        name = collection.name,
                        kind = collection.kind,
                        coverUrl = collection.coverUrl,
                        resolving = false,
                        partial = collection.partial
                    )
                    _partialWarning.value = collection.partial
                    _message.value = null
                }
            }
            _resolving.value = false
        }
    }

    fun cancelCurrent() = DownloadService.cancelCurrent(getApplication())

    fun retry(id: Long) {
        viewModelScope.launch {
            repository.retry(id)
            DownloadService.start(getApplication())
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun clearFinished() {
        viewModelScope.launch {
            repository.clearFinished()
            _message.value = "Historial limpiado"
        }
    }

    val failedCount: StateFlow<Int> = repository.failedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Vacía la cola entera. Se cancela primero lo que se esté descargando: borrar la fila
     * sin parar el proceso dejaría a yt-dlp trabajando para nadie.
     */
    fun clearAll() {
        viewModelScope.launch {
            DownloadService.cancelCurrent(getApplication())
            repository.clearAll()
            _message.value = "Cola vaciada"
        }
    }

    /** Quita lo pendiente y conserva el historial de lo ya terminado. */
    fun cancelPending() {
        viewModelScope.launch {
            DownloadService.cancelCurrent(getApplication())
            repository.clearPending()
            _message.value = "Descargas pendientes canceladas"
        }
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            val count = repository.retryAllFailed()
            if (count > 0) {
                DownloadService.start(getApplication())
                _message.value = "$count descargas de vuelta en la cola"
            } else {
                _message.value = "No hay descargas fallidas"
            }
        }
    }

    fun updateEngine() {
        viewModelScope.launch {
            _message.value = "Actualizando yt-dlp…"
            _message.value = YtDlpEngine.updateBinary(getApplication())
        }
    }
}
