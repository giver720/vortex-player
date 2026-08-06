package com.vortex.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    fun observeAllItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun itemsOf(playlistId: Long): List<PlaylistItemEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND uri = :uri")
    suspend fun removeItem(playlistId: Long, uri: String)

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, now: Long = System.currentTimeMillis())

    /**
     * Añade al final evitando duplicados: volver a añadir algo que ya está en la lista
     * no debe crear una segunda fila idéntica.
     */
    @Transaction
    suspend fun append(playlistId: Long, entries: List<Pair<String, String>>) {
        val existing = itemsOf(playlistId).map { it.uri }.toSet()
        var position = lastPosition(playlistId)
        val toAdd = entries.filter { it.first !in existing }.map { (uri, title) ->
            PlaylistItemEntity(
                playlistId = playlistId,
                uri = uri,
                title = title,
                position = ++position
            )
        }
        if (toAdd.isNotEmpty()) insertItems(toAdd)
        touch(playlistId)
    }

    /** Reescribe el orden completo tras arrastrar una pista. */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedUris: List<String>) {
        val current = itemsOf(playlistId).associateBy { it.uri }
        val rebuilt = orderedUris.mapIndexedNotNull { index, uri ->
            current[uri]?.copy(position = index)
        }
        clearItems(playlistId)
        insertItems(rebuilt.map { it.copy(id = 0) })
        touch(playlistId)
    }

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearItems(playlistId: Long)
}
