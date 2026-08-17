package com.vortex.player.playback

import androidx.media3.common.Player
import com.vortex.player.audio.AudioCapabilities
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

    /**
     * Cola, posición dentro de ella e instante del medio, guardados aquí y no en el
     * `Player`.
     *
     * Es lo que permite reanudar cuando el motor ya no existe: Android se lleva por
     * delante los servicios que no están en primer plano, y en pausa el de reproducción
     * lo está sólo un rato. Al morir, el `Player` se libera y la interfaz se quedaba con
     * la barra pintada y un botón que no hablaba con nadie. Con esto se puede volver a
     * levantar exactamente la misma cola por donde iba.
     */
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    internal fun setPosition(ms: Long) {
        if (ms >= 0) _positionMs.value = ms
    }

    /**
     * Repetición y aleatorio. Se guardan aquí, y no se leen del `Player`, porque son
     * preferencias del usuario que sobreviven al cambio de motor y a que no haya nada
     * sonando: el motor es sólo quien las obedece.
     */
    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    internal fun setRepeat(mode: RepeatMode) {
        _repeat.value = mode
    }

    internal fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
    }

    /** Solo-audio: el vídeo se apaga pero el sonido continúa sin cortes. */
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** Instante (epoch ms) en el que el temporizador de apagado detendrá la reproducción. */
    private val _sleepAtMs = MutableStateFlow<Long?>(null)
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()

    /**
     * Lo que el DSP del dispositivo permite en la sesión actual. Es `null` mientras no
     * haya reproducción, y queda vacío cuando el motor activo es VLC, que no expone
     * sesión de audio a la que enganchar efectos.
     */
    private val _audioCapabilities = MutableStateFlow<AudioCapabilities?>(null)
    val audioCapabilities: StateFlow<AudioCapabilities?> = _audioCapabilities.asStateFlow()

    internal fun setAudioCapabilities(capabilities: AudioCapabilities?) {
        _audioCapabilities.value = capabilities
    }

    /** La ventana flotante está en pantalla. */
    private val _popupVisible = MutableStateFlow(false)
    val popupVisible: StateFlow<Boolean> = _popupVisible.asStateFlow()

    internal fun setPlayer(player: Player?, controls: EngineControls?) {
        _player.value = player
        _controls.value = controls
    }

    /**
     * Cola nueva. La posición se recibe en vez de conservarse porque una cola distinta
     * puede empezar en el mismo índice que la anterior: sin esto, elegir otra canción que
     * cayera en la misma posición de la lista heredaría el minutaje de la que sonaba.
     */
    internal fun setQueue(entries: List<MediaEntry>, index: Int, positionMs: Long) {
        _queue.value = entries
        _currentIndex.value = index
        _currentEntry.value = entries.getOrNull(index)
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    internal fun setCurrentIndex(index: Int) {
        // Sólo al cambiar de medio se pone el reloj a cero: si no, reanudar saltaría a la
        // posición que llevaba la pista anterior. Se compara con el índice actual porque
        // al recrear la cola el motor reanuncia el mismo elemento, y borrar ahí la
        // posición desharía justo lo que se acaba de rescatar.
        if (index != _currentIndex.value) _positionMs.value = 0L
        _currentIndex.value = index
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
