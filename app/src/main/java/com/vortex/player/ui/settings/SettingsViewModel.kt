package com.vortex.player.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.audio.AudioCapabilities
import com.vortex.player.audio.AudioOutput
import com.vortex.player.audio.AudioPreferences
import com.vortex.player.audio.AudioScope
import com.vortex.player.audio.AudioSettings
import com.vortex.player.audio.EQ_BANDS
import com.vortex.player.audio.EQ_MAX_DB
import com.vortex.player.audio.EQ_MIN_DB
import com.vortex.player.audio.EqPreset
import com.vortex.player.playback.PlaybackHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** Si los perfiles por salida están activos, o se comparte uno solo. */
    val perOutput: StateFlow<Boolean> = AudioPreferences.observePerOutput(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Salida activa. Se enseña siempre, aunque los perfiles estén apagados. */
    val output: StateFlow<AudioOutput> = AudioOutput.observe(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioOutput.SPEAKER)

    /**
     * Perfil que se está editando: la salida activa si hay perfiles, o `null` —el juego de
     * claves de siempre— si no. Todo lo que se guarda va a este perfil, de modo que ajustar
     * el ecualizador con los auriculares puestos no toca el del altavoz.
     */
    private val profile: StateFlow<AudioOutput?> =
        combine(perOutput, output) { per, out -> out.takeIf { per } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val settings: StateFlow<AudioSettings> = profile
        .flatMapLatest { AudioPreferences.observe(app, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioSettings())

    /** `null` mientras no haya reproducción; sin capacidades si el motor activo es VLC. */
    val capabilities: StateFlow<AudioCapabilities?> = PlaybackHub.audioCapabilities

    private fun update(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch {
            AudioPreferences.save(getApplication(), transform(settings.value), profile.value)
        }
    }

    fun setPerOutput(enabled: Boolean) {
        viewModelScope.launch { AudioPreferences.setPerOutput(getApplication(), enabled) }
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setScope(scope: AudioScope) = update { it.copy(scope = scope) }

    fun setPreset(preset: EqPreset) = update {
        it.copy(preset = preset, bands = preset.gains, equalizerOn = true)
    }

    /**
     * Mover una banda saca del preset y pasa a manual, partiendo de la curva que había:
     * si no, el primer arrastre saltaría desde plano y perderías el ajuste anterior.
     */
    fun setBand(index: Int, gain: Float) = update { current ->
        val base = current.effectiveBands.toMutableList()
        while (base.size < EQ_BANDS.size) base.add(0f)
        if (index in base.indices) {
            base[index] = gain.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
        }
        current.copy(preset = null, bands = base, equalizerOn = true)
    }

    fun toggleEqualizer() = update { it.copy(equalizerOn = !it.equalizerOn) }

    fun setBassBoost(value: Int) = update { it.copy(bassBoost = value, bassBoostOn = value > 0) }
    fun toggleBassBoost() = update { it.copy(bassBoostOn = !it.bassBoostOn) }

    fun setClarity(value: Int) = update { it.copy(clarity = value, clarityOn = value > 0) }
    fun toggleClarity() = update { it.copy(clarityOn = !it.clarityOn) }

    fun setAmbience(value: Int) = update { it.copy(ambience = value, ambienceOn = value > 0) }
    fun toggleAmbience() = update { it.copy(ambienceOn = !it.ambienceOn) }

    fun setVirtualizer(value: Int) =
        update { it.copy(virtualizer = value, virtualizerOn = value > 0) }
    fun toggleVirtualizer() = update { it.copy(virtualizerOn = !it.virtualizerOn) }

    fun setBoost(db: Float) = update { it.copy(boostDb = db, boostOn = db > 0f) }
    fun toggleBoost() = update { it.copy(boostOn = !it.boostOn) }

    fun setCompressor(amount: Float) =
        update { it.copy(compressor = amount, compressorOn = amount > 0f) }
    fun toggleCompressor() = update { it.copy(compressorOn = !it.compressorOn) }

    fun reset() {
        viewModelScope.launch {
            AudioPreferences.save(
                getApplication(),
                AudioSettings(enabled = settings.value.enabled),
                profile.value
            )
        }
    }
}
