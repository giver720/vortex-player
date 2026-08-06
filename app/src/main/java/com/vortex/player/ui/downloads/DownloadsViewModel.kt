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
import com.vortex.player.download.VideoQuality
import com.vortex.player.download.YtDlpEngine
import com.vortex.player.spotify.SpotifyResolver
import com.vortex.player.spotify.SpotifyResult
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
                    embedSubtitles = _embedSubtitles.value
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
    private fun enqueueSpotify(link: String) {
        viewModelScope.launch {
            _resolving.value = true
            _message.value = "Leyendo la lista en Spotify…"
            when (val result = SpotifyResolver.resolve(link)) {
                is SpotifyResult.Error -> _message.value = result.message
                is SpotifyResult.Ok -> {
                    val count = repository.enqueueSpotify(
                        collection = result.collection,
                        base = DownloadRequest(
                            url = link,
                            kind = DownloadKind.AUDIO,
                            audioCodec = _codec.value,
                            audioBitrate = _bitrate.value
                        )
                    )
                    DownloadService.start(getApplication())
                    _url.value = ""
                    _message.value = if (count == 1) {
                        "1 canción en la cola"
                    } else {
                        "$count canciones de «${result.collection.name}» en la cola"
                    }
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
        viewModelScope.launch { repository.clearFinished() }
    }

    fun updateEngine() {
        viewModelScope.launch {
            _message.value = "Actualizando yt-dlp…"
            _message.value = YtDlpEngine.updateBinary(getApplication())
        }
    }
}
