package com.vortex.player.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "spotify_playlists",
    primaryKeys = ["accountId", "id"],
    indices = [Index(value = ["accountId", "name"])]
)
data class SpotifyPlaylistEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val owner: String,
    val imageUrl: String?,
    val itemCount: Int,
    val spotifyUrl: String?,
    val snapshotId: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "spotify_tracks",
    primaryKeys = ["accountId", "playlistId", "position"],
    indices = [Index(value = ["accountId", "playlistId"]), Index(value = ["spotifyId"])]
)
data class SpotifyTrackEntity(
    val accountId: String,
    val playlistId: String,
    val position: Int,
    val spotifyId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val imageUrl: String?,
    val spotifyUrl: String?,
    val isrc: String?
)
