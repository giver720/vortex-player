package com.vortex.player.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaStateDao {

    @Query("SELECT * FROM media_state")
    fun observeAll(): Flow<List<MediaStateEntity>>

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    suspend fun get(uri: String): MediaStateEntity?

    @Query("SELECT * FROM media_state WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<MediaStateEntity>>

    @Upsert
    suspend fun upsert(state: MediaStateEntity)

    @Query(
        """
        UPDATE media_state
        SET positionMs = :positionMs, durationMs = :durationMs, lastPlayedAt = :now
        WHERE uri = :uri
        """
    )
    suspend fun updatePosition(uri: String, positionMs: Long, durationMs: Long, now: Long)

    @Query("UPDATE media_state SET isFavorite = NOT isFavorite WHERE uri = :uri")
    suspend fun toggleFavorite(uri: String)

    @Query("DELETE FROM media_state WHERE lastPlayedAt > 0")
    suspend fun clearHistory()
}
