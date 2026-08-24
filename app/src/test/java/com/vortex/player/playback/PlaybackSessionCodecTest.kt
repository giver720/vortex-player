package com.vortex.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionCodecTest {

    @Test
    fun `round trip preserves a large queue and playback state`() {
        val snapshot = PlaybackSessionSnapshot(
            entries = List(1_000) { index -> entry(index) },
            currentIndex = 731,
            positionMs = 91_337L,
            audioOnly = true,
            speed = 1.35f,
            repeat = RepeatMode.ALL,
            shuffle = true,
            updatedAtMs = 123_456L
        )

        val restored = requireNotNull(PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(snapshot)))

        assertEquals(1_000, restored.entries.size)
        assertEquals(snapshot.currentIndex, restored.currentIndex)
        assertEquals(snapshot.positionMs, restored.positionMs)
        assertEquals(snapshot.audioOnly, restored.audioOnly)
        assertEquals(snapshot.speed, restored.speed)
        assertEquals(snapshot.repeat, restored.repeat)
        assertEquals(snapshot.shuffle, restored.shuffle)
        assertEquals("content://media/731", restored.entries[731].uri)
        assertEquals("Artista 731", restored.entries[731].artist)
    }

    @Test
    fun `decode clamps invalid index position and speed`() {
        val original = PlaybackSessionSnapshot(
            entries = listOf(entry(1), entry(2)),
            currentIndex = 99,
            positionMs = -50L,
            audioOnly = false,
            speed = 50f,
            repeat = RepeatMode.ONE,
            shuffle = false,
            updatedAtMs = 1L
        )

        val restored = requireNotNull(PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(original)))

        assertEquals(1, restored.currentIndex)
        assertEquals(0L, restored.positionMs)
        assertEquals(PlaybackSessionSnapshot.MAX_SPEED, restored.speed)
    }

    @Test
    fun `decode skips malformed entries but keeps valid ones`() {
        val json = """
            {
              "version": 1,
              "currentIndex": 1,
              "entries": [
                {"uri": ""},
                {"uri": "file:///music/song.mp3", "title": "Song", "isVideo": false}
              ]
            }
        """.trimIndent()

        val restored = requireNotNull(PlaybackSessionCodec.decode(json))

        assertEquals(1, restored.entries.size)
        assertEquals(0, restored.currentIndex)
        assertEquals("Song", restored.entries.single().title)
    }

    @Test
    fun `decode rejects corrupt unsupported and empty snapshots`() {
        assertNull(PlaybackSessionCodec.decode("not json"))
        assertNull(PlaybackSessionCodec.decode("{\"version\":99,\"entries\":[]}"))
        assertNull(PlaybackSessionCodec.decode("{\"version\":1,\"entries\":[]}"))
    }

    @Test
    fun `nullable metadata survives serialization`() {
        val snapshot = PlaybackSessionSnapshot(
            entries = listOf(entry(0).copy(artist = null, album = null)),
            currentIndex = 0,
            positionMs = 0,
            audioOnly = false,
            speed = 1f,
            repeat = RepeatMode.OFF,
            shuffle = false,
            updatedAtMs = 0
        )

        val restored = requireNotNull(PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(snapshot)))

        assertNull(restored.entries.single().artist)
        assertNull(restored.entries.single().album)
        assertTrue(PlaybackSessionCodec.encode(snapshot).contains("\"artist\":null"))
    }

    private fun entry(index: Int) = PersistedMediaEntry(
        id = index.toLong(),
        uri = "content://media/$index",
        title = "Pista $index",
        displayName = "pista-$index.mp3",
        durationMs = 180_000L + index,
        sizeBytes = 4_000_000L + index,
        mimeType = "audio/mpeg",
        width = 0,
        height = 0,
        folderPath = "/Music",
        folderName = "Music",
        dateAddedSec = 123L,
        isVideo = false,
        artist = "Artista $index",
        album = "Álbum"
    )
}
