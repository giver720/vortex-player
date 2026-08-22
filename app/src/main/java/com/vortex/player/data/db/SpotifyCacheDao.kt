package com.vortex.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SpotifyCacheDao {

    @Query("SELECT * FROM spotify_playlists WHERE accountId = :accountId ORDER BY name COLLATE NOCASE")
    abstract fun observePlaylists(accountId: String): Flow<List<SpotifyPlaylistEntity>>

    @Query(
        "SELECT * FROM spotify_tracks WHERE accountId = :accountId AND playlistId = :playlistId " +
            "ORDER BY position"
    )
    abstract fun observeTracks(accountId: String, playlistId: String): Flow<List<SpotifyTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPlaylists(items: List<SpotifyPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTracks(items: List<SpotifyTrackEntity>)

    @Query("DELETE FROM spotify_playlists WHERE accountId = :accountId")
    abstract suspend fun deletePlaylists(accountId: String)

    @Query("DELETE FROM spotify_tracks WHERE accountId = :accountId AND playlistId = :playlistId")
    abstract suspend fun deleteTracks(accountId: String, playlistId: String)

    @Query("DELETE FROM spotify_tracks WHERE accountId = :accountId")
    abstract suspend fun deleteAccountTracks(accountId: String)

    @Transaction
    open suspend fun replacePlaylists(accountId: String, items: List<SpotifyPlaylistEntity>) {
        deletePlaylists(accountId)
        if (items.isNotEmpty()) insertPlaylists(items)
    }

    @Transaction
    open suspend fun replaceTracks(
        accountId: String,
        playlistId: String,
        items: List<SpotifyTrackEntity>
    ) {
        deleteTracks(accountId, playlistId)
        if (items.isNotEmpty()) insertTracks(items)
    }

    @Transaction
    open suspend fun clearAccount(accountId: String) {
        deleteAccountTracks(accountId)
        deletePlaylists(accountId)
    }
}
