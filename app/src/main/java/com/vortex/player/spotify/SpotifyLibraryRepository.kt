package com.vortex.player.spotify

import android.content.Context
import com.vortex.player.data.db.SpotifyCacheDao
import com.vortex.player.data.db.SpotifyPlaylistEntity
import com.vortex.player.data.db.SpotifyTrackEntity
import com.vortex.player.data.db.VortexDatabase
import kotlinx.coroutines.flow.Flow

class SpotifyLibraryRepository private constructor(
    private val context: Context,
    private val dao: SpotifyCacheDao
) {
    fun playlists(accountId: String): Flow<List<SpotifyPlaylistEntity>> =
        dao.observePlaylists(accountId)

    fun tracks(accountId: String, playlistId: String): Flow<List<SpotifyTrackEntity>> =
        dao.observeTracks(accountId, playlistId)

    suspend fun syncPlaylists(accountId: String): Result<Int> = runCatching {
        val all = mutableListOf<SpotifyPlaylistEntity>()
        var offset = 0
        val updatedAt = System.currentTimeMillis()
        do {
            val page = SpotifyWebApi.currentUserPlaylists(context, offset).getOrThrow()
            all += page.items.filter { it.id.isNotBlank() }.map { item ->
                SpotifyPlaylistEntity(
                    accountId = accountId,
                    id = item.id,
                    name = item.name,
                    owner = item.owner,
                    imageUrl = item.imageUrl,
                    itemCount = item.itemCount,
                    spotifyUrl = item.spotifyUrl,
                    snapshotId = item.snapshotId,
                    updatedAt = updatedAt
                )
            }
            val next = page.nextOffset
            offset = next?.takeIf { it > offset } ?: -1
        } while (offset >= 0)
        dao.replacePlaylists(accountId, all)
        all.size
    }

    suspend fun syncPlaylistTracks(accountId: String, playlistId: String): Result<Int> =
        runCatching {
            val all = mutableListOf<SpotifyTrackEntity>()
            var offset = 0
            do {
                val page = SpotifyWebApi.playlistItems(context, playlistId, offset).getOrThrow()
                all += page.items.map { item ->
                    SpotifyTrackEntity(
                        accountId = accountId,
                        playlistId = playlistId,
                        position = item.position,
                        spotifyId = item.id,
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs,
                        imageUrl = item.imageUrl,
                        spotifyUrl = item.spotifyUrl,
                        isrc = item.isrc
                    )
                }
                val next = page.nextOffset
                offset = next?.takeIf { it > offset } ?: -1
            } while (offset >= 0)
            dao.replaceTracks(accountId, playlistId, all)
            all.size
        }

    suspend fun clearAccount(accountId: String) = dao.clearAccount(accountId)

    companion object {
        @Volatile
        private var instance: SpotifyLibraryRepository? = null

        fun get(context: Context): SpotifyLibraryRepository =
            instance ?: synchronized(this) {
                instance ?: SpotifyLibraryRepository(
                    context.applicationContext,
                    VortexDatabase.get(context).spotifyCacheDao()
                ).also { instance = it }
            }
    }
}
