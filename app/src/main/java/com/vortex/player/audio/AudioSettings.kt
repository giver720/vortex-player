package com.vortex.player.audio

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.audioDataStore by preferencesDataStore("vortex_audio")

/**
 * Bandas del ecualizador, en hercios.
 *
 * Diez bandas en octavas, que es el reparto clásico y el que espera cualquiera que haya
 * tocado un ecualizador. El del sistema suele dar sólo cinco; estas las definimos nosotros
 * sobre `DynamicsProcessing`, así que no dependen de lo que traiga el móvil.
 */
val EQ_BANDS = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

/** Recorrido de cada banda en decibelios. Más de ±12 dB deja de sonar a música. */
const val EQ_MIN_DB = -12f
const val EQ_MAX_DB = 12f

/** Ajuste preparado del ecualizador. */
enum class EqPreset(val label: String, val gains: List<Float>) {
    FLAT("PLANO", List(10) { 0f }),
    BASS("GRAVES", listOf(7f, 6f, 4.5f, 2.5f, 0f, 0f, 0f, 0f, 1f, 2f)),
    VOCAL("VOZ", listOf(-2f, -1.5f, 0f, 1.5f, 3.5f, 4f, 3f, 1.5f, 0f, -1f)),
    TREBLE("AGUDOS", listOf(-1f, -1f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5.5f, 6f)),
    SMILE("SONRISA", listOf(6f, 5f, 3f, 0f, -2f, -2f, 0f, 3f, 5f, 6f)),
    PODCAST("PODCAST", listOf(-6f, -4f, -1f, 2f, 4f, 4.5f, 3.5f, 2f, 0f, -2f)),
    NIGHT("NOCHE", listOf(2f, 1.5f, 1f, 1.5f, 2.5f, 2.5f, 2f, 1f, 0.5f, 0f))
}

data class AudioSettings(
    val enabled: Boolean = false,

    /** Ganancia de cada banda en dB. Su tamaño coincide con [EQ_BANDS]. */
    val bands: List<Float> = List(EQ_BANDS.size) { 0f },
    val preset: EqPreset? = EqPreset.FLAT,
    val equalizerOn: Boolean = true,

    val bassBoost: Int = 0,
    val bassBoostOn: Boolean = false,

    val virtualizer: Int = 0,
    val virtualizerOn: Boolean = false,

    /** Amplificación en decibelios por encima del máximo del sistema. */
    val boostDb: Float = 0f,
    val boostOn: Boolean = false,

    /**
     * Nivelador: comprime el rango dinámico para que lo flojo se oiga y lo fuerte no
     * moleste. Es lo que hace que una grabación pobre suene "llena" sin subir el volumen.
     */
    val compressor: Float = 0f,
    val compressorOn: Boolean = false
) {
    val effectiveBands: List<Float>
        get() = preset?.gains ?: bands

    companion object {
        const val MAX_BOOST_DB = 15f
        const val MAX_STRENGTH = 1000
    }
}

object AudioPreferences {

    /** Marca de "el usuario movió la curva", distinta de "nunca se ha guardado nada". */
    private const val MANUAL = "MANUAL"

    private val ENABLED = booleanPreferencesKey("audio_enabled_v2")
    private val BANDS = stringPreferencesKey("eq_bands_v2")
    private val PRESET = stringPreferencesKey("eq_preset_v2")
    private val EQ_ON = booleanPreferencesKey("eq_on")
    private val BASS = intPreferencesKey("bass_boost")
    private val BASS_ON = booleanPreferencesKey("bass_on")
    private val VIRTUALIZER = intPreferencesKey("virtualizer")
    private val VIRTUALIZER_ON = booleanPreferencesKey("virtualizer_on")
    private val BOOST = stringPreferencesKey("boost_db")
    private val BOOST_ON = booleanPreferencesKey("boost_on")
    private val COMPRESSOR = stringPreferencesKey("compressor")
    private val COMPRESSOR_ON = booleanPreferencesKey("compressor_on")

    fun observe(context: Context): Flow<AudioSettings> =
        context.audioDataStore.data.map { prefs ->
            AudioSettings(
                enabled = prefs[ENABLED] ?: false,
                bands = prefs[BANDS]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toFloatOrNull() }
                    ?.takeIf { it.size == EQ_BANDS.size }
                    ?: List(EQ_BANDS.size) { 0f },
                // Sin valor guardado se arranca en PLANO. El modo manual se marca con un
                // valor propio: si se dedujera de la ausencia de clave, un primer arranque
                // se anunciaría como "curva ajustada a mano" sin que nadie la haya tocado.
                preset = when (val stored = prefs[PRESET]) {
                    null -> EqPreset.FLAT
                    MANUAL -> null
                    else -> runCatching { EqPreset.valueOf(stored) }.getOrDefault(EqPreset.FLAT)
                },
                equalizerOn = prefs[EQ_ON] ?: true,
                bassBoost = prefs[BASS] ?: 0,
                bassBoostOn = prefs[BASS_ON] ?: false,
                virtualizer = prefs[VIRTUALIZER] ?: 0,
                virtualizerOn = prefs[VIRTUALIZER_ON] ?: false,
                boostDb = prefs[BOOST]?.toFloatOrNull() ?: 0f,
                boostOn = prefs[BOOST_ON] ?: false,
                compressor = prefs[COMPRESSOR]?.toFloatOrNull() ?: 0f,
                compressorOn = prefs[COMPRESSOR_ON] ?: false
            )
        }

    suspend fun save(context: Context, settings: AudioSettings) {
        context.audioDataStore.edit { prefs ->
            prefs[ENABLED] = settings.enabled
            prefs[BANDS] = settings.bands.joinToString(",")
            prefs[PRESET] = settings.preset?.name ?: MANUAL
            prefs[EQ_ON] = settings.equalizerOn
            prefs[BASS] = settings.bassBoost
            prefs[BASS_ON] = settings.bassBoostOn
            prefs[VIRTUALIZER] = settings.virtualizer
            prefs[VIRTUALIZER_ON] = settings.virtualizerOn
            prefs[BOOST] = settings.boostDb.toString()
            prefs[BOOST_ON] = settings.boostOn
            prefs[COMPRESSOR] = settings.compressor.toString()
            prefs[COMPRESSOR_ON] = settings.compressorOn
        }
    }
}
