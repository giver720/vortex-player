package com.vortex.player.playback

enum class PlaybackSourceKind(val label: String) {
    LOCAL("LOCAL"),
    HTTP("WEB"),
    HLS("HLS"),
    RTSP("RTSP"),
    OTHER_NETWORK("RED")
}

enum class DecoderMode(val label: String) {
    HARDWARE("HARDWARE"),
    SOFTWARE("SOFTWARE");

    val shortLabel: String get() = if (this == HARDWARE) "HW" else "SW"
}

enum class PlaybackHealth(val label: String) {
    IDLE("EN ESPERA"),
    OPENING("ABRIENDO"),
    BUFFERING("CARGANDO"),
    PLAYING("ESTABLE"),
    RECOVERING("RECUPERANDO"),
    ERROR("ERROR")
}

data class PlaybackPlan(
    val source: PlaybackSourceKind,
    val decoder: DecoderMode,
    val cacheMs: Int,
    val mediaOptions: List<String>
)

data class RecoveryDecision(
    val shouldRetry: Boolean,
    val decoder: DecoderMode
)

object PlaybackRecoveryPolicy {
    const val MAX_AUTOMATIC_RECOVERIES = 2

    fun decide(currentDecoder: DecoderMode, completedRecoveries: Int): RecoveryDecision = when {
        completedRecoveries >= MAX_AUTOMATIC_RECOVERIES -> RecoveryDecision(false, currentDecoder)
        currentDecoder == DecoderMode.HARDWARE -> RecoveryDecision(true, DecoderMode.SOFTWARE)
        else -> RecoveryDecision(true, DecoderMode.SOFTWARE)
    }
}

data class PlaybackStartPlan(
    val mediaStartPositionMs: Long,
    val seekAfterPlayingMs: Long?
)

/**
 * Abrir el demuxer directamente a mitad de un GOP puede enseñar cuadros verdes hasta el siguiente
 * fotograma clave. Se abre siempre de forma limpia y el seek preciso se hace con VLC ya iniciado.
 */
object PlaybackStartPolicy {
    fun plan(requestedPositionMs: Long): PlaybackStartPlan {
        val target = requestedPositionMs.coerceAtLeast(0L)
        return PlaybackStartPlan(
            mediaStartPositionMs = 0L,
            seekAfterPlayingMs = target.takeIf { it > 0L }
        )
    }
}

data class VideoLivenessSample(
    val playbackActive: Boolean,
    val videoEnabled: Boolean,
    val outputAttached: Boolean,
    val hasVideoTrack: Boolean,
    val statsAvailable: Boolean,
    val decoder: DecoderMode,
    val elapsedWithoutDisplayedFrameMs: Long,
    val timelineAdvanceMs: Long
)

/**
 * Detecta el caso que el watchdog de tiempo no puede ver: el audio avanza pero VLC no entrega
 * ningún cuadro a la salida de vídeo. Sólo degrada hardware; software conserva el audio y deja
 * disponible el reintento manual en vez de entrar en un bucle.
 */
object VideoLivenessPolicy {
    const val FRAME_TIMEOUT_MS = 8_000L
    const val MIN_TIMELINE_ADVANCE_MS = 3_000L

    fun shouldFallbackToSoftware(sample: VideoLivenessSample): Boolean =
        sample.playbackActive &&
            sample.videoEnabled &&
            sample.outputAttached &&
            sample.hasVideoTrack &&
            sample.statsAvailable &&
            sample.decoder == DecoderMode.HARDWARE &&
            sample.elapsedWithoutDisplayedFrameMs >= FRAME_TIMEOUT_MS &&
            sample.timelineAdvanceMs >= MIN_TIMELINE_ADVANCE_MS
}

data class SurfaceReattachSample(
    val playbackActive: Boolean,
    val outputAttached: Boolean,
    val hasVideoTrack: Boolean,
    val elapsedSinceAttachMs: Long,
    val timelineAdvanceMs: Long,
    val displayedFramesAtAttach: Int?,
    val displayedFramesNow: Int?
)

/**
 * Detecta una salida nueva que VLC aceptó pero a la que el decodificador no entregó cuadros.
 * A diferencia del watchdog general, admite que algunos builds no expongan estadísticas: si
 * el audio/reloj avanza y hay una pista de vídeo, una superficie negra también debe recuperarse.
 */
object SurfaceReattachPolicy {
    const val FRAME_TIMEOUT_MS = 3_000L
    const val MIN_TIMELINE_ADVANCE_MS = 750L

    fun shouldReopen(sample: SurfaceReattachSample): Boolean {
        val deliveredNewFrame = sample.displayedFramesAtAttach != null &&
            sample.displayedFramesNow != null &&
            sample.displayedFramesNow > sample.displayedFramesAtAttach
        return sample.playbackActive &&
            sample.outputAttached &&
            sample.hasVideoTrack &&
            sample.elapsedSinceAttachMs >= FRAME_TIMEOUT_MS &&
            sample.timelineAdvanceMs >= MIN_TIMELINE_ADVANCE_MS &&
            !deliveredNewFrame
    }
}

data class PlaybackDiagnostics(
    val source: PlaybackSourceKind = PlaybackSourceKind.LOCAL,
    val decoder: DecoderMode = DecoderMode.HARDWARE,
    val health: PlaybackHealth = PlaybackHealth.IDLE,
    val cacheMs: Int = 0,
    val recoveryCount: Int = 0,
    val lastRecovery: String? = null,
    val codec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val framesPerSecond: Float = 0f,
    val inputBitrateKbps: Int = 0,
    val decodedFrames: Int = 0,
    val displayedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val corruptedPackets: Int = 0
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width} × $height" else "—"
}

/** Selecciona opciones conservadoras sin depender de clases Android, para poder probarlo en JVM. */
object PlaybackIntelligencePlanner {
    fun plan(uri: String, mimeType: String?, lowRamDevice: Boolean): PlaybackPlan {
        val normalized = uri.lowercase()
        val mime = mimeType.orEmpty().lowercase()
        val scheme = normalized.substringBefore(':', missingDelimiterValue = "")
        val source = when {
            scheme == "rtsp" -> PlaybackSourceKind.RTSP
            normalized.contains(".m3u8") || mime.contains("mpegurl") -> PlaybackSourceKind.HLS
            scheme == "http" || scheme == "https" -> PlaybackSourceKind.HTTP
            scheme in setOf("rtmp", "mms", "udp", "tcp") -> PlaybackSourceKind.OTHER_NETWORK
            else -> PlaybackSourceKind.LOCAL
        }
        val cacheMs = when (source) {
            PlaybackSourceKind.LOCAL -> if (lowRamDevice) 200 else 350
            PlaybackSourceKind.HTTP -> if (lowRamDevice) 1_800 else 2_500
            PlaybackSourceKind.HLS -> if (lowRamDevice) 2_500 else 4_000
            PlaybackSourceKind.RTSP -> if (lowRamDevice) 1_200 else 1_800
            PlaybackSourceKind.OTHER_NETWORK -> if (lowRamDevice) 1_800 else 3_000
        }
        val options = buildList {
            if (source == PlaybackSourceKind.LOCAL) {
                add(":file-caching=$cacheMs")
            } else {
                add(":network-caching=$cacheMs")
                add(":live-caching=$cacheMs")
            }
            if (source == PlaybackSourceKind.HTTP || source == PlaybackSourceKind.HLS) {
                add(":http-reconnect")
            }
            if (source == PlaybackSourceKind.RTSP) add(":rtsp-tcp")
        }
        return PlaybackPlan(
            source = source,
            decoder = DecoderMode.HARDWARE,
            cacheMs = cacheMs,
            mediaOptions = options
        )
    }
}
