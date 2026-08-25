package com.vortex.player.playback

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.db.MediaStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueAutoplayEngineTest {
    @Test
    fun recommendationsAreLocalExcludeQueuedAndPreferRelatedMedia() {
        val current = entry(1, "Actual", artist = "Vortex", album = "Uno")
        val sameArtist = entry(2, "Misma artista", artist = "Vortex", album = "Dos")
        val favorite = entry(3, "Favorita", artist = "Otro", album = "Tres")
        val unrelated = entry(4, "Distinta", artist = "Nadie", album = "Cuatro")
        val queued = listOf(PlaybackQueueItem("current", current))
        val library = LibraryState(
            loading = false,
            entries = listOf(current, unrelated, favorite, sameArtist),
            states = mapOf(
                favorite.uri.toString() to MediaStateEntity(favorite.uri.toString(), isFavorite = true)
            )
        )

        val result = QueueAutoplayEngine.recommend(library, queued, 0, limit = 3, nowMs = 100_000L)

        assertEquals(sameArtist.uri, result.first().uri)
        assertFalse(result.any { it.uri == current.uri })
        assertEquals(3, result.size)
    }

    private fun entry(id: Long, title: String, artist: String, album: String) = MediaEntry(
        id = id,
        uri = Uri.parse("content://media/$id"),
        title = title,
        displayName = "$title.mp3",
        durationMs = 180_000L,
        sizeBytes = 1L,
        mimeType = "audio/mpeg",
        width = 0,
        height = 0,
        folderPath = "/Music",
        folderName = "Music",
        dateAddedSec = id,
        isVideo = false,
        artist = artist,
        album = album
    )
}
