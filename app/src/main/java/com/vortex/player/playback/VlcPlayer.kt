package com.vortex.player.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

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
            "--rtsp-tcp",
            "--http-reconnect",
            "--network-caching=1500",
            "--file-caching=300",
            // Permite cambiar la velocidad sin que las voces suenen a helio.
            "--audio-time-stretch"
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
    private var videoSize: VideoSize = VideoSize.UNKNOWN
    private var pendingError: PlaybackException? = null
    private var repeat: Int = Player.REPEAT_MODE_OFF
    private var shuffle: Boolean = false

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

    override val engineName: String = "VLC"

    init {
        mediaPlayer.setEventListener { event -> handler.post { onVlcEvent(event) } }
    }

    // ---------------------------------------------------------------- eventos

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> vlcPlaybackState = Player.STATE_BUFFERING

            MediaPlayer.Event.Buffering -> {
                bufferedPercent = event.buffering / 100f
                // VLC reporta 100 % de buffer también mientras reproduce; sólo el
                // buffer parcial significa realmente "esperando datos".
                vlcPlaybackState = if (event.buffering < 100f) {
                    Player.STATE_BUFFERING
                } else {
                    Player.STATE_READY
                }
            }

            MediaPlayer.Event.Playing -> {
                vlcPlaybackState = Player.STATE_READY
                wantsToPlay = true
                refreshDuration()
            }

            MediaPlayer.Event.Paused -> {
                vlcPlaybackState = Player.STATE_READY
                wantsToPlay = false
                lastKnownPositionMs = mediaPlayer.time.coerceAtLeast(0L)
            }

            MediaPlayer.Event.Stopped -> {
                vlcPlaybackState = Player.STATE_IDLE
                wantsToPlay = false
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
                }
            }

            MediaPlayer.Event.EncounteredError -> {
                pendingError = PlaybackException(
                    "libVLC no pudo reproducir este medio",
                    null,
                    PlaybackException.ERROR_CODE_DECODING_FAILED
                )
                vlcPlaybackState = Player.STATE_IDLE
                wantsToPlay = false
            }

            MediaPlayer.Event.TimeChanged -> lastKnownPositionMs = event.timeChanged.coerceAtLeast(0L)

            MediaPlayer.Event.LengthChanged -> refreshDuration()

            MediaPlayer.Event.Vout -> {
                val vt = mediaPlayer.currentVideoTrack
                videoSize = if (vt != null && vt.width > 0) {
                    VideoSize(vt.width, vt.height)
                } else {
                    VideoSize.UNKNOWN
                }
            }
        }
        invalidateState()
    }

    private fun refreshDuration() {
        val length = mediaPlayer.length
        durationMs = if (length > 0) length else C.TIME_UNSET
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
        playlist = mediaItems
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        rebuildOrder()
        val start = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        openCurrent(start, play = wantsToPlay)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (mediaPlayer.media == null && playlist.isNotEmpty()) {
            openCurrent(lastKnownPositionMs, play = wantsToPlay)
        }
        pendingError = null
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsToPlay = playWhenReady
        if (playWhenReady) {
            if (mediaPlayer.media == null) openCurrent(lastKnownPositionMs, play = true)
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
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        detachVideoOutput()
        mediaPlayer.setEventListener(null)
        mediaPlayer.stop()
        mediaPlayer.media?.release()
        mediaPlayer.release()
        libVlc.release()
        closeFd()
        return Futures.immediateVoidFuture()
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
        mediaPlayer.volume = (currentVolume * 100).toInt()
        return Futures.immediateVoidFuture()
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
        mediaPlayer.time = positionMs
    }

    /**
     * Abre el medio actual. Para `content://` y `file://` pasamos un descriptor de fichero:
     * libVLC no resuelve los proveedores de contenido de Android por sí solo.
     */
    private fun openCurrent(startPositionMs: Long, play: Boolean) {
        val item = playlist.getOrNull(currentIndex) ?: return
        val uri = item.localConfiguration?.uri ?: return

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

        media.setHWDecoderEnabled(true, false)
        if (startPositionMs > 0) {
            // VLC aplica :start-time en segundos con decimales.
            media.addOption(":start-time=${startPositionMs / 1000.0}")
        }
        if (!videoEnabled) media.addOption(":no-video")

        mediaPlayer.media = media
        media.release()

        lastKnownPositionMs = startPositionMs
        durationMs = C.TIME_UNSET
        pendingError = null
        vlcPlaybackState = Player.STATE_BUFFERING

        mediaPlayer.rate = speed
        mediaPlayer.setVideoTrackEnabled(videoEnabled)
        if (play) mediaPlayer.play()
        invalidateState()
    }

    private fun signalOpenFailure(uri: Uri, cause: Throwable? = null) {
        pendingError = PlaybackException(
            "No se pudo abrir $uri",
            cause,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        vlcPlaybackState = Player.STATE_IDLE
        invalidateState()
    }

    private fun closeFd() {
        runCatching { openFd?.close() }
        openFd = null
    }

    // ------------------------------------------------------- EngineControls

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
        id.toIntOrNull()?.let { mediaPlayer.audioTrack = it }
    }

    override fun selectSubtitleTrack(id: String?) {
        mediaPlayer.spuTrack = id?.toIntOrNull() ?: -1
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
        val layout = VLCVideoLayout(context).also { videoLayout = it }
        container.addView(
            layout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        mediaPlayer.attachViews(layout, null, true, false)
    }

    override fun detachVideoOutput() {
        if (videoLayout == null) return
        mediaPlayer.detachViews()
        (videoLayout?.parent as? ViewGroup)?.removeView(videoLayout)
        videoLayout = null
    }

    override fun addExternalSubtitle(uri: String) {
        mediaPlayer.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.parse(uri), true)
    }

    private companion object {
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
