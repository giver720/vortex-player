package com.vortex.player.spotify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyRequestPolicyTest {
    @Test
    fun rateLimitWaitIsNeverShortenedAndCannotOverflow() {
        assertEquals(25_000L, SpotifyRetryPolicy.delayMs(429, "25", 0))
        assertNull(SpotifyRetryPolicy.delayMs(429, "120", 0))
        assertNull(SpotifyRetryPolicy.delayMs(429, Long.MAX_VALUE.toString(), 0))
        assertNull(SpotifyRetryPolicy.delayMs(429, "invalid", 0))
        assertNull(SpotifyRetryPolicy.delayMs(429, "-1", 0))
        assertNull(SpotifyRetryPolicy.delayMs(429, "2", 2))
    }

    @Test
    fun onlyTransientFailuresAreRetried() {
        assertEquals(700L, SpotifyRetryPolicy.delayMs(503, null, 0))
        assertEquals(1_400L, SpotifyRetryPolicy.delayMs(-1, null, 1))
        listOf(200, 400, 401, 403, 404).forEach {
            assertNull(SpotifyRetryPolicy.delayMs(it, null, 0))
        }
    }

    @Test
    fun cursorConsumesRawEntriesIncludingNullTracks() {
        val page = JSONObject().put("offset", 50).put("total", 200).put("limit", 0)
            .put("next", "next").put("items", JSONArray().put(JSONObject.NULL).put(JSONObject.NULL))
        assertEquals(52, SpotifyPageCursor.nextOffset(page, 50))
    }

    @Test(expected = IllegalStateException::class)
    fun nonAdvancingPageCannotReplaceTheCachedLibrary() {
        SpotifyPageCursor.nextOffset(JSONObject().put("total", 100)
            .put("next", "next").put("items", JSONArray()), 0)
    }

    @Test(expected = IllegalStateException::class)
    fun truncatedResponseIsNotTreatedAsACompleteSync() {
        SpotifyPageCursor.nextOffset(JSONObject().put("total", 100)
            .put("next", JSONObject.NULL).put("items", JSONArray()), 0)
    }
}
