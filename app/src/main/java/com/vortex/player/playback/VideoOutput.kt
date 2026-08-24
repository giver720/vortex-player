package com.vortex.player.playback

import android.widget.FrameLayout

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

    val audioTracks: List<TrackOption>
    val subtitleTracks: List<TrackOption>

    fun selectAudioTrack(id: String)

    /** `null` desactiva los subtítulos. */
    fun selectSubtitleTrack(id: String?)

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

    /** Carga un fichero de subtítulos externo (.srt, .ass…). */
    fun addExternalSubtitle(uri: String) {}
}
