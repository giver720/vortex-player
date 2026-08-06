package com.vortex.player.playback

import androidx.media3.common.Player
import com.vortex.player.data.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Punto único de encuentro entre el servicio de reproducción y todas las superficies
 * (reproductor, ventana flotante, biblioteca). Como todo vive en el mismo proceso,
 * compartir el [Player] directamente evita el ida y vuelta de un `MediaController`
 * y, sobre todo, permite que la UI siga viendo el mismo estado cuando el motor cambia
 * de Media3 a VLC a mitad de reproducción.
 */
object PlaybackHub {

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _controls = MutableStateFlow<EngineControls?>(null)
    val controls: StateFlow<EngineControls?> = _controls.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaEntry>>(emptyList())
    val queue: StateFlow<List<MediaEntry>> = _queue.asStateFlow()

    private val _currentEntry = MutableStateFlow<MediaEntry?>(null)
    val currentEntry: StateFlow<MediaEntry?> = _currentEntry.asStateFlow()

    /** Solo-audio: el vídeo se apaga pero el sonido continúa sin cortes. */
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** Instante (epoch ms) en el que el temporizador de apagado detendrá la reproducción. */
    private val _sleepAtMs = MutableStateFlow<Long?>(null)
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()

    /** La ventana flotante está en pantalla. */
    private val _popupVisible = MutableStateFlow(false)
    val popupVisible: StateFlow<Boolean> = _popupVisible.asStateFlow()

    internal fun setPlayer(player: Player?, controls: EngineControls?) {
        _player.value = player
        _controls.value = controls
    }

    internal fun setQueue(entries: List<MediaEntry>, index: Int) {
        _queue.value = entries
        _currentEntry.value = entries.getOrNull(index)
    }

    internal fun setCurrentIndex(index: Int) {
        _currentEntry.value = _queue.value.getOrNull(index)
    }

    internal fun setAudioOnly(enabled: Boolean) {
        _audioOnly.value = enabled
    }

    internal fun setSleepAt(atMs: Long?) {
        _sleepAtMs.value = atMs
    }

    internal fun setPopupVisible(visible: Boolean) {
        _popupVisible.value = visible
    }
}
