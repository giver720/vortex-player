package com.vortex.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE id = :itemId AND playlistId = :playlistId")
    suspend fun removeItemById(playlistId: Long, itemId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND uri = :uri")
    suspend fun removeItemByUri(playlistId: Long, uri: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND id IN (:itemIds)")
    suspend fun removeItems(playlistId: Long, itemIds: List<Long>)

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE playlists SET name = :name, description = :description, coverUri = :coverUri, " +
            "updatedAt = :now WHERE id = :playlistId"
    )
    suspend fun updateDetails(
        playlistId: Long,
        name: String,
        description: String,
        coverUri: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, now: Long = System.currentTimeMillis())

    /**
     * Añade al final evitando duplicados: volver a añadir algo que ya está en la lista
     * no debe crear una segunda fila idéntica.
     */
    @Transaction
    suspend fun append(playlistId: Long, entries: List<PlaylistItemDraft>) {
        val existing = itemsOf(playlistId).map { it.uri }.toSet()
        var position = lastPosition(playlistId)
        val toAdd = entries.distinctBy { it.uri }.filter { it.uri !in existing }.map { draft ->
            PlaylistItemEntity(
                playlistId = playlistId,
                uri = draft.uri,
                title = draft.title,
                artist = draft.artist,
                album = draft.album,
                durationMs = draft.durationMs,
                isVideo = draft.isVideo,
                position = ++position
            )
        }
        if (toAdd.isNotEmpty()) insertItems(toAdd)
        touch(playlistId)
    }

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId AND playlistId = :playlistId")
    suspend fun updatePosition(playlistId: Long, itemId: Long, position: Int)

    /** Actualiza sólo posiciones: conserva IDs y evita borrar/reinsertar listas grandes. */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedItemIds: List<Long>) {
        val current = itemsOf(playlistId)
        val known = current.associateBy { it.id }
        val complete = orderedItemIds.distinct().filter { it in known } +
            current.map { it.id }.filterNot { it in orderedItemIds }
        complete.forEachIndexed { index, id ->
            if (known[id]?.position != index) updatePosition(playlistId, id, index)
        }
        touch(playlistId)
    }

    @Transaction
    suspend fun removeAndTouch(playlistId: Long, itemIds: List<Long>) {
        if (itemIds.isNotEmpty()) removeItems(playlistId, itemIds)
        itemsOf(playlistId).forEachIndexed { index, item ->
            if (item.position != index) updatePosition(playlistId, item.id, index)
        }
        touch(playlistId)
    }

    @Transaction
    suspend fun restore(playlistId: Long, item: PlaylistItemDraft, atPosition: Int) {
        val current = itemsOf(playlistId).filterNot { it.uri == item.uri }
        val position = atPosition.coerceIn(0, current.size)
        val drafts = current.map {
            PlaylistItemDraft(it.uri, it.title, it.artist, it.album, it.durationMs, it.isVideo)
        }.toMutableList().apply { add(position, item) }
        clearItems(playlistId)
        insertItems(drafts.mapIndexed { index, draft ->
            PlaylistItemEntity(
                playlistId = playlistId,
                uri = draft.uri,
                title = draft.title,
                artist = draft.artist,
                album = draft.album,
                durationMs = draft.durationMs,
                isVideo = draft.isVideo,
                position = index
            )
        })
        touch(playlistId)
    }

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearItems(playlistId: Long)
}
