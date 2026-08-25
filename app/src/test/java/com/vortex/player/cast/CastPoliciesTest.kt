package com.vortex.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPoliciesTest {

    @Test
    fun `http and hls are sent directly while local content uses bridge`() {
        val http = CastMediaPolicy.plan("https://cdn.example/movie.mp4", "video/mp4", true, 10L)
        val hls = CastMediaPolicy.plan("https://cdn.example/live.m3u8", null, true, 0L)
        val local = CastMediaPolicy.plan("content://media/external/video/42", "video/mp4", true, 10L)

        assertEquals(CastDelivery.DIRECT_HTTP, http.delivery)
        assertEquals(CastDelivery.DIRECT_HTTP, hls.delivery)
        assertEquals(CastStreamKind.LIVE, hls.streamKind)
        assertEquals("application/x-mpegurl", hls.contentType)
        assertEquals(CastDelivery.LOCAL_BRIDGE, local.delivery)
    }

    @Test
    fun `rtsp is rejected by default cast receiver`() {
        val plan = CastMediaPolicy.plan("rtsp://camera.local/live", null, true, 0L)

        assertEquals(CastDelivery.UNSUPPORTED, plan.delivery)
        assertTrue(plan.reason.orEmpty().contains("RTSP"))
    }

    @Test
    fun `hls with known duration is vod and wildcard mime uses extension`() {
        val vod = CastMediaPolicy.plan(
            "https://cdn.example/episode.m3u8",
            "video/*",
            true,
            120_000L
        )
        val audio = CastMediaPolicy.plan("file:///Music/song.mp3", null, false, 10L)

        assertEquals(CastStreamKind.BUFFERED, vod.streamKind)
        assertEquals("application/x-mpegurl", vod.contentType)
        assertEquals("audio/mpeg", audio.contentType)
    }

    @Test
    fun `range policy accepts seeks open ends and suffixes`() {
        assertEquals(HttpRangeResult.Full, HttpRangePolicy.parse(null, 1_000L))
        assertEquals(
            HttpRangeResult.Partial(HttpByteRange(100L, 199L)),
            HttpRangePolicy.parse("bytes=100-199", 1_000L)
        )
        assertEquals(
            HttpRangeResult.Partial(HttpByteRange(900L, 999L)),
            HttpRangePolicy.parse("bytes=-100", 1_000L)
        )
        assertEquals(
            HttpRangeResult.Partial(HttpByteRange(800L, 999L)),
            HttpRangePolicy.parse("bytes=800-", 1_000L)
        )
    }

    @Test
    fun `range policy rejects malformed or unsatisfiable requests`() {
        assertEquals(HttpRangeResult.Invalid, HttpRangePolicy.parse("items=0-2", 100L))
        assertEquals(HttpRangeResult.Invalid, HttpRangePolicy.parse("bytes=100-", 100L))
        assertEquals(HttpRangeResult.Invalid, HttpRangePolicy.parse("bytes=80-20", 100L))
        assertEquals(HttpRangeResult.Invalid, HttpRangePolicy.parse("bytes=0-1,4-5", 100L))
    }
}
