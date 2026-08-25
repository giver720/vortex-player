package com.vortex.player.data

import com.vortex.player.data.db.PlaylistItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistToolsTest {
    private fun item(id: Long, title: String, position: Int, duration: Long = 0L) =
        PlaylistItemEntity(
            id = id,
            playlistId = 1,
            uri = "file:///Music/$title.mp3",
            title = title,
            artist = if (title == "B") "Zulu" else "Alpha",
            durationMs = duration,
            isVideo = false,
            position = position
        )

    @Test
    fun `m3u preserves order metadata and removes duplicate uris`() {
        val parsed = M3uCodec.decode(
            """
            #EXTM3U
            #PLAYLIST:Viaje
            #EXTINF:123,Artista - Canción
            file:///Music/song.mp3
            file:///Music/song.mp3
            #EXTINF:-1,Otra
            https://example.test/live.mp3
            """.trimIndent()
        )

        assertEquals("Viaje", parsed.name)
        assertEquals(2, parsed.entries.size)
        assertEquals(123L, parsed.entries.first().durationSeconds)
        assertEquals("Artista - Canción", parsed.entries.first().title)
    }

    @Test
    fun `organizer sorts stable ids and moves without losing items`() {
        val resolved = listOf(item(1, "B", 0), item(2, "A", 1)).map {
            PlaylistResolvedItem(it, null)
        }

        assertEquals(listOf(2L, 1L), PlaylistOrganizer.sortedIds(resolved, PlaylistSortMode.TITLE))
        assertEquals(listOf(2L, 1L), PlaylistOrganizer.move(listOf(1L, 2L), 0, 1))
        assertEquals(listOf(1L, 2L), PlaylistOrganizer.move(listOf(1L, 2L), -1, 1))
    }

    @Test
    fun `stats keep unavailable items visible`() {
        val stored = item(1, "Ausente", 0, 5_000L)
        val stats = PlaylistOrganizer.stats(listOf(PlaylistResolvedItem(stored, null)))

        assertEquals(1, stats.count)
        assertEquals(1, stats.missing)
        assertEquals(5_000L, stats.durationMs)
        assertFalse(PlaylistResolvedItem(stored, null).available)
        assertTrue(PlaylistResolvedItem(stored, null).title.isNotBlank())
    }

    @Test
    fun `m3u export is extended and round trips stored metadata`() {
        val resolved = listOf(
            PlaylistResolvedItem(item(7, "Tema", 0, 65_000L).copy(artist = "Artista"), null)
        )

        val encoded = M3uCodec.encode("Mi lista", resolved)
        val decoded = M3uCodec.decode(encoded)

        assertTrue(encoded.startsWith("#EXTM3U"))
        assertEquals("Mi lista", decoded.name)
        assertEquals("Artista - Tema", decoded.entries.single().title)
        assertEquals(65L, decoded.entries.single().durationSeconds)
    }

    @Test
    fun `smart rule parser is forward compatible`() {
        assertEquals(SmartPlaylistRule.RECENT, SmartPlaylistRule.parse("RECENT"))
        assertEquals(null, SmartPlaylistRule.parse("FUTURE_RULE"))
        assertEquals(null, SmartPlaylistRule.parse(null))
    }
}
