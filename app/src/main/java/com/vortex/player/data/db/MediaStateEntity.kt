package com.vortex.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Todo lo que Vórtex recuerda de un medio concreto. La clave es el URI en texto
 * porque un mismo fichero puede reaparecer con otro _ID tras un reescaneo del sistema.
 */
@Entity(tableName = "media_state")
data class MediaStateEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    /** Última pista de audio y subtítulo elegidas, para no repetir la selección cada vez. */
    val preferredAudioTrack: String? = null,
    val preferredSubtitleTrack: String? = null,
    val preferredSpeed: Float = 1f,
    /** Si el usuario dejó este medio en modo solo-audio, se respeta al reabrirlo. */
    val audioOnly: Boolean = false
) {
    /** Se considera terminado si quedan menos de 15 s o pasó del 97 %. */
    val isFinished: Boolean
        get() = durationMs > 0 &&
            (positionMs >= durationMs - 15_000 || positionMs.toFloat() / durationMs > 0.97f)

    val progressFraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
