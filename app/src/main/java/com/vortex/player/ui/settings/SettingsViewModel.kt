package com.vortex.player.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.audio.AudioCapabilities
import com.vortex.player.audio.AudioOutput
import com.vortex.player.audio.AudioProMode
import com.vortex.player.audio.AudioProProfiles
import com.vortex.player.audio.AudioPreferences
import com.vortex.player.audio.AudioScope
import com.vortex.player.audio.AudioSettings
import com.vortex.player.audio.EQ_BANDS
import com.vortex.player.audio.EQ_MAX_DB
import com.vortex.player.audio.EQ_MIN_DB
import com.vortex.player.audio.EqPreset
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.spotify.SpotifyAccountState
import com.vortex.player.spotify.SpotifyAuth
import com.vortex.player.spotify.SpotifyLibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val spotifyLibrary = SpotifyLibraryRepository.get(app)

    init {
        SpotifyAuth.initialize(app)
    }

    val spotifyAccount: StateFlow<SpotifyAccountState> = SpotifyAuth.state

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

    /** `null` sin servicio; vacío cuando VLC está activo y no ofrece sesión de efectos. */
    val capabilities: StateFlow<AudioCapabilities?> = PlaybackHub.audioCapabilities

    private fun update(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch {
            AudioPreferences.save(getApplication(), transform(settings.value), profile.value)
        }
    }

    fun setPerOutput(enabled: Boolean) {
        viewModelScope.launch { AudioPreferences.setPerOutput(getApplication(), enabled) }
    }

    fun connectSpotify() {
        viewModelScope.launch { SpotifyAuth.connect(getApplication()) }
    }

    fun disconnectSpotify() {
        val id = (spotifyAccount.value as? SpotifyAccountState.Connected)?.accountId
        SpotifyAuth.disconnect(getApplication())
        if (id != null) {
            viewModelScope.launch { spotifyLibrary.clearAccount(id) }
        }
    }

    fun setEnabled(enabled: Boolean) = update {
        it.copy(enabled = enabled, bypassOn = if (enabled) false else it.bypassOn)
    }

    fun toggleBypass() = update { it.copy(bypassOn = !it.bypassOn) }

    fun setAudioProMode(mode: AudioProMode) = update { AudioProProfiles.apply(mode, it) }

    fun setScope(scope: AudioScope) = update { it.copy(scope = scope) }

    fun setPreset(preset: EqPreset) = update {
        it.copy(
            preset = preset,
            bands = preset.gains,
            equalizerOn = true,
            proMode = AudioProMode.CUSTOM
        )
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
        current.copy(
            preset = null,
            bands = base,
            equalizerOn = true,
            proMode = AudioProMode.CUSTOM
        )
    }

    fun toggleEqualizer() = update {
        it.copy(equalizerOn = !it.equalizerOn, proMode = AudioProMode.CUSTOM)
    }

    fun setBassBoost(value: Int) = update {
        it.copy(bassBoost = value, bassBoostOn = value > 0, proMode = AudioProMode.CUSTOM)
    }
    fun toggleBassBoost() = update {
        it.copy(bassBoostOn = !it.bassBoostOn, proMode = AudioProMode.CUSTOM)
    }

    fun setClarity(value: Int) = update {
        it.copy(clarity = value, clarityOn = value > 0, proMode = AudioProMode.CUSTOM)
    }
    fun toggleClarity() = update {
        it.copy(clarityOn = !it.clarityOn, proMode = AudioProMode.CUSTOM)
    }

    fun setAmbience(value: Int) = update {
        it.copy(ambience = value, ambienceOn = value > 0, proMode = AudioProMode.CUSTOM)
    }
    fun toggleAmbience() = update {
        it.copy(ambienceOn = !it.ambienceOn, proMode = AudioProMode.CUSTOM)
    }

    fun setVirtualizer(value: Int) =
        update {
            it.copy(
                virtualizer = value,
                virtualizerOn = value > 0,
                proMode = AudioProMode.CUSTOM
            )
        }
    fun toggleVirtualizer() = update {
        it.copy(virtualizerOn = !it.virtualizerOn, proMode = AudioProMode.CUSTOM)
    }

    fun setBoost(db: Float) = update {
        it.copy(boostDb = db, boostOn = db > 0f, proMode = AudioProMode.CUSTOM)
    }
    fun toggleBoost() = update {
        if (it.boostOn) {
            it.copy(boostOn = false, proMode = AudioProMode.CUSTOM)
        } else {
            // Encender un boost guardado en 0 dB parecía funcionar pero no hacía nada.
            it.copy(
                boostOn = true,
                boostDb = it.boostDb.takeIf { db -> db > 0f } ?: 6f,
                proMode = AudioProMode.CUSTOM
            )
        }
    }

    fun setCompressor(amount: Float) =
        update {
            it.copy(
                compressor = amount,
                compressorOn = amount > 0f,
                proMode = AudioProMode.CUSTOM
            )
        }
    fun toggleCompressor() = update {
        it.copy(compressorOn = !it.compressorOn, proMode = AudioProMode.CUSTOM)
    }

    fun toggleLimiter() = update {
        it.copy(limiterOn = !it.limiterOn, proMode = AudioProMode.CUSTOM)
    }

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
