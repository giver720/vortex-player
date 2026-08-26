package com.vortex.player.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vortex.player.audio.AudioSettings
import com.vortex.player.audio.VlcEqualizerPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

internal fun VideoScaleMode.toVlcScaleType(): MediaPlayer.ScaleType = when (this) {
    VideoScaleMode.BEST_FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    VideoScaleMode.FIT_SCREEN -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    VideoScaleMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
    VideoScaleMode.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
    VideoScaleMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
    VideoScaleMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
    VideoScaleMode.RATIO_16_10 -> MediaPlayer.ScaleType.SURFACE_16_10
    VideoScaleMode.RATIO_221_1 -> MediaPlayer.ScaleType.SURFACE_221_1
    VideoScaleMode.RATIO_235_1 -> MediaPlayer.ScaleType.SURFACE_235_1
    VideoScaleMode.RATIO_239_1 -> MediaPlayer.ScaleType.SURFACE_239_1
    VideoScaleMode.RATIO_5_4 -> MediaPlayer.ScaleType.SURFACE_5_4
}

/**
 * libVLC presentado como un [Player] de Media3.
 *
 * El motivo de este envoltorio es que toda la app —notificación, pantalla de bloqueo,
 * ventana flotante y reproductor— habla con una única `MediaSession`. Si VLC viviera
 * aparte, cada superficie necesitaría su propio camino y el modo solo-audio dejaría de
 * ser un simple interruptor.
 */
@UnstableApi
class VlcPlayer(
    private val context: Context,
    looper: Looper = Util.getCurrentOrMainLooper()
) : androidx.media3.common.SimpleBasePlayer(looper), EngineControls {

    private val handler = Handler(looper)

    private val libVlc: LibVLC = LibVLC(
        context,
        arrayListOf(
            // Permite cambiar la velocidad sin que las voces suenen a helio.
            "--audio-time-stretch",
            // Normaliza automáticamente los archivos que incluyen etiquetas ReplayGain.
            // En medios sin esas etiquetas, la ganancia predeterminada de 0 dB no altera nada.
            "--audio-replay-gain-mode=track",
            "--audio-replay-gain-preamp=0.0",
            "--audio-replay-gain-default=0.0",
            "--audio-replay-gain-peak-protection"
        )
    )

    private val mediaPlayer = MediaPlayer(libVlc)

    private var playlist: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0
    private var openFd: ParcelFileDescriptor? = null

    private var vlcPlaybackState: Int = Player.STATE_IDLE
    private var wantsToPlay: Boolean = false
    private var lastKnownPositionMs: Long = 0L
    private var durationMs: Long = C.TIME_UNSET
    private var bufferedPercent: Float = 0f
    private var speed: Float = 1f
    private var currentVolume: Float = 1f
    private var boostVolumePercent: Int = NORMAL_VLC_VOLUME
    internal var appliedVlcVolumePercent: Int = NORMAL_VLC_VOLUME
        private set
    private var videoSize: VideoSize = VideoSize.UNKNOWN
    private var pendingError: PlaybackException? = null
    private var repeat: Int = Player.REPEAT_MODE_OFF
    private var shuffle: Boolean = false
    private val lowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .isLowRamDevice
    private var activePlan = PlaybackIntelligencePlanner.plan("file://", null, lowRamDevice)
    private var decoderMode = DecoderMode.HARDWARE
    private var recoveryCount = 0
    private var recoveryScheduled = false
    private var recovering = false
    private var lastProgressElapsedMs = SystemClock.elapsedRealtime()
    private var lastDisplayedFrameElapsedMs = SystemClock.elapsedRealtime()
    private var lastDisplayedFramePositionMs = 0L
    private var lastDisplayedPictures = 0
    private var voutCount = 0
    private var videoAttachGeneration = 0L
    private var displayedPicturesAtAttach: Int? = null
    private var seekAfterPlayingMs: Long? = null
    private var ignoreNextStoppedEvent = false
    private var released = false
    private var lastDiagnosticsElapsedMs = 0L
    private var activeMediaKey: String? = null
    private val sessionSoftwareMediaKeys = mutableSetOf<String>()
    private var requestedAudioTrack: Int? = null
    private var requestedSubtitleTrack: Int = -1
    private var subtitleSelectionExplicit = false
    private var requestedSubtitleDelayMs = 0L
    private val externalSubtitleUris = linkedSetOf<String>()
    private var restoreExternalSubtitlesOnPlaying = false

    private val mutableDiagnostics = MutableStateFlow(PlaybackDiagnostics())
    override val diagnostics: StateFlow<PlaybackDiagnostics> = mutableDiagnostics.asStateFlow()
    private val mutableVideoOutputReady = MutableStateFlow(false)
    override val videoOutputReady: StateFlow<Boolean> = mutableVideoOutputReady.asStateFlow()

    /**
     * Orden en el que se recorre [playlist], como lista de índices.
     *
     * Hace falta llevarlo aquí porque VLC no tiene cola: el salto de pista lo decidimos
     * nosotros al terminar cada medio, y `SimpleBasePlayer` calcula "siguiente" y
     * "anterior" siempre en orden de lista, sin enterarse del aleatorio.
     */
    private var order: List<Int> = emptyList()

    private var videoLayout: VLCVideoLayout? = null
    private var videoEnabled: Boolean = true
    private var videoScaleMode: VideoScaleMode = VideoScaleMode.BEST_FIT

    /** Último ajuste aplicado; se reaplica al abrir cada medio por consistencia entre pistas. */
    private var audioSettings = AudioSettings()

    override val engineName: String = "VLC"

    private val stallWatchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val stalledFor = now - lastProgressElapsedMs
            if (
                wantsToPlay &&
                vlcPlaybackState == Player.STATE_READY &&
                stalledFor >= STALL_TIMEOUT_MS &&
                !recoveryScheduled
            ) {
                scheduleRecovery(
                    reason = "La reproducción dejó de avanzar"
                )
            } else {
                inspectVideoLiveness(now)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        mediaPlayer.setEventListener { event ->
            handler.post {
                // libVLC puede haber emitido el evento justo antes de release(); nunca
                // debemos consultar un objeto nativo que ya dejó de existir.
                if (!released) onVlcEvent(event)
            }
        }
        handler.postDelayed(stallWatchdog, WATCHDOG_INTERVAL_MS)
    }

    // ---------------------------------------------------------------- eventos

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                recoveryScheduled = false
                lastProgressElapsedMs = SystemClock.elapsedRealtime()
                vlcPlaybackState = Player.STATE_BUFFERING
                updateDiagnostics(health = PlaybackHealth.OPENING)
            }

            MediaPlayer.Event.Buffering -> {
                bufferedPercent = event.buffering / 100f
                // VLC reporta 100 % de buffer también mientras reproduce; sólo el
                // buffer parcial significa realmente "esperando datos".
                vlcPlaybackState = if (event.buffering < 100f) {
                    Player.STATE_BUFFERING
                } else {
                    Player.STATE_READY
                }
                updateDiagnostics(
                    health = if (event.buffering < 100f) {
                        PlaybackHealth.BUFFERING
                    } else if (recovering) {
                        PlaybackHealth.RECOVERING
                    } else {
                        PlaybackHealth.PLAYING
                    }
                )
            }

            MediaPlayer.Event.Playing -> {
                vlcPlaybackState = Player.STATE_READY
                wantsToPlay = true
                recovering = false
                recoveryScheduled = false
                lastProgressElapsedMs = SystemClock.elapsedRealtime()
                val deferredSeek = seekAfterPlayingMs
                seekAfterPlayingMs = null
                if (deferredSeek != null) {
                    // Seek preciso después de inicializar demuxer y decodificador. Usar
                    // `:start-time` al reabrir en software podía empezar a mitad de un GOP
                    // y mostrar verde hasta el próximo fotograma clave.
                    mediaPlayer.setTime(deferredSeek, false)
                    lastKnownPositionMs = deferredSeek
                }
                resetVideoLivenessWatch(lastKnownPositionMs)
                // Algunos dispositivos crean la salida de audio después de Playing; volver
                // a mandar aquí el valor evita que el boost se pierda al cambiar de pista.
                applyVlcVolume()
                requestedAudioTrack?.let { mediaPlayer.audioTrack = it }
                if (subtitleSelectionExplicit) mediaPlayer.spuTrack = requestedSubtitleTrack
                if (requestedSubtitleDelayMs != 0L) {
                    mediaPlayer.setSpuDelay(requestedSubtitleDelayMs * 1_000L)
                }
                if (restoreExternalSubtitlesOnPlaying) {
                    externalSubtitleUris.forEach { subtitleUri ->
                        mediaPlayer.addSlave(
                            org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                            Uri.parse(subtitleUri),
                            true
                        )
                    }
                    restoreExternalSubtitlesOnPlaying = false
                }
                refreshDuration()
                publishRuntimeDiagnostics(force = true, health = PlaybackHealth.PLAYING)
            }

            MediaPlayer.Event.Paused -> {
                vlcPlaybackState = Player.STATE_READY
                wantsToPlay = false
                lastKnownPositionMs = mediaPlayer.time.coerceAtLeast(0L)
                updateDiagnostics(health = PlaybackHealth.IDLE)
            }

            MediaPlayer.Event.Stopped -> {
                if (ignoreNextStoppedEvent) {
                    ignoreNextStoppedEvent = false
                } else if (!recovering) {
                    vlcPlaybackState = Player.STATE_IDLE
                    wantsToPlay = false
                    updateDiagnostics(health = PlaybackHealth.IDLE)
                }
            }

            MediaPlayer.Event.EndReached -> {
                lastKnownPositionMs = durationMs.takeIf { it != C.TIME_UNSET } ?: lastKnownPositionMs
                val next = if (repeat == Player.REPEAT_MODE_ONE) null else stepInOrder(1)
                if (repeat == Player.REPEAT_MODE_ONE) {
                    seekToVlc(0L)
                    mediaPlayer.play()
                } else if (next != null) {
                    currentIndex = next
                    openCurrent(0L, play = true)
                } else {
                    vlcPlaybackState = Player.STATE_ENDED
                    wantsToPlay = false
                    updateDiagnostics(health = PlaybackHealth.IDLE)
                }
            }

            MediaPlayer.Event.EncounteredError -> {
                val recovered = scheduleRecovery(
                    reason = if (decoderMode == DecoderMode.HARDWARE) {
                        "El decodificador hardware falló; cambiando a software"
                    } else {
                        "VLC perdió el medio; reabriendo en modo seguro"
                    }
                )
                if (!recovered) signalPlaybackFailure()
            }

            MediaPlayer.Event.TimeChanged -> {
                val next = event.timeChanged.coerceAtLeast(0L)
                if (next + SEEK_RESET_THRESHOLD_MS < lastKnownPositionMs) {
                    resetVideoLivenessWatch(next)
                }
                if (next != lastKnownPositionMs) lastProgressElapsedMs = SystemClock.elapsedRealtime()
                lastKnownPositionMs = next
                refreshVideoOutputReadiness()
                publishRuntimeDiagnostics()
            }

            MediaPlayer.Event.LengthChanged -> refreshDuration()

            MediaPlayer.Event.Vout -> {
                voutCount = event.voutCount
                val vt = mediaPlayer.currentVideoTrack
                videoSize = if (vt != null && vt.width > 0) {
                    VideoSize(vt.width, vt.height)
                } else {
                    VideoSize.UNKNOWN
                }
                applyVideoScale()
                val generation = videoAttachGeneration
                handler.postDelayed({
                    if (
                        !released &&
                        generation == videoAttachGeneration &&
                        videoLayout != null &&
                        voutCount > 0 &&
                        !mutableVideoOutputReady.value
                    ) {
                        val statsAvailable = runCatching {
                            mediaPlayer.media?.stats?.displayedPictures
                        }.getOrNull() != null
                        // En pausa VLC no genera un cuadro nuevo. En builds sin estadísticas,
                        // Vout es la mejor confirmación disponible de que la superficie existe.
                        if (!wantsToPlay || !statsAvailable) {
                            mutableVideoOutputReady.value = true
                        }
                    }
                }, VIDEO_OUTPUT_FALLBACK_MS)
                publishRuntimeDiagnostics(force = true)
            }
        }
        invalidateState()
    }

    private fun refreshDuration() {
        val length = mediaPlayer.length
        durationMs = if (length > 0) length else C.TIME_UNSET
    }

    /**
     * El reloj puede seguir avanzando gracias al audio aunque el decodificador de vídeo esté
     * entregando cero imágenes. Las estadísticas `displayedPictures` de VLC permiten distinguir
     * ese caso de un bloqueo general y cambiar a software sin intervención del usuario.
     */
    private fun inspectVideoLiveness(now: Long) {
        if (!wantsToPlay || vlcPlaybackState != Player.STATE_READY || recoveryScheduled) return
        val stats = runCatching { mediaPlayer.media?.stats }.getOrNull()
        val displayed = stats?.displayedPictures
        if (displayed != null && displayed > lastDisplayedPictures) {
            lastDisplayedPictures = displayed
            lastDisplayedFrameElapsedMs = now
            lastDisplayedFramePositionMs = lastKnownPositionMs
            return
        }

        val hasVideoTrack = runCatching {
            mediaPlayer.videoTracksCount > 0 || mediaPlayer.currentVideoTrack != null || voutCount > 0
        }.getOrDefault(false)
        val shouldRecover = VideoLivenessPolicy.shouldFallbackToSoftware(
            VideoLivenessSample(
                playbackActive = true,
                videoEnabled = videoEnabled,
                outputAttached = videoLayout != null,
                hasVideoTrack = hasVideoTrack,
                statsAvailable = stats != null,
                decoder = decoderMode,
                elapsedWithoutDisplayedFrameMs = now - lastDisplayedFrameElapsedMs,
                timelineAdvanceMs = (lastKnownPositionMs - lastDisplayedFramePositionMs)
                    .coerceAtLeast(0L)
            )
        )
        if (shouldRecover) {
            scheduleRecovery(
                reason = "El audio avanzó sin imagen; cambiando el vídeo de hardware a software"
            )
        }
    }

    /** Revela la superficie sólo después de que VLC confirme un cuadro nuevo. */
    private fun refreshVideoOutputReadiness() {
        if (mutableVideoOutputReady.value || videoLayout == null || !videoEnabled) return
        val baseline = displayedPicturesAtAttach ?: return
        val displayed = runCatching {
            mediaPlayer.media?.stats?.displayedPictures
        }.getOrNull() ?: return
        if (displayed > baseline) {
            mutableVideoOutputReady.value = true
        }
    }

    private fun resetVideoLivenessWatch(
        positionMs: Long,
        displayedPicturesBaseline: Int? = null
    ) {
        lastDisplayedFrameElapsedMs = SystemClock.elapsedRealtime()
        lastDisplayedFramePositionMs = positionMs.coerceAtLeast(0L)
        // Las estadísticas de VLC son acumuladas durante la vida del Media. Al reconectar
        // una vista no vuelven a cero: hacerlo aquí convertía cuadros viejos en evidencia de
        // una salida viva y dejaba la nueva superficie negra hasta el siguiente reinicio.
        lastDisplayedPictures = displayedPicturesBaseline
            ?: runCatching { mediaPlayer.media?.stats?.displayedPictures }.getOrNull()
            ?: 0
        voutCount = 0
    }

    /**
     * Reintento acotado: evita bucles infinitos y conserva el instante y la intención de play.
     * El primer fallo hardware degrada a software; el segundo limpia y reabre el mismo modo.
     */
    private fun scheduleRecovery(reason: String): Boolean {
        val decision = PlaybackRecoveryPolicy.decide(decoderMode, recoveryCount)
        if (recoveryScheduled || playlist.isEmpty() || !decision.shouldRetry) {
            return false
        }
        val position = mediaPlayer.time.takeIf { it >= 0L } ?: lastKnownPositionMs
        val resumePlayback = wantsToPlay
        if (decoderMode == DecoderMode.HARDWARE && decision.decoder == DecoderMode.SOFTWARE) {
            activeMediaKey?.let(sessionSoftwareMediaKeys::add)
        }
        decoderMode = decision.decoder
        recoveryCount++
        recovering = true
        recoveryScheduled = true
        pendingError = null
        vlcPlaybackState = Player.STATE_BUFFERING
        updateDiagnostics(PlaybackHealth.RECOVERING, reason)
        invalidateState()

        handler.post {
            openCurrent(position.coerceAtLeast(0L), resumePlayback, isRecovery = true)
            // Si VLC ni siquiera emite Opening, permitimos que el watchdog o el error
            // definitivo vuelvan a tomar una decisión en vez de quedar bloqueados.
            handler.postDelayed({ recoveryScheduled = false }, RECOVERY_OPEN_TIMEOUT_MS)
        }
        return true
    }

    private fun signalPlaybackFailure() {
        recovering = false
        recoveryScheduled = false
        pendingError = PlaybackException(
            "libVLC no pudo reproducir este medio tras $recoveryCount intentos de recuperación",
            null,
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )
        vlcPlaybackState = Player.STATE_IDLE
        wantsToPlay = false
        updateDiagnostics(PlaybackHealth.ERROR, "La recuperación automática no funcionó")
    }

    private fun updateDiagnostics(health: PlaybackHealth, recovery: String? = null) {
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            source = activePlan.source,
            decoder = decoderMode,
            health = health,
            cacheMs = activePlan.cacheMs,
            recoveryCount = recoveryCount,
            lastRecovery = recovery ?: mutableDiagnostics.value.lastRecovery
        )
    }

    /** Lee estadísticas nativas con una frecuencia limitada para no cargar Compose ni VLC. */
    private fun publishRuntimeDiagnostics(
        force: Boolean = false,
        health: PlaybackHealth? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDiagnosticsElapsedMs < DIAGNOSTICS_INTERVAL_MS) return
        lastDiagnosticsElapsedMs = now
        val track = runCatching { mediaPlayer.currentVideoTrack }.getOrNull()
        val stats = runCatching { mediaPlayer.media?.stats }.getOrNull()
        val fps = track?.let {
            if (it.frameRateNum > 0 && it.frameRateDen > 0) {
                it.frameRateNum.toFloat() / it.frameRateDen
            } else {
                0f
            }
        } ?: 0f
        val current = mutableDiagnostics.value
        mutableDiagnostics.value = current.copy(
            source = activePlan.source,
            decoder = decoderMode,
            health = health ?: current.health,
            cacheMs = activePlan.cacheMs,
            recoveryCount = recoveryCount,
            codec = track?.codec?.takeIf(String::isNotBlank)?.uppercase(Locale.ROOT),
            width = track?.width ?: 0,
            height = track?.height ?: 0,
            framesPerSecond = fps,
            inputBitrateKbps = stats?.inputBitrate
                ?.let { (it * 8_000f).toInt().coerceAtLeast(0) }
                ?: 0,
            decodedFrames = stats?.decodedVideo ?: 0,
            displayedFrames = stats?.displayedPictures ?: 0,
            droppedFrames = stats?.lostPictures ?: 0,
            corruptedPackets = stats?.demuxCorrupted ?: 0
        )
    }

    // ---------------------------------------------------------- estado Media3

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(vlcPlaybackState)
            .setPlayWhenReady(wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(buildPlaylist())
            .setCurrentMediaItemIndex(currentIndex.coerceAtLeast(0))
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVolume(currentVolume)
            .setVideoSize(videoSize)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setPlayerError(pendingError)

        val position = lastKnownPositionMs
        builder.setContentPositionMs(
            if (wantsToPlay && vlcPlaybackState == Player.STATE_READY) {
                PositionSupplier.getExtrapolating(position, speed)
            } else {
                PositionSupplier.getConstant(position)
            }
        )

        val buffered = durationMs.takeIf { it != C.TIME_UNSET }
            ?.let { (it * bufferedPercent).toLong() }
            ?: position
        builder.setContentBufferedPositionMs(PositionSupplier.getConstant(buffered))

        return builder.build()
    }

    private fun buildPlaylist(): List<MediaItemData> = playlist.mapIndexed { index, item ->
        MediaItemData.Builder(item.mediaId.ifEmpty { "vortex-$index" })
            .setMediaItem(item)
            .setMediaMetadata(item.mediaMetadata)
            .setIsSeekable(true)
            .setIsDynamic(false)
            .setDurationUs(
                if (index == currentIndex && durationMs != C.TIME_UNSET) {
                    durationMs * 1000L
                } else {
                    C.TIME_UNSET
                }
            )
            .build()
    }

    // --------------------------------------------------------------- comandos

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        // Una sustitución real sí cambia el medio nativo. Detenerlo antes de liberar su
        // descriptor evita carreras entre el decodificador de vídeo y la nueva apertura.
        if (mediaPlayer.media != null) {
            ignoreNextStoppedEvent = true
            mediaPlayer.stop()
        }
        playlist = mediaItems
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        rebuildOrder()
        val start = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        openCurrent(start, play = wantsToPlay)
        return Futures.immediateVoidFuture()
    }

    /**
     * Actualiza la línea de tiempo de Media3 sin tocar el medio abierto por VLC.
     * Devuelve false cuando el elemento activo cambió y el servicio debe usar la ruta
     * normal de apertura desde cero.
     */
    internal fun replacePlaylistPreservingCurrent(
        mediaItems: List<MediaItem>,
        updatedCurrentIndex: Int
    ): Boolean {
        if (mediaPlayer.media == null || mediaItems.isEmpty()) return false
        val index = updatedCurrentIndex.coerceIn(mediaItems.indices)
        val preservesCurrent = PlaybackQueueEditor.preservesCurrent(
            entries = playlist,
            currentIndex = currentIndex,
            updatedEntries = mediaItems,
            updatedCurrentIndex = index,
            sameIdentity = { before, after -> mediaIdentity(before) == mediaIdentity(after) }
        )
        if (!preservesCurrent) return false

        playlist = mediaItems
        currentIndex = index
        activeMediaKey = mediaIdentity(mediaItems[index])
        rebuildOrder()
        invalidateState()
        return true
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if ((mediaPlayer.media == null || vlcPlaybackState == Player.STATE_IDLE) && playlist.isNotEmpty()) {
            openCurrent(lastKnownPositionMs, play = wantsToPlay)
        }
        pendingError = null
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsToPlay = playWhenReady
        if (playWhenReady) {
            if (mediaPlayer.media == null || vlcPlaybackState == Player.STATE_IDLE) {
                openCurrent(lastKnownPositionMs, play = true)
            }
            else mediaPlayer.play()
        } else {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs
        // Con el aleatorio activo, "siguiente" y "anterior" tienen que seguir el orden
        // barajado; el índice que llega aquí lo ha calculado `SimpleBasePlayer` sobre la
        // lista tal cual, que es justo lo que no queremos.
        val index = if (!shuffle) {
            mediaItemIndex
        } else when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> stepInOrder(1) ?: mediaItemIndex
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> stepInOrder(-1) ?: mediaItemIndex
            else -> mediaItemIndex
        }
        if (index != currentIndex && index in playlist.indices) {
            currentIndex = index
            openCurrent(target, play = wantsToPlay)
        } else {
            seekToVlc(target)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        mediaPlayer.stop()
        wantsToPlay = false
        vlcPlaybackState = Player.STATE_IDLE
        updateDiagnostics(health = PlaybackHealth.IDLE)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        released = true
        handler.removeCallbacks(stallWatchdog)
        detachVideoOutput()
        mediaPlayer.setEventListener(null)
        mediaPlayer.stop()
        mediaPlayer.media?.release()
        mediaPlayer.release()
        libVlc.release()
        closeFd()
        return Futures.immediateVoidFuture()
    }

    /** Aplica la cadena de tono dentro de libVLC, sin depender de una sesión de audio Android. */
    fun applyAudioSettings(settings: AudioSettings) {
        audioSettings = settings
        runCatching {
            if (!settings.enabled || settings.bypassOn) {
                boostVolumePercent = NORMAL_VLC_VOLUME
                applyVlcVolume()
                mediaPlayer.setEqualizer(null)
                return
            }
            val frequencies = List(MediaPlayer.Equalizer.getBandCount()) { index ->
                MediaPlayer.Equalizer.getBandFrequency(index)
            }
            val plan = VlcEqualizerPlanner.build(settings, frequencies)
            boostVolumePercent = plan.volumePercent
            applyVlcVolume()
            val equalizer = MediaPlayer.Equalizer.create()
            check(equalizer.setPreAmp(plan.preampDb)) { "VLC rechazó el preamplificador" }
            plan.bandGainsDb.forEachIndexed { index, gain ->
                check(equalizer.setAmp(index, gain)) { "VLC rechazó la banda $index" }
            }
            check(mediaPlayer.setEqualizer(equalizer)) { "VLC rechazó el ecualizador" }
        }.onFailure { Log.w(TAG, "No se pudo aplicar el ecualizador nativo", it) }
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters
    ): ListenableFuture<*> {
        speed = playbackParameters.speed
        mediaPlayer.rate = speed
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        currentVolume = volume.coerceIn(0f, 1f)
        applyVlcVolume()
        return Futures.immediateVoidFuture()
    }

    /** Mantiene el volumen Media3 y el boost VLC como dos controles independientes. */
    private fun applyVlcVolume() {
        val target = (currentVolume * boostVolumePercent).toInt()
            .coerceIn(0, MAX_VLC_VOLUME)
        val result = mediaPlayer.setVolume(target)
        if (result == 0) {
            appliedVlcVolumePercent = target
        } else {
            Log.w(TAG, "VLC rechazó el volumen software $target %")
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffle = shuffleModeEnabled
        rebuildOrder()
        return Futures.immediateVoidFuture()
    }

    /**
     * Rehace el orden de recorrido. Al barajar, el medio que suena queda el primero para
     * que activar el aleatorio no corte la pista a medias.
     */
    private fun rebuildOrder(currentFirst: Boolean = true) {
        order = when {
            playlist.isEmpty() -> emptyList()
            !shuffle -> playlist.indices.toList()
            currentFirst -> listOf(currentIndex) +
                playlist.indices.filter { it != currentIndex }.shuffled()
            else -> playlist.indices.shuffled()
        }
    }

    /**
     * Índice del medio que toca [delta] pasos más allá en el orden vigente, o `null` si
     * no hay a dónde ir porque la cola se acabó y no se pidió bucle.
     */
    private fun stepInOrder(delta: Int): Int? {
        if (playlist.isEmpty()) return null
        if (order.size != playlist.size) rebuildOrder()
        val position = order.indexOf(currentIndex)
        if (position < 0) return null

        val target = position + delta
        if (target in order.indices) return order[target]
        if (repeat != Player.REPEAT_MODE_ALL) return null

        // Al dar la vuelta se baraja de nuevo: conservar el orden convertiría el modo
        // aleatorio en un bucle fijo a partir de la segunda pasada.
        if (shuffle) rebuildOrder(currentFirst = false)
        return if (delta > 0) order.first() else order.last()
    }

    private fun seekToVlc(positionMs: Long) {
        lastKnownPositionMs = positionMs
        resetVideoLivenessWatch(positionMs)
        if (vlcPlaybackState == Player.STATE_BUFFERING && !mediaPlayer.isPlaying) {
            // Si todavía está abriendo, el seek se aplicará al recibir Playing. Mandarlo
            // ahora puede ser ignorado y dejar después el target anterior pendiente.
            seekAfterPlayingMs = positionMs.takeIf { it > 0L }
        } else {
            seekAfterPlayingMs = null
            mediaPlayer.setTime(positionMs, false)
        }
    }

    /**
     * Abre el medio actual. Para `content://` y `file://` pasamos un descriptor de fichero:
     * libVLC no resuelve los proveedores de contenido de Android por sí solo.
     */
    private fun openCurrent(startPositionMs: Long, play: Boolean, isRecovery: Boolean = false) {
        val item = playlist.getOrNull(currentIndex) ?: return
        val uri = item.localConfiguration?.uri ?: return
        mutableVideoOutputReady.value = false
        val startPlan = PlaybackStartPolicy.plan(startPositionMs)
        val mediaKey = mediaIdentity(item)
        val reopeningSameMedia = mediaKey == activeMediaKey
        if (!reopeningSameMedia) {
            activeMediaKey = mediaKey
            requestedAudioTrack = null
            requestedSubtitleTrack = -1
            subtitleSelectionExplicit = false
            requestedSubtitleDelayMs = 0L
            externalSubtitleUris.clear()
            restoreExternalSubtitlesOnPlaying = false
        }
        activePlan = PlaybackIntelligencePlanner.plan(
            uri = uri.toString(),
            mimeType = item.localConfiguration?.mimeType,
            lowRamDevice = lowRamDevice
        )
        if (!isRecovery) {
            decoderMode = if (mediaKey in sessionSoftwareMediaKeys) {
                DecoderMode.SOFTWARE
            } else {
                activePlan.decoder
            }
            recoveryCount = 0
            recovering = false
            recoveryScheduled = false
            mutableDiagnostics.value = PlaybackDiagnostics(
                source = activePlan.source,
                decoder = decoderMode,
                health = PlaybackHealth.OPENING,
                cacheMs = activePlan.cacheMs
            )
        }

        mediaPlayer.media?.release()
        closeFd()

        val media = try {
            when (uri.scheme) {
                "content", "file" -> {
                    val fd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: return signalOpenFailure(uri)
                    openFd = fd
                    Media(libVlc, fd.fileDescriptor)
                }
                else -> Media(libVlc, uri)
            }
        } catch (e: Exception) {
            return signalOpenFailure(uri, e)
        }

        media.setHWDecoderEnabled(decoderMode == DecoderMode.HARDWARE, false)
        activePlan.mediaOptions.forEach(media::addOption)
        if (!videoEnabled) media.addOption(":no-video")

        mediaPlayer.media = media
        displayedPicturesAtAttach = if (videoLayout != null && videoEnabled) 0 else null
        media.release()
        restoreExternalSubtitlesOnPlaying = reopeningSameMedia && externalSubtitleUris.isNotEmpty()

        seekAfterPlayingMs = startPlan.seekAfterPlayingMs
        lastKnownPositionMs = startPlan.seekAfterPlayingMs ?: startPlan.mediaStartPositionMs
        lastProgressElapsedMs = SystemClock.elapsedRealtime()
        resetVideoLivenessWatch(lastKnownPositionMs)
        lastDiagnosticsElapsedMs = 0L
        durationMs = C.TIME_UNSET
        pendingError = null
        vlcPlaybackState = Player.STATE_BUFFERING

        mediaPlayer.rate = speed
        applyAudioSettings(audioSettings)
        mediaPlayer.setVideoTrackEnabled(videoEnabled)
        if (play) mediaPlayer.play()
        invalidateState()
    }

    private fun signalOpenFailure(uri: Uri, cause: Throwable? = null) {
        recovering = false
        recoveryScheduled = false
        pendingError = PlaybackException(
            "No se pudo abrir $uri",
            cause,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        vlcPlaybackState = Player.STATE_IDLE
        wantsToPlay = false
        updateDiagnostics(PlaybackHealth.ERROR, "No se pudo abrir la fuente")
        invalidateState()
    }

    private fun closeFd() {
        runCatching { openFd?.close() }
        openFd = null
    }

    private fun mediaIdentity(item: MediaItem): String =
        item.mediaId.takeIf(String::isNotBlank)
            ?: item.localConfiguration?.uri?.toString().orEmpty()

    // ------------------------------------------------------- EngineControls

    override fun retryInSafeMode() {
        if (playlist.isEmpty()) return
        activeMediaKey?.let(sessionSoftwareMediaKeys::add)
        decoderMode = DecoderMode.SOFTWARE
        recoveryCount = 0
        recovering = true
        recoveryScheduled = false
        wantsToPlay = true
        pendingError = null
        updateDiagnostics(PlaybackHealth.RECOVERING, "Reintento manual en modo software")
        openCurrent(lastKnownPositionMs, play = true, isRecovery = true)
    }

    override val audioTracks: List<TrackOption>
        get() = mediaPlayer.audioTracks.orEmpty().map { track ->
            TrackOption(
                id = track.id.toString(),
                label = track.name ?: "Pista ${track.id}",
                selected = track.id == mediaPlayer.audioTrack
            )
        }

    override val subtitleTracks: List<TrackOption>
        get() = mediaPlayer.spuTracks.orEmpty()
            // VLC expone "Desactivar" como pista con id -1; la UI ya tiene su propia opción.
            .filter { it.id >= 0 }
            .map { track ->
                TrackOption(
                    id = track.id.toString(),
                    label = track.name ?: "Subtítulo ${track.id}",
                    selected = track.id == mediaPlayer.spuTrack
                )
            }

    override fun selectAudioTrack(id: String) {
        id.toIntOrNull()?.let {
            requestedAudioTrack = it
            mediaPlayer.audioTrack = it
        }
    }

    override fun selectSubtitleTrack(id: String?) {
        requestedSubtitleTrack = id?.toIntOrNull() ?: -1
        subtitleSelectionExplicit = true
        mediaPlayer.spuTrack = requestedSubtitleTrack
    }

    override val subtitleDelayMs: Long
        get() = requestedSubtitleDelayMs

    override fun setSubtitleDelayMs(delayMs: Long) {
        val bounded = delayMs.coerceIn(-60_000L, 60_000L)
        requestedSubtitleDelayMs = bounded
        if (!mediaPlayer.setSpuDelay(bounded * 1_000L)) {
            Log.w(TAG, "VLC rechazó el retardo de subtítulos $bounded ms")
        }
    }

    override val isVideoEnabled: Boolean get() = videoEnabled

    override fun setVideoEnabled(enabled: Boolean) {
        if (videoEnabled == enabled) return
        videoEnabled = enabled
        mediaPlayer.setVideoTrackEnabled(enabled)
        if (!enabled) {
            detachVideoOutput()
        }
        invalidateState()
    }

    override fun attachVideoOutput(container: FrameLayout) {
        if (!videoEnabled) return
        detachVideoOutput()
        // VLCVideoLayout necesita el contexto de la Activity para resolver el tamaño real de
        // la ventana. Con el contexto del servicio, VideoHelper no puede centrar ni escalar.
        val layout = VLCVideoLayout(container.context).also { videoLayout = it }
        container.addView(
            layout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        mediaPlayer.attachViews(layout, null, true, false)
        applyVideoScale()
        val generation = ++videoAttachGeneration
        val attachedAtMs = SystemClock.elapsedRealtime()
        val positionAtAttachMs = lastKnownPositionMs
        val displayedAtAttach = runCatching {
            mediaPlayer.media?.stats?.displayedPictures
        }.getOrNull()
        displayedPicturesAtAttach = displayedAtAttach
        mutableVideoOutputReady.value = false
        resetVideoLivenessWatch(lastKnownPositionMs, displayedAtAttach)

        // Algunos decodificadores hardware no redirigen sus buffers al Surface nuevo al
        // volver desde biblioteca. Damos tiempo a una salida normal y, sólo si el reloj
        // avanza sin entregar un cuadro nuevo, reabrimos el mismo medio en el mismo punto.
        handler.postDelayed({
            if (released || generation != videoAttachGeneration || videoLayout !== layout) {
                return@postDelayed
            }
            val displayedNow = runCatching {
                mediaPlayer.media?.stats?.displayedPictures
            }.getOrNull()
            val hasVideoTrack = runCatching {
                mediaPlayer.videoTracksCount > 0 || mediaPlayer.currentVideoTrack != null || voutCount > 0
            }.getOrDefault(false)
            val shouldReopen = SurfaceReattachPolicy.shouldReopen(
                SurfaceReattachSample(
                    playbackActive = wantsToPlay &&
                        vlcPlaybackState == Player.STATE_READY &&
                        !recoveryScheduled,
                    outputAttached = videoLayout === layout,
                    hasVideoTrack = hasVideoTrack,
                    elapsedSinceAttachMs = SystemClock.elapsedRealtime() - attachedAtMs,
                    timelineAdvanceMs = (lastKnownPositionMs - positionAtAttachMs).coerceAtLeast(0L),
                    displayedFramesAtAttach = displayedAtAttach,
                    displayedFramesNow = displayedNow
                )
            )
            if (shouldReopen) {
                val position = mediaPlayer.time.takeIf { it >= 0L } ?: lastKnownPositionMs
                recovering = true
                updateDiagnostics(
                    PlaybackHealth.RECOVERING,
                    "Reconectando la imagen al volver al reproductor"
                )
                openCurrent(position.coerceAtLeast(0L), play = true, isRecovery = true)
            }
        }, SurfaceReattachPolicy.FRAME_TIMEOUT_MS)
    }

    override fun detachVideoOutput() {
        videoAttachGeneration++
        displayedPicturesAtAttach = null
        mutableVideoOutputReady.value = false
        if (videoLayout == null) return
        mediaPlayer.detachViews()
        (videoLayout?.parent as? ViewGroup)?.removeView(videoLayout)
        videoLayout = null
    }

    override fun setVideoScale(mode: VideoScaleMode) {
        if (videoScaleMode == mode) return
        videoScaleMode = mode
        applyVideoScale()
    }

    private fun applyVideoScale() {
        if (videoLayout == null) return
        runCatching {
            mediaPlayer.setVideoScale(videoScaleMode.toVlcScaleType())
        }.onFailure { error ->
            Log.w(TAG, "VLC no pudo aplicar la escala $videoScaleMode", error)
        }
    }

    override fun addExternalSubtitle(uri: String) {
        val added = mediaPlayer.addSlave(
            org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
            Uri.parse(uri),
            true
        )
        if (added) {
            externalSubtitleUris += uri
            subtitleSelectionExplicit = false
        } else {
            Log.w(TAG, "VLC rechazó el subtítulo externo $uri")
        }
    }

    private companion object {
        const val TAG = "VlcPlayer"
        const val NORMAL_VLC_VOLUME = 100
        const val MAX_VLC_VOLUME = 200
        const val WATCHDOG_INTERVAL_MS = 4_000L
        const val STALL_TIMEOUT_MS = 16_000L
        const val VIDEO_OUTPUT_FALLBACK_MS = 1_200L
        const val RECOVERY_OPEN_TIMEOUT_MS = 3_000L
        const val DIAGNOSTICS_INTERVAL_MS = 1_000L
        const val SEEK_RESET_THRESHOLD_MS = 1_000L

        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_RELEASE
            )
            .build()
    }
}
