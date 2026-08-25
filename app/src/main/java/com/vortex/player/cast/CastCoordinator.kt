package com.vortex.player.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.vortex.player.data.MediaEntry
import com.vortex.player.playback.PlaybackHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CastUiState(
    val sdkAvailable: Boolean = true,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val deviceName: String? = null,
    val mediaTitle: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val message: String? = null
)

/** Coordina handoff VLC → receptor Cast sin convertir Cast en un segundo motor local. */
object CastCoordinator {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var castContext: CastContext? = null
    private var observedClient: RemoteMediaClient? = null
    private var remoteContentToLocalIndex: Map<String, Int> = emptyMap()
    private var initialized = false

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishRemoteStatus()
        override fun onMetadataUpdated() = publishRemoteStatus()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _state.value = _state.value.copy(loading = true, message = "Conectando con la pantalla…")
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attach(session, shouldLoad = true)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _state.value = CastUiState(message = "No se pudo iniciar Cast · código $error")
        }

        override fun onSessionEnding(session: CastSession) {
            _state.value = _state.value.copy(loading = true)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            detach(resumePosition = true, message = null)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _state.value = _state.value.copy(loading = true, message = "Recuperando conexión Cast…")
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attach(session, shouldLoad = session.remoteMediaClient?.hasMediaSession() != true)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            detach(resumePosition = false, message = "No se pudo recuperar Cast · código $error")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _state.value = _state.value.copy(
                connected = false,
                loading = true,
                message = "Conexión Cast interrumpida; intentando recuperar…"
            )
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) {
            _state.value = CastUiState(
                sdkAvailable = false,
                message = "Google Cast requiere Google Play Services"
            )
            return
        }
        runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .onSuccess { value ->
                castContext = value
                value.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                value.sessionManager.currentCastSession?.let { session ->
                    attach(session, shouldLoad = false)
                }
            }
            .onFailure { error ->
                _state.value = CastUiState(
                    sdkAvailable = false,
                    message = "Cast no está disponible · ${error.message.orEmpty()}"
                )
            }
    }

    /** Desactiva sólo la interfaz Cast cuando MediaRouter no puede crear su selector. */
    fun reportUiFailure() {
        _state.value = _state.value.copy(
            sdkAvailable = false,
            message = "Cast no está disponible en este dispositivo"
        )
    }

    fun loadCurrent(autoplay: Boolean? = null) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        main.post { loadCurrent(session, autoplay) }
    }

    fun toggleRemotePlayback() {
        val client = observedClient ?: return
        if (client.isPlaying) client.pause() else client.play()
    }

    /** Detiene el medio remoto sin desconectar el teléfono del dispositivo Cast. */
    fun stopRemotePlayback() {
        observedClient?.stop()
        LocalCastServer.stop()
    }

    fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun openExpandedControls(context: Context) {
        val intent = Intent(context, VortexExpandedControlsActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun attach(session: CastSession, shouldLoad: Boolean) {
        val client = session.remoteMediaClient
        observedClient?.unregisterCallback(remoteCallback)
        observedClient = client
        client?.registerCallback(remoteCallback)
        _state.value = _state.value.copy(
            connected = true,
            loading = false,
            deviceName = session.castDevice?.friendlyName,
            message = null
        )
        if (shouldLoad) loadCurrent(session) else publishRemoteStatus()
    }

    private fun loadCurrent(session: CastSession, autoplayOverride: Boolean? = null) {
        val context = appContext ?: return
        val queue = PlaybackHub.queue.value
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(message = "No hay un medio para enviar")
            return
        }
        val currentIndex = PlaybackHub.currentIndex.value.coerceIn(queue.indices)
        val entry = queue.getOrNull(currentIndex) ?: run {
            _state.value = _state.value.copy(message = "No hay un medio para enviar")
            return
        }
        val localPlayer = PlaybackHub.player.value
        val position = localPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val planned = queue.mapIndexed { index, queued ->
            val duration = if (index == currentIndex) {
                localPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: queued.durationMs
            } else {
                queued.durationMs
            }
            Triple(
                index,
                queued,
                CastMediaPolicy.plan(
                    uri = queued.uri.toString(),
                    mimeType = queued.mimeType,
                    isVideo = queued.isVideo,
                    durationMs = duration
                )
            )
        }
        val currentPlan = planned.firstOrNull { it.first == currentIndex }?.third
        if (currentPlan == null || currentPlan.delivery == CastDelivery.UNSUPPORTED) {
            _state.value = _state.value.copy(loading = false, message = currentPlan?.reason)
            return
        }
        val compatible = planned.filter { it.third.delivery != CastDelivery.UNSUPPORTED }
        val omitted = planned.size - compatible.size
        _state.value = _state.value.copy(
            connected = true,
            loading = true,
            deviceName = session.castDevice?.friendlyName,
            mediaTitle = entry.title.ifBlank { entry.displayName },
            message = "Preparando envío…"
        )

        val local = compatible.filter { it.third.delivery == CastDelivery.LOCAL_BRIDGE }
        val localEndpoints = if (local.isNotEmpty()) {
            LocalCastServer.startQueue(
                context,
                local.map { (_, queued, plan) -> queued to plan.contentType }
            ).getOrElse { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    message = "No se pudo compartir la playlist · ${error.message.orEmpty()}"
                )
                return
            }.also {
                CastBridgeService.keepAlive(
                    context,
                    "${entry.title.ifBlank { entry.displayName }} · ${compatible.size} elementos"
                )
            }
        } else {
            CastBridgeService.stop(context)
            emptyMap()
        }

        val autoplay = autoplayOverride ?: (localPlayer?.playWhenReady == true)
        val contentIndex = linkedMapOf<String, Int>()
        val castItems = compatible.mapIndexed { castIndex, (localIndex, queued, plan) ->
            val mediaUrl = if (plan.delivery == CastDelivery.LOCAL_BRIDGE) {
                localEndpoints.getValue(queued.uri.toString())
            } else {
                queued.uri.toString()
            }
            contentIndex[mediaUrl] = localIndex
            val metadata = MediaMetadata(
                if (queued.isVideo) MediaMetadata.MEDIA_TYPE_MOVIE
                else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            ).apply {
                putString(MediaMetadata.KEY_TITLE, queued.title.ifBlank { queued.displayName })
                queued.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
                queued.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            }
            val duration = if (localIndex == currentIndex) {
                localPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: queued.durationMs
            } else queued.durationMs
            val info = MediaInfo.Builder(mediaUrl)
                .setContentType(plan.contentType)
                .setStreamType(
                    if (plan.streamKind == CastStreamKind.LIVE) MediaInfo.STREAM_TYPE_LIVE
                    else MediaInfo.STREAM_TYPE_BUFFERED
                )
                .setMetadata(metadata)
                .apply {
                    if (duration > 0L && plan.streamKind == CastStreamKind.BUFFERED) {
                        setStreamDuration(duration)
                    }
                }
                .build()
            MediaQueueItem.Builder(info)
                .setAutoplay(if (localIndex == currentIndex) autoplay else true)
                .setPreloadTime(if (castIndex < compatible.lastIndex) 10.0 else 0.0)
                .build()
        }.toTypedArray()
        val castStartIndex = compatible.indexOfFirst { it.first == currentIndex }.coerceAtLeast(0)
        val client = session.remoteMediaClient ?: run {
            CastBridgeService.stop(context)
            _state.value = _state.value.copy(loading = false, message = "El receptor no acepta medios")
            return
        }
        client.queueLoad(
            castItems,
            castStartIndex,
            MediaStatus.REPEAT_MODE_REPEAT_OFF,
            position,
            null
        ).setResultCallback { result ->
            main.post {
                if (result.status.isSuccess) {
                    // El handoff sólo silencia VLC después de que el receptor confirmó la carga.
                    localPlayer?.pause()
                    _state.value = _state.value.copy(
                        loading = false,
                        connected = true,
                        mediaTitle = entry.title.ifBlank { entry.displayName },
                        isPlaying = autoplay,
                        positionMs = position,
                        durationMs = entry.durationMs.coerceAtLeast(0L),
                        message = if (omitted > 0) "$omitted elementos no compatibles se omitieron" else null
                    )
                    remoteContentToLocalIndex = contentIndex
                    publishRemoteStatus()
                } else {
                    CastBridgeService.stop(context)
                    _state.value = _state.value.copy(
                        loading = false,
                        message = "El televisor rechazó el medio · ${result.status.statusCode}"
                    )
                }
            }
        }
    }

    private fun publishRemoteStatus() {
        val client = observedClient ?: return
        val metadata = client.mediaInfo?.metadata
        _state.value = _state.value.copy(
            connected = true,
            loading = false,
            mediaTitle = metadata?.getString(MediaMetadata.KEY_TITLE) ?: _state.value.mediaTitle,
            isPlaying = client.isPlaying,
            positionMs = client.approximateStreamPosition.coerceAtLeast(0L),
            durationMs = client.mediaInfo?.streamDuration?.coerceAtLeast(0L) ?: 0L,
            message = null
        )
    }

    private fun detach(resumePosition: Boolean, message: String?) {
        val position = observedClient?.approximateStreamPosition ?: 0L
        val localIndex = observedClient?.mediaInfo?.contentId?.let(remoteContentToLocalIndex::get)
        observedClient?.unregisterCallback(remoteCallback)
        observedClient = null
        appContext?.let(CastBridgeService::stop)
        if (resumePosition && position > 0L) {
            PlaybackHub.player.value?.takeIf { it.mediaItemCount > 0 }?.let { player ->
                if (localIndex != null && localIndex in 0 until player.mediaItemCount) {
                    player.seekTo(localIndex, position)
                } else {
                    player.seekTo(position)
                }
            }
        }
        remoteContentToLocalIndex = emptyMap()
        _state.value = CastUiState(message = message)
    }
}
