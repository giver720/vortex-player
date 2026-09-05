package com.vortex.player.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyCatalogPagerTest {
    @Test
    fun largePlaylistContinuesAfterAnEntireRemovedPageAndPreservesPositions() = runBlocking {
        val offsets = mutableListOf<Int>()
        val emitted = mutableListOf<SpotifyTrack>()
        val result = SpotifyCatalogPager.load(SpotifyKind.PLAYLIST, "Lista", null, fetch = { offset ->
            offsets += offset
            val items = JSONArray()
            repeat(minOf(50, 1_025 - offset)) { index ->
                items.put(if (offset == 50) JSONObject.NULL else {
                    JSONObject().put(if (offset % 100 == 0) "item" else "track", track(offset + index))
                })
            }
            page(offset, 1_025, items)
        }, onPage = { _, items -> emitted += items })
        assertTrue(result.complete)
        assertEquals((0..1_000 step 50).toList(), offsets)
        assertEquals(975, result.tracks.size)
        assertEquals(1_025, result.total)
        assertEquals(101, result.tracks[50].trackNumber)
        assertEquals(result.tracks, emitted)
        assertEquals(1_025, result.tracks.last().trackNumber)
    }

    @Test
    fun interruptedPaginationReturnsOnlyAlreadyEmittedTracks() = runBlocking {
        val emitted = mutableListOf<SpotifyTrack>()
        val result = SpotifyCatalogPager.load(SpotifyKind.PLAYLIST, "Lista", null, fetch = { offset ->
            if (offset == 0) page(0, 3, JSONArray().put(JSONObject().put("item", track(0)))) else null
        }, onPage = { _, items -> emitted += items })
        assertFalse(result.complete)
        assertEquals(3, result.total)
        assertEquals(emitted, result.tracks)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun emptyCollectionDoesNotInventATrack() = runBlocking {
        val result = SpotifyCatalogPager.load(SpotifyKind.ALBUM, "Vacío", null,
            fetch = { page(0, 0, JSONArray()) })
        assertTrue(result.complete)
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun keepsIntentionalDuplicatesAndSkipsEpisodesLocalTracksAndNullIds() = runBlocking {
        val duplicate = track(7)
        val items = JSONArray()
            .put(JSONObject().put("item", duplicate))
            .put(JSONObject().put("track", track(2).put("type", "episode")))
            .put(JSONObject().put("track", track(3).put("is_local", true)))
            .put(JSONObject().put("track", duplicate))
            .put(JSONObject().put("track", track(5).put("id", JSONObject.NULL)))
        val result = SpotifyCatalogPager.load(SpotifyKind.PLAYLIST, "Lista", null,
            fetch = { page(0, 5, items) })
        assertEquals(listOf(1, 4, 5), result.tracks.map { it.trackNumber })
        assertEquals(listOf("id7", "id7", null), result.tracks.map { it.id })
    }

    @Test
    fun repeatedServerPageIsReportedAsPartialWithoutDuplicateEmission() = runBlocking {
        val result = SpotifyCatalogPager.load(SpotifyKind.ALBUM, "Álbum", null,
            fetch = { page(0, 3, JSONArray().put(track(0))) })
        assertFalse(result.complete)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAPartialSuccess() = runBlocking {
        try {
            spotifyResult {
                SpotifyCatalogPager.load(SpotifyKind.ALBUM, "Álbum", null,
                    fetch = { throw CancellationException("cancelled") })
            }
            fail("La cancelación debe propagarse")
        } catch (_: CancellationException) { }
    }

    private fun page(offset: Int, total: Int, items: JSONArray) = JSONObject()
        .put("offset", offset).put("total", total).put("items", items)

    private fun track(index: Int) = JSONObject()
        .put("id", "id$index").put("name", "Pista $index").put("type", "track")
        .put("duration_ms", 180_000)
        .put("artists", JSONArray().put(JSONObject().put("name", "Artista")))
}
