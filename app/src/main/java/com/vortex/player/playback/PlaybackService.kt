package com.vortex.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vortex.player.MainActivity
import com.vortex.player.audio.AudioEnhancer
import com.vortex.player.audio.AudioPreferences
import com.vortex.player.audio.AudioSettings
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio de reproducción con doble motor.
 *
 * Media3 lleva la voz cantante porque es más eficiente y se integra con la sesión
 * multimedia sin fricción. Cuando falla por un códec o un contenedor que no entiende,
 * el servicio rescata la posición exacta y levanta libVLC en su lugar: para el usuario
 * el vídeo sólo parpadea un instante y sigue, sin diálogos de error.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var exoControls: ExoEngineControls
    private var vlcPlayer: VlcPlayer? = null
    private var mediaSession: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: MediaRepository

    private val enhancer = AudioEnhancer()
    private var audioSettings = AudioSettings()

    /**
     * Sesión de audio propia, generada por nosotros e impuesta a ExoPlayer.
     *
     * Se hace así en vez de esperar a que el reproductor anuncie la suya porque ese aviso
     * no llega de forma fiable, y sin identificador de sesión no hay dónde enganchar los
     * efectos: el ecualizador se quedaba sin aplicar y la pantalla de ajustes no mostraba
     * ninguna capacidad aunque hubiera música sonando.
     */
    private val audioSessionId: Int by lazy {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        manager.generateAudioSessionId()
    }

    private var usingVlc = false
    private var audioOnly = false
    private var sleepRunnable: Runnable? = null

    /** Evita rebotar entre motores si VLC también falla con el mismo medio. */
    private var fallbackAttemptedFor: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository.get(this)
        exoPlayer = buildExoPlayer()
        exoControls = ExoEngineControls(this, exoPlayer)
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityIntent())
            .build()
            // Sin este registro explícito, Media3 nunca publica la notificación multimedia
            // y el servicio jamás pasa a primer plano: `onGetSession` sólo se invoca cuando
            // se conecta un MediaController, y aquí la interfaz usa el Player directamente
            // a través de PlaybackHub. El resultado era que el sistema mataba la
            // reproducción con «Stopping service due to app idle» a los dos minutos.
            .also { addSession(it) }
        PlaybackHub.setPlayer(exoPlayer, exoControls)
        instance = this
        startPositionPersistence()
        startAudioEnhancer()
    }

    /**
     * Mantiene los efectos de audio enganchados a la sesión de ExoPlayer.
     *
     * La sesión cambia cuando el reproductor recrea su salida, así que hay que reengancharse
     * cada vez; si no, los ajustes dejan de tener efecto en la segunda canción sin que nada
     * lo indique.
     */
    private fun startAudioEnhancer() {
        refreshAudioSession(audioSessionId)
        scope.launch {
            AudioPreferences.observe(this@PlaybackService).collect { settings ->
                audioSettings = settings
                enhancer.apply(settings)
            }
        }
    }

    private fun refreshAudioSession(sessionId: Int) {
        val capabilities = enhancer.attach(sessionId)
        PlaybackHub.setAudioCapabilities(capabilities)
        enhancer.apply(audioSettings)
    }

    private fun buildExoPlayer(): ExoPlayer =
        ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    // Si el decodificador por hardware falla, se prueba el de software
                    // antes de rendirse y saltar a VLC.
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true)
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // Sin esto, el modo solo-audio se corta al apagar la pantalla.
                setWakeMode(C.WAKE_MODE_NETWORK)
                audioSessionId = this@PlaybackService.audioSessionId
                addListener(playerListener)
            }

    private fun sessionActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            pendingRequest?.let { request ->
                pendingRequest = null
                startRequest(request)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ------------------------------------------------------------ arranque

    private fun startRequest(request: PlayRequest) {
        PlaybackHub.setQueue(request.entries, request.startIndex)
        fallbackAttemptedFor = null
        audioOnly = request.audioOnly
        PlaybackHub.setAudioOnly(audioOnly)

        // Todo empieza en Media3; VLC sólo entra si Media3 se atraganta.
        if (usingVlc) switchBackToExo()

        val items = request.entries.map { it.toMediaItem() }
        exoPlayer.setMediaItems(items, request.startIndex, request.positionMs)
        exoControls.setVideoEnabled(!audioOnly)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // ------------------------------------------------------- cambio de motor

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val currentId = currentPlayer.currentMediaItem?.mediaId
            if (usingVlc || !error.isEngineFallbackWorthy() || currentId == null) return
            if (fallbackAttemptedFor == currentId) return
            fallbackAttemptedFor = currentId
            switchToVlc()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackHub.setCurrentIndex(currentPlayer.currentMediaItemIndex)
            // Cada medio merece su propia oportunidad con Media3.
            fallbackAttemptedFor = null
            if (usingVlc) switchBackToExo()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            refreshAudioSession(audioSessionId)
        }
    }

    private fun switchToVlc() {
        val items = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        val index = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)

        exoPlayer.stop()
        exoControls.detachVideoOutput()

        val vlc = VlcPlayer(this).also { vlcPlayer = it }
        vlc.addListener(vlcListener)
        vlc.setMediaItems(items, index, position)
        vlc.setVideoEnabled(!audioOnly)
        vlc.prepare()
        vlc.playWhenReady = true

        usingVlc = true
        mediaSession?.player = vlc
        PlaybackHub.setPlayer(vlc, vlc)
        // libVLC no expone sesión de audio: los efectos del sistema no le llegan.
        enhancer.release()
        PlaybackHub.setAudioCapabilities(null)
    }

    private fun switchBackToExo() {
        val vlc = vlcPlayer ?: return
        vlc.removeListener(vlcListener)
        vlc.release()
        vlcPlayer = null
        usingVlc = false
        mediaSession?.player = exoPlayer
        PlaybackHub.setPlayer(exoPlayer, exoControls)
        refreshAudioSession(audioSessionId)
    }

    private val vlcListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackHub.setCurrentIndex(currentPlayer.currentMediaItemIndex)
        }
    }

    private val currentPlayer: Player
        get() = vlcPlayer ?: exoPlayer

    private fun PlaybackException.isEngineFallbackWorthy(): Boolean = errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    )

    // ------------------------------------------------------------ funciones

    /** Conmuta solo-audio manteniendo intacto el motor y la posición actual. */
    fun setAudioOnly(enabled: Boolean) {
        audioOnly = enabled
        PlaybackHub.controls.value?.setVideoEnabled(!enabled)
        PlaybackHub.setAudioOnly(enabled)
        PlaybackHub.currentEntry.value?.let {
            repository.savePreferences(it.uri.toString(), audioOnly = enabled)
        }
    }

    /** Temporizador de apagado; `null` lo cancela. */
    fun setSleepTimer(durationMs: Long?) {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        if (durationMs == null) {
            PlaybackHub.setSleepAt(null)
            return
        }
        val runnable = Runnable {
            currentPlayer.pause()
            PlaybackHub.setSleepAt(null)
        }
        sleepRunnable = runnable
        handler.postDelayed(runnable, durationMs)
        PlaybackHub.setSleepAt(System.currentTimeMillis() + durationMs)
    }

    /**
     * Guarda la posición cada pocos segundos. Es lo que hace posible "continuar viendo"
     * incluso si la app muere de golpe: nunca se pierden más de 4 s de progreso.
     */
    private fun startPositionPersistence() {
        scope.launch {
            while (true) {
                delay(4_000)
                val player = currentPlayer
                val entry = PlaybackHub.currentEntry.value
                if (entry != null && player.isPlaying && player.duration > 0) {
                    repository.savePosition(
                        entry.uri.toString(),
                        player.currentPosition,
                        player.duration
                    )
                }
            }
        }
    }

    private fun persistNow() {
        val player = currentPlayer
        val entry = PlaybackHub.currentEntry.value ?: return
        if (player.duration > 0) {
            repository.savePosition(entry.uri.toString(), player.currentPosition, player.duration)
        }
    }

    // ----------------------------------------------------------- ciclo vida

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Cerrar la app desde recientes no debe cortar el audio: es justo el caso
        // de uso de "escuchar un vídeo como si fuera un podcast".
        if (!currentPlayer.isPlaying) {
            persistNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistNow()
        sleepRunnable?.let { handler.removeCallbacks(it) }
        enhancer.release()
        PlaybackHub.setAudioCapabilities(null)
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        vlcPlayer?.release()
        vlcPlayer = null
        PlaybackHub.setPlayer(null, null)
        instance = null
        super.onDestroy()
    }

    // --------------------------------------------------------------- API

    private data class PlayRequest(
        val entries: List<MediaEntry>,
        val startIndex: Int,
        val positionMs: Long,
        val audioOnly: Boolean
    )

    companion object {
        private const val ACTION_PLAY = "com.vortex.player.action.PLAY"

        /**
         * La cola se pasa por un campo estático en vez de por el Intent: son listas de
         * cientos de elementos y superarían el límite de una transacción Binder.
         * Vale porque servicio y UI comparten proceso.
         */
        @Volatile
        private var pendingRequest: PlayRequest? = null

        /**
         * Referencia al servicio vivo. La UI la usa para lo que no cabe en la interfaz
         * `Player` (solo-audio, temporizador). Mismo proceso, así que no hay IPC de por medio.
         */
        @Volatile
        private var instance: PlaybackService? = null

        fun setAudioOnly(enabled: Boolean) {
            instance?.setAudioOnly(enabled)
        }

        fun setSleepTimer(durationMs: Long?) {
            instance?.setSleepTimer(durationMs)
        }

        fun play(
            context: Context,
            entries: List<MediaEntry>,
            startIndex: Int,
            positionMs: Long = C.TIME_UNSET,
            audioOnly: Boolean = false
        ) {
            if (entries.isEmpty()) return
            pendingRequest = PlayRequest(entries, startIndex.coerceIn(entries.indices), positionMs, audioOnly)
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY)
            context.startService(intent)
        }
    }
}

/** Metadatos que alimentan la notificación y la pantalla de bloqueo. */
internal fun MediaEntry.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri.toString())
        .setMimeType(mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { displayName })
                .setArtist(artist ?: folderName)
                .setAlbumTitle(album)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()
