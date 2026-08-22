package com.vortex.player.ui.downloads

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DestinationStore
import com.vortex.player.download.DownloadConcurrency
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadPolicy
import com.vortex.player.download.DownloadRepository
import com.vortex.player.download.DownloadRequest
import com.vortex.player.download.DownloadService
import com.vortex.player.download.DownloadSchedule
import com.vortex.player.download.EnginePreferences
import com.vortex.player.download.SponsorCategory
import com.vortex.player.download.SponsorMode
import com.vortex.player.download.SponsorSettings
import com.vortex.player.download.SourcePlaylistSelection
import com.vortex.player.download.VideoContainer
import com.vortex.player.download.VideoQuality
import com.vortex.player.download.YtDlpEngine
import com.vortex.player.download.toSelection
import com.vortex.player.data.MediaRepository
import com.vortex.player.spotify.PlaylistSelection
import com.vortex.player.spotify.SpotifyKind
import com.vortex.player.spotify.SpotifyEngine
import com.vortex.player.spotify.SpotifyResolver
import com.vortex.player.spotify.SpotifyResult
import com.vortex.player.spotify.markOwned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DownloadRepository.get(app)

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val destination: StateFlow<Uri?> = DestinationStore.observe(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeJobIds: StateFlow<Set<Long>> = DownloadService.activeJobIds
    val queuePaused: StateFlow<Boolean> = DownloadService.queuePaused

    val concurrentDownloads: StateFlow<Int> =
        EnginePreferences.concurrentDownloads(app).stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DownloadConcurrency.DEFAULT
        )
    val downloadPolicy: StateFlow<DownloadPolicy> =
        EnginePreferences.downloadPolicy(app).stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DownloadPolicy()
        )
    val effectiveConcurrency: StateFlow<Int> = DownloadService.effectiveConcurrency
    val policyBlockReason: StateFlow<String?> = DownloadService.policyBlockReason

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _kind = MutableStateFlow(DownloadKind.VIDEO)
    val kind: StateFlow<DownloadKind> = _kind.asStateFlow()

    private val _quality = MutableStateFlow(VideoQuality.BEST)
    val quality: StateFlow<VideoQuality> = _quality.asStateFlow()

    /** Envase del vídeo. MP4 de salida: es el que reproduce cualquier cosa. */
    private val _container = MutableStateFlow(VideoContainer.MP4)
    val container: StateFlow<VideoContainer> = _container.asStateFlow()

    private val _codec = MutableStateFlow(AudioCodec.MP3)
    val codec: StateFlow<AudioCodec> = _codec.asStateFlow()

    private val _bitrate = MutableStateFlow(AudioBitrate.BEST)
    val bitrate: StateFlow<AudioBitrate> = _bitrate.asStateFlow()

    private val _playlist = MutableStateFlow(true)
    val playlist: StateFlow<Boolean> = _playlist.asStateFlow()

    private val _embedSubtitles = MutableStateFlow(false)
    val embedSubtitles: StateFlow<Boolean> = _embedSubtitles.asStateFlow()

    /**
     * Extras de postprocesado. Apagados de salida: cada uno añade un paso de ffmpeg tras
     * la descarga, y con ello una forma más de que una pista se pierda.
     */
    private val _embedThumbnail = MutableStateFlow(false)
    val embedThumbnail: StateFlow<Boolean> = _embedThumbnail.asStateFlow()

    private val _embedMetadata = MutableStateFlow(false)
    val embedMetadata: StateFlow<Boolean> = _embedMetadata.asStateFlow()

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
            } else {
                // La versión sólo se puede leer con el motor ya en pie.
                loadEngineVersion()
            }
        }
        // Las reglas pesan apenas unos bytes y se revisan semanalmente. Si GitHub no
        // responde, el motor integrado sigue disponible y la pantalla no se bloquea.
        viewModelScope.launch {
            if (SpotifyEngine.shouldAutoUpdate(getApplication())) {
                SpotifyEngine.update(getApplication())
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

    fun setContainer(value: VideoContainer) { _container.value = value }

    /** Versión de yt-dlp en uso. Se lee fuera del hilo principal: toca disco. */
    private val _engineVersion = MutableStateFlow("…")
    val engineVersion: StateFlow<String> = _engineVersion.asStateFlow()

    private val _updatingEngine = MutableStateFlow(false)
    val updatingEngine: StateFlow<Boolean> = _updatingEngine.asStateFlow()

    val autoUpdateEngine: StateFlow<Boolean> =
        EnginePreferences.autoUpdateEnabled(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val lastEngineUpdate: StateFlow<String?> =
        EnginePreferences.lastResult(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val spotifyEngineVersion: StateFlow<String> =
        SpotifyEngine.version(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, SpotifyEngine.bundled.label)

    val lastSpotifyEngineUpdate: StateFlow<String?> =
        SpotifyEngine.lastResult(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val autoUpdateSpotifyEngine: StateFlow<Boolean> =
        SpotifyEngine.autoUpdateEnabled(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _updatingSpotifyEngine = MutableStateFlow(false)
    val updatingSpotifyEngine: StateFlow<Boolean> = _updatingSpotifyEngine.asStateFlow()
    fun setCodec(value: AudioCodec) { _codec.value = value }
    fun setBitrate(value: AudioBitrate) { _bitrate.value = value }
    fun togglePlaylist() { _playlist.value = !_playlist.value }
    fun toggleSubtitles() { _embedSubtitles.value = !_embedSubtitles.value }
    fun toggleThumbnail() { _embedThumbnail.value = !_embedThumbnail.value }
    fun toggleMetadata() { _embedMetadata.value = !_embedMetadata.value }
    fun consumeMessage() { _message.value = null }

    fun setDestination(uri: Uri) {
        viewModelScope.launch {
            // Si Android no cede el permiso permanente, la carpeta no se guarda: aceptarla
            // dejaría a la app creyéndose dueña de un destino donde no puede escribir, y el
            // fallo no saldría hasta que una descarga terminara sin dejar nada.
            _message.value = if (DestinationStore.set(getApplication(), uri)) {
                "Destino actualizado"
            } else {
                "Android no dio permiso permanente sobre esa carpeta. Elige otra."
            }
        }
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
        val isWebLink = link.startsWith("http", ignoreCase = true)
        val isSpotifyUri = link.startsWith("spotify:", ignoreCase = true)
        if (!isWebLink && !isSpotifyUri) {
            if (link.length < 2) {
                _message.value = "Escribe al menos dos caracteres para buscar"
                return
            }
            analyzeSourceCollection(
                target = "ytsearch20:$link",
                displayName = "Resultados para “$link”"
            )
            return
        }

        if (SpotifyResolver.isSpotifyLink(link)) {
            enqueueSpotify(link)
            return
        }

        if (_playlist.value && looksLikePlaylist(link)) {
            analyzeSourceCollection(link)
            return
        }

        enqueueDirect(link)
    }

    private fun enqueueDirect(link: String) {
        viewModelScope.launch {
            if (repository.hasExistingUrl(link)) {
                _message.value = "Ese enlace ya está en la cola"
                return@launch
            }
            repository.enqueue(
                DownloadRequest(
                    url = link,
                    kind = _kind.value,
                    videoQuality = _quality.value,
                    videoContainer = _container.value,
                    audioCodec = _codec.value,
                    audioBitrate = _bitrate.value,
                    playlist = _playlist.value,
                    embedSubtitles = _embedSubtitles.value,
                    embedThumbnail = _embedThumbnail.value,
                    embedMetadata = _embedMetadata.value,
                    sponsor = sponsor.value
                )
            )
            DownloadService.start(getApplication())
            _url.value = ""
            _message.value = "Añadido a la cola"
        }
    }

    /**
     * YouTube entrega listas largas con metadatos planos muy baratos. Se analizan antes
     * de encolar para que el usuario elija pistas y cada una sea un trabajo recuperable.
     */
    private fun analyzeSourceCollection(target: String, displayName: String? = null) {
        if (_resolving.value) return
        viewModelScope.launch {
            _resolving.value = true
            _message.value = if (displayName == null) {
                "Analizando la playlist…"
            } else {
                "Buscando en YouTube…"
            }
            val analysis = YtDlpEngine.analyzePlaylist(target)
            if (analysis == null || !analysis.isPlaylist || analysis.entries.size <= 1) {
                _resolving.value = false
                _message.value = if (analysis == null) {
                    if (displayName == null) {
                        "No se pudo previsualizar; se usará la descarga directa"
                    } else {
                        "No se encontraron resultados"
                    }
                } else {
                    null
                }
                if (displayName == null) enqueueDirect(target)
                return@launch
            }
            val completed = repository.completedSourceIds()
            _sourceSelection.value = analysis
                .copy(name = displayName ?: analysis.name)
                .toSelection(
                    sourceUrl = target,
                    completedIds = completed,
                    folderName = if (displayName == null) analysis.name else null
                )
            _message.value = null
            _resolving.value = false
        }
    }

    private fun looksLikePlaylist(link: String): Boolean {
        val value = link.lowercase()
        return "list=" in value || "/playlist" in value || "/sets/" in value ||
            "/album/" in value
    }

    /**
     * Resuelve el enlace contra el catálogo de Spotify y mete una entrada por canción.
     * De Spotify sólo salen metadatos; el audio se busca luego en YouTube.
     */
    /** Lista resuelta y a la espera de que el usuario elija qué bajar. */
    private val _selection = MutableStateFlow<PlaylistSelection?>(null)
    val selection: StateFlow<PlaylistSelection?> = _selection.asStateFlow()

    private val _sourceSelection = MutableStateFlow<SourcePlaylistSelection?>(null)
    val sourceSelection: StateFlow<SourcePlaylistSelection?> = _sourceSelection.asStateFlow()

    fun toggleSourceEntry(index: Int) {
        _sourceSelection.value = _sourceSelection.value?.toggle(index)
    }

    fun selectAllSourceEntries(selected: Boolean) {
        _sourceSelection.value = _sourceSelection.value?.withAll(selected)
    }

    fun selectOnlyMissingSourceEntries() {
        _sourceSelection.value = _sourceSelection.value?.withOnlyMissing()
    }

    fun cancelSourceSelection() {
        _sourceSelection.value = null
    }

    fun confirmSourceSelection() {
        val selection = _sourceSelection.value ?: return
        val chosen = selection.selectedEntries()
        if (chosen.isEmpty()) {
            _message.value = "No has marcado ningún elemento"
            return
        }
        viewModelScope.launch {
            val count = repository.enqueuePlaylistEntries(
                entries = chosen,
                folder = selection.folderName,
                base = DownloadRequest(
                    url = selection.sourceUrl,
                    kind = _kind.value,
                    videoQuality = _quality.value,
                    videoContainer = _container.value,
                    audioCodec = _codec.value,
                    audioBitrate = _bitrate.value,
                    playlist = false,
                    embedSubtitles = _embedSubtitles.value,
                    embedThumbnail = _embedThumbnail.value,
                    embedMetadata = _embedMetadata.value,
                    sponsor = sponsor.value
                )
            )
            DownloadService.start(getApplication())
            _sourceSelection.value = null
            _url.value = ""
            _message.value = "$count elementos añadidos a la cola"
        }
    }

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
            val result = SpotifyResolver.resolve(getApplication(), link) { folder, page ->
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

    fun cancel(id: Long) = DownloadService.cancel(id)

    fun toggleQueuePaused() {
        DownloadService.setQueuePaused(getApplication(), !queuePaused.value)
        _message.value = when {
            !queuePaused.value -> "Cola reanudada"
            activeJobIds.value.isNotEmpty() ->
                "La cola se detendrá al terminar las descargas activas"
            else -> "Cola pausada"
        }
    }

    fun retry(id: Long) {
        viewModelScope.launch {
            repository.retry(id)
            DownloadService.start(getApplication())
        }
    }

    fun prioritize(id: Long, first: Boolean) {
        viewModelScope.launch {
            repository.prioritize(id, first)
            _message.value = if (first) "Descarga movida al principio" else
                "Descarga enviada al final"
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch {
            repository.remove(id)
            withContext(Dispatchers.IO) {
                DestinationStore.workspace(getApplication(), id).deleteRecursively()
            }
        }
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
            val wasPaused = queuePaused.value
            val workspaceIds = downloads.value.map { it.id }
            DownloadService.setQueuePaused(getApplication(), true)
            DownloadService.cancelAll()
            repository.clearAll()
            withContext(Dispatchers.IO) {
                workspaceIds.forEach {
                    DestinationStore.workspace(getApplication(), it).deleteRecursively()
                }
            }
            if (!wasPaused) DownloadService.setQueuePaused(getApplication(), false)
            _message.value = "Cola vaciada"
        }
    }

    /** Quita lo pendiente y conserva el historial de lo ya terminado. */
    fun cancelPending() {
        viewModelScope.launch {
            val wasPaused = queuePaused.value
            val workspaceIds = downloads.value.filter { !it.status.isTerminal }.map { it.id }
            DownloadService.setQueuePaused(getApplication(), true)
            DownloadService.cancelAll()
            repository.clearPending()
            withContext(Dispatchers.IO) {
                workspaceIds.forEach {
                    DestinationStore.workspace(getApplication(), it).deleteRecursively()
                }
            }
            if (!wasPaused) DownloadService.setQueuePaused(getApplication(), false)
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

    /**
     * Actualiza yt-dlp a petición del usuario.
     *
     * Se anota el resultado igual que hace la actualización automática. Antes no se
     * anotaba: una actualización manual no dejaba rastro y tampoco reiniciaba el contador
     * diario, así que la automática volvía a lanzarse justo después para nada.
     */
    fun updateEngine() {
        if (_updatingEngine.value) return
        viewModelScope.launch {
            _updatingEngine.value = true
            _message.value = "Actualizando yt-dlp…"
            val result = YtDlpEngine.updateBinary(getApplication())
            EnginePreferences.record(getApplication(), result)
            _engineVersion.value = withContext(Dispatchers.IO) {
                YtDlpEngine.versionOrUnknown(getApplication())
            }
            _message.value = result
            _updatingEngine.value = false
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { EnginePreferences.setAutoUpdate(getApplication(), enabled) }
    }

    fun setConcurrentDownloads(value: Int) {
        viewModelScope.launch {
            val safeValue = DownloadConcurrency.clamp(value)
            EnginePreferences.setConcurrentDownloads(getApplication(), safeValue)
            // El servicio observa este ajuste y adapta las ranuras sin interrumpir los
            // procesos que ya están trabajando.
            _message.value = "$safeValue descarga${if (safeValue == 1) "" else "s"} a la vez"
        }
    }

    fun setAdaptiveConcurrency(enabled: Boolean) {
        viewModelScope.launch {
            EnginePreferences.setAdaptiveConcurrency(getApplication(), enabled)
        }
    }

    fun setYoutubeLimit(value: Int) {
        viewModelScope.launch {
            EnginePreferences.setSourceLimits(
                getApplication(),
                value,
                downloadPolicy.value.otherLimit
            )
        }
    }

    fun setOtherLimit(value: Int) {
        viewModelScope.launch {
            EnginePreferences.setSourceLimits(
                getApplication(),
                downloadPolicy.value.youtubeLimit,
                value
            )
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { EnginePreferences.setWifiOnly(getApplication(), enabled) }
    }

    fun setChargingOnly(enabled: Boolean) {
        viewModelScope.launch { EnginePreferences.setChargingOnly(getApplication(), enabled) }
    }

    fun setDownloadSchedule(schedule: DownloadSchedule) {
        viewModelScope.launch {
            EnginePreferences.setDownloadSchedule(getApplication(), schedule)
        }
    }

    fun setBandwidthLimit(kbps: Int) {
        viewModelScope.launch { EnginePreferences.setBandwidthLimit(getApplication(), kbps) }
    }

    fun setAutomaticRetries(value: Int) {
        viewModelScope.launch {
            EnginePreferences.setMaxAutomaticRetries(getApplication(), value)
        }
    }

    /** Actualiza sólo las reglas declarativas del catálogo; nunca descarga código. */
    fun updateSpotifyEngine() {
        if (_updatingSpotifyEngine.value) return
        viewModelScope.launch {
            _updatingSpotifyEngine.value = true
            _message.value = "Actualizando el motor Spotify…"
            val result = SpotifyEngine.update(getApplication())
            _message.value = result
            _updatingSpotifyEngine.value = false
        }
    }

    fun setSpotifyAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { SpotifyEngine.setAutoUpdate(getApplication(), enabled) }
    }

    private fun loadEngineVersion() {
        viewModelScope.launch {
            _engineVersion.value = withContext(Dispatchers.IO) {
                YtDlpEngine.versionOrUnknown(getApplication())
            }
        }
    }
}
