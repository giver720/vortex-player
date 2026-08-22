package com.vortex.player.spotify

import com.vortex.player.data.db.SpotifyTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLocalMatcherTest {

    @Test
    fun matchesAccentsCaseAndCloseDurationAsExact() {
        val match = SpotifyLocalMatcher.best(
            track(title = "Canción Azul", artist = "Artista Uno", album = "Álbum", durationMs = 180_000),
            listOf(
                local(title = "CANCION AZUL", artist = "artista uno", album = "album", durationMs = 180_500)
            )
        )

        assertEquals(LocalMatchQuality.EXACT, match?.quality)
        assertTrue((match?.confidence ?: 0f) >= 0.92f)
    }

    @Test
    fun returnsLikelyWhenMetadataMatchesButDurationIsLessPrecise() {
        val match = SpotifyLocalMatcher.best(
            track(title = "Luz de Luna", artist = "Mar Abierto", album = "Noches", durationMs = 200_000),
            listOf(
                local(title = "Luz de Luna", artist = "Mar Abierto", album = null, durationMs = 207_000)
            )
        )

        assertEquals(LocalMatchQuality.LIKELY, match?.quality)
    }

    @Test
    fun rejectsDifferentArtistAndDuration() {
        val match = SpotifyLocalMatcher.best(
            track(title = "Intro", artist = "Artista Original", durationMs = 90_000),
            listOf(local(title = "Intro", artist = "Otra Persona", durationMs = 210_000))
        )

        assertNull(match)
    }

    @Test
    fun rejectsAmbiguousLocalVersions() {
        val spotify = track(title = "Horizonte", artist = "Norte", durationMs = 180_000)
        val match = SpotifyLocalMatcher.best(
            spotify,
            listOf(
                local(uri = "content://audio/1", title = "Horizonte", artist = "Norte", durationMs = 180_000),
                local(uri = "content://audio/2", title = "Horizonte", artist = "Norte", durationMs = 181_000)
            )
        )

        assertNull(match)
    }

    private fun track(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long
    ) = SpotifyTrackEntity(
        accountId = "account",
        playlistId = "playlist",
        position = 0,
        spotifyId = "spotify-track",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        imageUrl = null,
        spotifyUrl = null,
        isrc = null
    )

    private fun local(
        uri: String = "content://audio/local",
        title: String,
        artist: String,
        album: String? = "",
        durationMs: Long
    ) = LocalAudioCandidate(
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )
}
