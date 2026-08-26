package com.vortex.player.playback

import android.widget.FrameLayout
import kotlinx.coroutines.flow.StateFlow

/**
 * Modos de escala que entiende el motor de vídeo. Mantenerlos en playback evita que la UI
 * intente redimensionar por su cuenta un SurfaceView, cuya superficie vive fuera de Compose.
 */
enum class VideoScaleMode {
    BEST_FIT,
    FIT_SCREEN,
    FILL,
    ORIGINAL,
    RATIO_16_9,
    RATIO_4_3,
    RATIO_16_10,
    RATIO_221_1,
    RATIO_235_1,
    RATIO_239_1,
    RATIO_5_4
}

/** Una pista seleccionable (audio o subtítulo) tal y como la ve la interfaz. */
data class TrackOption(
    val id: String,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false
)

/**
 * Controles de VLC que no forman parte del contrato [androidx.media3.common.Player]:
 * pistas, salida de vídeo, subtítulos externos y el interruptor de solo-audio.
 */
interface EngineControls {

    /** Nombre del motor único que se muestra en el HUD. */
    val engineName: String

    /** Telemetría ligera del medio actual, actualizada como máximo una vez por segundo. */
    val diagnostics: StateFlow<PlaybackDiagnostics>

    /** Verdadero cuando la salida nueva ya está lista para revelar la imagen. */
    val videoOutputReady: StateFlow<Boolean>

    /** Reabre el medio actual con decodificación por software conservando la posición. */
    fun retryInSafeMode() {}

    val audioTracks: List<TrackOption>
    val subtitleTracks: List<TrackOption>

    fun selectAudioTrack(id: String)

    /** `null` desactiva los subtítulos. */
    fun selectSubtitleTrack(id: String?)

    /** Retardo de la pista principal en milisegundos; admite valores negativos. */
    val subtitleDelayMs: Long get() = 0L

    fun setSubtitleDelayMs(delayMs: Long) {}

    /**
     * El interruptor que convierte un MP4 en un MP3: apaga la decodificación de vídeo
     * sin tocar el audio ni la posición. Ahorra batería y permite seguir con la pantalla apagada.
     */
    fun setVideoEnabled(enabled: Boolean)

    val isVideoEnabled: Boolean

    /** Monta la superficie de vídeo dentro del contenedor dado. */
    fun attachVideoOutput(container: FrameLayout)

    /** Suelta la superficie sin detener la reproducción (paso previo al modo solo-audio). */
    fun detachVideoOutput()

    /** Aplica tamaño, recorte o relación directamente sobre la salida nativa de VLC. */
    fun setVideoScale(mode: VideoScaleMode)

    /** Carga un fichero de subtítulos externo (.srt, .ass…). */
    fun addExternalSubtitle(uri: String) {}
}
