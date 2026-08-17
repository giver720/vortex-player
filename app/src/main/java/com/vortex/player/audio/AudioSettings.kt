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

    // Música primero: es lo que más se escucha y lo que antes se quería probar. Van más
    // marcados que los de contenido a propósito, porque un preset que no se distingue del
    // plano no sirve de nada; para el retoque fino ya está la curva.
    /** Pegada abajo, medios algo hundidos y presencia arriba: la firma del rock. */
    ROCK("ROCK", listOf(5f, 4f, 2f, -1f, -1.5f, 0f, 2.5f, 4f, 4.5f, 3f)),
    /** Voz al frente sobre una sonrisa suave. */
    POP("POP", listOf(-1f, 1f, 3f, 2f, -0.5f, -1f, 1.5f, 3.5f, 4f, 3f)),
    /** Sub-graves de verdad y brillo cristalino, con los medios apartados. */
    ELECTRONIC("ELECTRÓNICA", listOf(7f, 6f, 3f, 0f, -2f, -1f, 1f, 3f, 5f, 6f)),
    /** El bombo y el 808 mandan; la voz se mantiene por encima sin estridencias. */
    URBAN("URBANO", listOf(8f, 6.5f, 3f, 1f, -1f, -1.5f, 0.5f, 2f, 3f, 2f)),
    /** Cuerpo de madera y aire, sin colorear los medios donde vive la voz. */
    ACOUSTIC("ACÚSTICO", listOf(2f, 1.5f, 0f, 0.5f, 1.5f, 2f, 2f, 1.5f, 2f, 2.5f)),
    /** U muy suave: respeta el rango dinámico, que es de lo que vive una orquesta. */
    CLASSICAL("CLÁSICA", listOf(3f, 2.5f, 1f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f)),

    // Formas de tono, para cuando se busca un color concreto y no un género.
    BASS("GRAVES", listOf(7f, 6f, 4.5f, 2.5f, 0f, 0f, 0f, 0f, 1f, 2f)),
    TREBLE("AGUDOS", listOf(-1f, -1f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5.5f, 6f)),
    SMILE("SONRISA", listOf(6f, 5f, 3f, 0f, -2f, -2f, 0f, 3f, 5f, 6f)),
    VOCAL("VOZ", listOf(-2f, -1.5f, 0f, 1.5f, 3.5f, 4f, 3f, 1.5f, 0f, -1f)),
    NIGHT("NOCHE", listOf(2f, 1.5f, 1f, 1.5f, 2.5f, 2.5f, 2f, 1f, 0.5f, 0f)),

    // Contenido que no es música. Puro reparto de ganancias, sin efectos de por medio.
    /** Diálogo por encima de la banda sonora, con algo de peso en los golpes graves. */
    CINEMA("CINE", listOf(4f, 3f, 1f, -0.5f, 1.5f, 3f, 3f, 1.5f, 1f, 2f)),
    /** Altavoces pequeños: se quita el retumbe que no reproducen y se despeja la voz. */
    TV("TV", listOf(-4f, -2f, 0f, 1f, 3f, 3.5f, 2.5f, 1f, 0f, -1f)),
    /** Pasos y disparos audibles sin que los agudos cansen en sesiones largas. */
    GAMING("JUEGOS", listOf(3f, 2f, 0f, -1f, 0f, 2f, 4f, 4.5f, 3f, 1f)),
    PODCAST("PODCAST", listOf(-6f, -4f, -1f, 2f, 4f, 4.5f, 3.5f, 2f, 0f, -2f))
}

/** A qué audio se aplica el procesado. */
enum class AudioScope(val label: String) {
    /** Sólo lo que reproduce Vórtex. Siempre funciona. */
    VORTEX("SÓLO VÓRTEX"),

    /**
     * La mezcla de salida del sistema, con lo que afecta también a YouTube, Spotify y
     * cualquier otra app. Android lo permite enganchándose a la sesión 0, pero lo tiene
     * marcado como desaconsejado: hay dispositivos y versiones que lo ignoran sin avisar.
     */
    SYSTEM("TODO EL SISTEMA")
}

data class AudioSettings(
    val enabled: Boolean = false,
    val scope: AudioScope = AudioScope.VORTEX,

    /** Ganancia de cada banda en dB. Su tamaño coincide con [EQ_BANDS]. */
    val bands: List<Float> = List(EQ_BANDS.size) { 0f },
    val preset: EqPreset? = EqPreset.FLAT,
    val equalizerOn: Boolean = true,

    val bassBoost: Int = 0,
    val bassBoostOn: Boolean = false,

    /**
     * Claridad: realce de presencia entre 2 y 8 kHz, que es donde viven las consonantes y
     * el ataque de los instrumentos. Se suma dentro del pre-ecualizador igual que los
     * graves, así que no añade ningún efecto a la cadena y el limitador lo sigue viendo.
     */
    val clarity: Int = 0,
    val clarityOn: Boolean = false,

    val virtualizer: Int = 0,
    val virtualizerOn: Boolean = false,

    /**
     * Ambiente: reverberación corta que ensancha la escena. Es el único ajuste que sí
     * cuelga un efecto aparte de la cadena, así que se crea sólo mientras se usa.
     */
    val ambience: Int = 0,
    val ambienceOn: Boolean = false,

    /** Amplificación en decibelios por encima del máximo del sistema. */
    val boostDb: Float = 0f,
    val boostOn: Boolean = false,

    /**
     * Nivelador: comprime el rango dinámico para que lo flojo se oiga y lo fuerte no
     * moleste. Es lo que hace que una grabación pobre suene "llena" sin subir el volumen.
     */
    val compressor: Float = 0f,
    val compressorOn: Boolean = false,

    /**
     * Limitador. Se puede apagar, pero por defecto va puesto.
     *
     * Es lo que recorta los picos cuando el ecualizador o el volumen extra levantan la
     * señal por encima de cero. Apagarlo devuelve los transitorios intactos —los platillos
     * y las percusiones dejan de sonar apelmazados— a cambio de que, si se va de rango,
     * recorte el propio conversor y eso sí distorsiona de verdad.
     */
    val limiterOn: Boolean = true
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

    /**
     * Sufijo de las claves según la salida.
     *
     * Con `null` no se añade nada, que es exactamente el juego de claves que existía antes
     * de haber perfiles: quien venga de una versión anterior se encuentra sus ajustes
     * intactos, y sólo estrena perfiles vacíos si activa la opción.
     */
    private fun suffix(output: AudioOutput?): String = output?.let { "_${it.name}" }.orEmpty()

    private fun enabledKey(o: AudioOutput?) = booleanPreferencesKey("audio_enabled_v2${suffix(o)}")
    private fun scopeKey(o: AudioOutput?) = stringPreferencesKey("audio_scope${suffix(o)}")
    private fun bandsKey(o: AudioOutput?) = stringPreferencesKey("eq_bands_v2${suffix(o)}")
    private fun presetKey(o: AudioOutput?) = stringPreferencesKey("eq_preset_v2${suffix(o)}")
    private fun eqOnKey(o: AudioOutput?) = booleanPreferencesKey("eq_on${suffix(o)}")
    private fun bassKey(o: AudioOutput?) = intPreferencesKey("bass_boost${suffix(o)}")
    private fun bassOnKey(o: AudioOutput?) = booleanPreferencesKey("bass_on${suffix(o)}")
    private fun clarityKey(o: AudioOutput?) = intPreferencesKey("clarity${suffix(o)}")
    private fun clarityOnKey(o: AudioOutput?) = booleanPreferencesKey("clarity_on${suffix(o)}")
    private fun virtualizerKey(o: AudioOutput?) = intPreferencesKey("virtualizer${suffix(o)}")
    private fun virtualizerOnKey(o: AudioOutput?) =
        booleanPreferencesKey("virtualizer_on${suffix(o)}")
    private fun ambienceKey(o: AudioOutput?) = intPreferencesKey("ambience${suffix(o)}")
    private fun ambienceOnKey(o: AudioOutput?) = booleanPreferencesKey("ambience_on${suffix(o)}")
    private fun boostKey(o: AudioOutput?) = stringPreferencesKey("boost_db${suffix(o)}")
    private fun boostOnKey(o: AudioOutput?) = booleanPreferencesKey("boost_on${suffix(o)}")
    private fun compressorKey(o: AudioOutput?) = stringPreferencesKey("compressor${suffix(o)}")
    private fun compressorOnKey(o: AudioOutput?) =
        booleanPreferencesKey("compressor_on${suffix(o)}")
    private fun limiterOnKey(o: AudioOutput?) = booleanPreferencesKey("limiter_on${suffix(o)}")

    /** Global, no por salida: decide si el resto de claves llevan sufijo. */
    private val PER_OUTPUT = booleanPreferencesKey("per_output_profiles")

    fun observePerOutput(context: Context): Flow<Boolean> =
        context.audioDataStore.data.map { it[PER_OUTPUT] ?: false }

    suspend fun setPerOutput(context: Context, enabled: Boolean) {
        context.audioDataStore.edit { it[PER_OUTPUT] = enabled }
    }

    fun observe(context: Context, output: AudioOutput? = null): Flow<AudioSettings> =
        context.audioDataStore.data.map { prefs ->
            AudioSettings(
                enabled = prefs[enabledKey(output)] ?: false,
                scope = prefs[scopeKey(output)]
                    ?.let { runCatching { AudioScope.valueOf(it) }.getOrNull() }
                    ?: AudioScope.VORTEX,
                bands = prefs[bandsKey(output)]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toFloatOrNull() }
                    ?.takeIf { it.size == EQ_BANDS.size }
                    ?: List(EQ_BANDS.size) { 0f },
                // Sin valor guardado se arranca en PLANO. El modo manual se marca con un
                // valor propio: si se dedujera de la ausencia de clave, un primer arranque
                // se anunciaría como "curva ajustada a mano" sin que nadie la haya tocado.
                preset = when (val stored = prefs[presetKey(output)]) {
                    null -> EqPreset.FLAT
                    MANUAL -> null
                    else -> runCatching { EqPreset.valueOf(stored) }.getOrDefault(EqPreset.FLAT)
                },
                equalizerOn = prefs[eqOnKey(output)] ?: true,
                bassBoost = prefs[bassKey(output)] ?: 0,
                bassBoostOn = prefs[bassOnKey(output)] ?: false,
                clarity = prefs[clarityKey(output)] ?: 0,
                clarityOn = prefs[clarityOnKey(output)] ?: false,
                virtualizer = prefs[virtualizerKey(output)] ?: 0,
                virtualizerOn = prefs[virtualizerOnKey(output)] ?: false,
                ambience = prefs[ambienceKey(output)] ?: 0,
                ambienceOn = prefs[ambienceOnKey(output)] ?: false,
                boostDb = prefs[boostKey(output)]?.toFloatOrNull() ?: 0f,
                boostOn = prefs[boostOnKey(output)] ?: false,
                compressor = prefs[compressorKey(output)]?.toFloatOrNull() ?: 0f,
                compressorOn = prefs[compressorOnKey(output)] ?: false,
                // Por defecto puesto: quien no lo toque sigue protegido como hasta ahora.
                limiterOn = prefs[limiterOnKey(output)] ?: true
            )
        }

    suspend fun save(context: Context, settings: AudioSettings, output: AudioOutput? = null) {
        context.audioDataStore.edit { prefs ->
            prefs[enabledKey(output)] = settings.enabled
            prefs[scopeKey(output)] = settings.scope.name
            prefs[bandsKey(output)] = settings.bands.joinToString(",")
            prefs[presetKey(output)] = settings.preset?.name ?: MANUAL
            prefs[eqOnKey(output)] = settings.equalizerOn
            prefs[bassKey(output)] = settings.bassBoost
            prefs[bassOnKey(output)] = settings.bassBoostOn
            prefs[clarityKey(output)] = settings.clarity
            prefs[clarityOnKey(output)] = settings.clarityOn
            prefs[virtualizerKey(output)] = settings.virtualizer
            prefs[virtualizerOnKey(output)] = settings.virtualizerOn
            prefs[ambienceKey(output)] = settings.ambience
            prefs[ambienceOnKey(output)] = settings.ambienceOn
            prefs[boostKey(output)] = settings.boostDb.toString()
            prefs[boostOnKey(output)] = settings.boostOn
            prefs[compressorKey(output)] = settings.compressor.toString()
            prefs[compressorOnKey(output)] = settings.compressorOn
            prefs[limiterOnKey(output)] = settings.limiterOn
        }
    }
}
