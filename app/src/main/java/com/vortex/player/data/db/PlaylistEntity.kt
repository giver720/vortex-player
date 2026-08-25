package com.vortex.player.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val coverUri: String? = null,
    /** LOCAL, QUEUE, M3U o SPOTIFY; sólo informa del origen, la lista siempre es editable. */
    val source: String = "LOCAL",
    /** Regla dinámica; `null` significa orden manual almacenado en playlist_items. */
    val smartRule: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Un elemento de una lista.
 *
 * Se guarda el URI y además el título en el momento de añadirlo: si el fichero
 * desaparece del dispositivo, la lista sigue pudiendo mostrar qué había ahí en vez de
 * una fila en blanco.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "uri"], unique = true)
    ]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val isVideo: Boolean = true,
    val position: Int
)

/** Instantánea mínima para añadir o restaurar una pista aunque ya no exista en MediaStore. */
data class PlaylistItemDraft(
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val isVideo: Boolean = true
)
