package com.vortex.player.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vortex.player.MainActivity
import com.vortex.player.audio.AudioCapabilities
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio de reproducción con VLC como único motor multimedia.
 *
 * Media3 permanece únicamente como contrato de [Player] y [MediaSession] para que Android
 * conserve notificación, pantalla de bloqueo y controles externos. Todo el audio y vídeo
 * se abre, decodifica y reproduce en libVLC.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var vlcPlayer: VlcPlayer
    private var mediaSession: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: MediaRepository

    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vortex:VlcPlayback")
            .apply { setReferenceCounted(false) }
    }
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    vlcPlayer.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeOnFocusGain = false
                vlcPlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = vlcPlayer.playWhenReady
                hasAudioFocus = false
                vlcPlayer.pause()
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@lazy null
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusListener, handler)
            .build()
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnFocusGain = false
                vlcPlayer.pause()
            }
        }
    }

    private var audioOnly = false
    private var sleepRunnable: Runnable? = null
    private var order = PlaybackPrefs()

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository.get(this)
        vlcPlayer = VlcPlayer(this).apply { addListener(playerListener) }
        mediaSession = MediaSession.Builder(this, vlcPlayer)
            .setSessionActivity(sessionActivityIntent())
            .build()
            // Sin este registro explícito, Media3 nunca publica la notificación multimedia
            // y el servicio jamás pasa a primer plano: `onGetSession` sólo se invoca cuando
            // se conecta un MediaController, y aquí la interfaz usa el Player directamente
            // a través de PlaybackHub. El resultado era que el sistema mataba la
            // reproducción con «Stopping service due to app idle» a los dos minutos.
            .also { addSession(it) }
        PlaybackHub.setPlayer(vlcPlayer, vlcPlayer)
        // libVLC no publica un id de sesión Android al que enganchar los efectos de audio.
        PlaybackHub.setAudioCapabilities(AudioCapabilities())
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        instance = this
        startPositionPersistence()
        startOrderPreferences()
    }

    /**
     * Repetición y aleatorio, recuperados de la sesión anterior y volcados al motor.
     *
     * Se observan para que cualquier cambio de la interfaz llegue inmediatamente a VLC.
     */
    private fun startOrderPreferences() {
        scope.launch {
            PlaybackPreferences.observe(this@PlaybackService).collect { prefs ->
                order = prefs
                PlaybackHub.setRepeat(prefs.repeat)
                PlaybackHub.setShuffle(prefs.shuffle)
                applyOrder()
            }
        }
    }

    private fun applyOrder() {
        val player = vlcPlayer
        if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            player.repeatMode = order.repeat.playerMode
        }
        if (player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
            player.shuffleModeEnabled = order.shuffle
        }
    }

    /**
     * Cambia el orden de reproducción. Se aplica al instante y se guarda después: esperar
     * a que el disco conteste dejaría el botón sin responder durante un fotograma o dos.
     */
    private fun setOrder(prefs: PlaybackPrefs) {
        order = prefs
        PlaybackHub.setRepeat(prefs.repeat)
        PlaybackHub.setShuffle(prefs.shuffle)
        applyOrder()
        scope.launch { PlaybackPreferences.save(this@PlaybackService, prefs) }
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
        PlaybackHub.setQueue(request.entries, request.startIndex, request.positionMs)
        audioOnly = request.audioOnly
        PlaybackHub.setAudioOnly(audioOnly)

        val items = request.entries.map { it.toMediaItem() }
        vlcPlayer.setMediaItems(items, request.startIndex, request.positionMs)
        // Después de poner la cola: el orden aleatorio se calcula sobre los medios ya
        // cargados, así que hacerlo antes no barajaría nada.
        applyOrder()
        vlcPlayer.setVideoEnabled(!audioOnly)
        vlcPlayer.prepare()
        vlcPlayer.playWhenReady = true
    }

    // ---------------------------------------------------------- eventos VLC

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackHub.setCurrentIndex(vlcPlayer.currentMediaItemIndex)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                if (!requestAudioFocus()) vlcPlayer.pause()
            } else if (!resumeOnFocusGain) {
                abandonAudioFocus()
            }
        }

        // Es un servicio multimedia visible: el lock dura exactamente lo que VLC está
        // reproduciendo y también se libera de forma defensiva en onDestroy().
        @SuppressLint("Wakelock", "WakelockTimeout")
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (!wakeLock.isHeld) wakeLock.acquire()
            } else if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(requireNotNull(audioFocusRequest))
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(requireNotNull(audioFocusRequest))
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        hasAudioFocus = false
    }

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
            vlcPlayer.pause()
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
                val player = vlcPlayer
                val entry = PlaybackHub.currentEntry.value
                PlaybackHub.setPosition(player.currentPosition)
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
        val player = vlcPlayer
        // La posición se rescata siempre, aunque no haya duración todavía: es el último
        // instante en que se puede leer antes de soltar el motor, y de él depende que
        // reanudar continúe donde estaba en vez de empezar de cero.
        PlaybackHub.setPosition(player.currentPosition)
        val entry = PlaybackHub.currentEntry.value ?: return
        if (player.duration > 0) {
            repository.savePosition(entry.uri.toString(), player.currentPosition, player.duration)
        }
    }

    // ----------------------------------------------------------- ciclo vida

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Cerrar la app desde recientes no debe cortar el audio: es justo el caso
        // de uso de "escuchar un vídeo como si fuera un podcast".
        if (!vlcPlayer.isPlaying) {
            persistNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistNow()
        sleepRunnable?.let { handler.removeCallbacks(it) }
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonAudioFocus()
        if (wakeLock.isHeld) wakeLock.release()
        PlaybackHub.setAudioCapabilities(null)
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        vlcPlayer.removeListener(playerListener)
        vlcPlayer.release()
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

        /** Avanza el botón de repetición al siguiente estado de su ciclo. */
        fun cycleRepeat() {
            val service = instance ?: return
            service.setOrder(service.order.copy(repeat = service.order.repeat.next()))
        }

        fun toggleShuffle() {
            val service = instance ?: return
            service.setOrder(service.order.copy(shuffle = !service.order.shuffle))
        }

        /**
         * Reanuda lo que estuviera sonando, exista o no todavía el motor.
         *
         * En pausa, el servicio deja de estar en primer plano y Android acaba
         * llevándoselo por delante; al morir libera el `Player`. Como la cola y el medio
         * en curso viven en [PlaybackHub] y no en el servicio, la barra seguía pintada
         * pero `play()` no tenía a quién hablar: el botón quedaba muerto y la única
         * salida era buscar la canción a mano y empezarla de cero.
         *
         * Aquí, si el motor ya no está, se levanta de nuevo la misma cola en el mismo
         * punto. Para quien mira, el botón simplemente funciona.
         */
        fun resume(context: Context) {
            PlaybackHub.player.value?.let { player ->
                // `play()` sobre un motor parado no hace nada: si un error lo dejó en
                // reposo, hay que rearmarlo, y si la cola llegó al final hay que volver al
                // principio. En ambos casos el botón se quedaba igual de mudo.
                when (player.playbackState) {
                    Player.STATE_IDLE -> player.prepare()
                    Player.STATE_ENDED -> player.seekToDefaultPosition()
                    else -> Unit
                }
                player.play()
                return
            }
            val queue = PlaybackHub.queue.value
            if (queue.isEmpty()) return
            play(
                context = context,
                entries = queue,
                startIndex = PlaybackHub.currentIndex.value,
                positionMs = PlaybackHub.positionMs.value,
                audioOnly = PlaybackHub.audioOnly.value
            )
        }

        /** Único punto por el que pasan todos los botones de reproducir/pausar. */
        fun togglePlayPause(context: Context) {
            val player = PlaybackHub.player.value
            if (player != null && player.isPlaying) player.pause() else resume(context)
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
