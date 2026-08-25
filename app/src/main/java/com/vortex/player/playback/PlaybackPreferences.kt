package com.vortex.player.playback

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackDataStore by preferencesDataStore("vortex_playback")

/**
 * Modo de repetición tal y como se ofrece en la interfaz.
 *
 * Es un enum propio y no el entero de Media3 porque el botón cicla entre los tres estados
 * en un orden concreto y cada uno necesita su etiqueta; guardar el entero suelto obligaría
 * a repetir esa tabla en cada superficie que lo pinte.
 */
enum class RepeatMode(val label: String, val playerMode: Int) {
    OFF("SIN BUCLE", Player.REPEAT_MODE_OFF),
    ALL("BUCLE", Player.REPEAT_MODE_ALL),
    ONE("BUCLE 1", Player.REPEAT_MODE_ONE);

    /** Siguiente estado del ciclo del botón: ninguno → toda la cola → sólo esta pista. */
    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/** Cómo recorre Vórtex la cola. Se recuerda entre sesiones. */
data class PlaybackPrefs(
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val autoplay: Boolean = false
)

object PlaybackPreferences {

    private val REPEAT = stringPreferencesKey("repeat_mode")
    private val SHUFFLE = booleanPreferencesKey("shuffle")
    private val AUTOPLAY = booleanPreferencesKey("autoplay")

    fun observe(context: Context): Flow<PlaybackPrefs> =
        context.playbackDataStore.data.map { prefs ->
            PlaybackPrefs(
                repeat = prefs[REPEAT]
                    ?.let { name -> runCatching { RepeatMode.valueOf(name) }.getOrNull() }
                    ?: RepeatMode.OFF,
                shuffle = prefs[SHUFFLE] ?: false,
                autoplay = prefs[AUTOPLAY] ?: false
            )
        }

    suspend fun save(context: Context, prefs: PlaybackPrefs) {
        context.playbackDataStore.edit {
            it[REPEAT] = prefs.repeat.name
            it[SHUFFLE] = prefs.shuffle
            it[AUTOPLAY] = prefs.autoplay
        }
    }
}
