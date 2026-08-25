package com.vortex.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSourcesTest {

    @Test
    fun `web address without scheme becomes https and hls is detected`() {
        val parsed = NetworkSourceParser.parse("media.example.org/live/channel.m3u8?quality=hd")
            as NetworkSourceParseResult.Valid

        assertTrue(parsed.draft.url.startsWith("https://"))
        assertEquals(NetworkProtocol.HLS, parsed.draft.protocol)
        assertTrue(parsed.draft.canPersist)
    }

    @Test
    fun `credentials and access tokens can play but cannot persist`() {
        val credentials = NetworkSourceParser.parse("rtsp://user:pass@camera.local/live")
            as NetworkSourceParseResult.Valid
        val token = NetworkSourceParser.parse("https://video.example/live.m3u8?access_token=secret")
            as NetworkSourceParseResult.Valid

        assertEquals(NetworkProtocol.RTSP, credentials.draft.protocol)
        assertFalse(credentials.draft.canPersist)
        assertFalse(token.draft.canPersist)
    }

    @Test
    fun `unsupported and incomplete sources are rejected`() {
        assertTrue(NetworkSourceParser.parse("file:///storage/movie.mp4") is NetworkSourceParseResult.Invalid)
        assertTrue(NetworkSourceParser.parse("rtsp:///live") is NetworkSourceParseResult.Invalid)
    }

    @Test
    fun `audio stream extension selects audio mode`() {
        val parsed = NetworkSourceParser.parse("https://radio.example.org/live.opus")
            as NetworkSourceParseResult.Valid

        assertEquals(NetworkMediaKind.AUDIO, parsed.draft.mediaKind)
    }

    @Test
    fun `history deduplicates sources and preserves favorite state`() {
        val draft = (NetworkSourceParser.parse("https://example.org/movie.mp4", "Película")
            as NetworkSourceParseResult.Valid).draft
        val favorite = NetworkSourceLibraryPolicy.saveFavorite(emptyList(), draft, 10L)
        val reopened = NetworkSourceLibraryPolicy.recordOpened(favorite, draft.copy(title = "Nueva"), 20L)

        assertEquals(1, reopened.size)
        assertTrue(reopened.single().favorite)
        assertEquals("Nueva", reopened.single().title)
        assertEquals(20L, reopened.single().lastOpenedAtMs)
    }

    @Test
    fun `clearing recent keeps favorites and codec round trips`() {
        val favoriteDraft = (NetworkSourceParser.parse("rtsp://camera.local/live", "Cámara")
            as NetworkSourceParseResult.Valid).draft
        val recentDraft = (NetworkSourceParser.parse("https://example.org/video.mp4", "Vídeo")
            as NetworkSourceParseResult.Valid).draft
        var values = NetworkSourceLibraryPolicy.saveFavorite(emptyList(), favoriteDraft, 1L)
        values = NetworkSourceLibraryPolicy.recordOpened(values, recentDraft, 2L)

        val restored = NetworkSourceCodec.decode(NetworkSourceCodec.encode(values))
        val cleared = NetworkSourceLibraryPolicy.clearRecent(restored)

        assertEquals(2, restored.size)
        assertEquals(1, cleared.size)
        assertTrue(cleared.single().favorite)
        assertEquals(NetworkProtocol.RTSP, cleared.single().protocol)
    }

    @Test
    fun `library applies deterministic recent limit`() {
        var values = emptyList<NetworkSource>()
        repeat(NetworkSourceLibraryPolicy.MAX_RECENT + 5) { index ->
            val draft = (NetworkSourceParser.parse("https://example.org/video-$index.mp4")
                as NetworkSourceParseResult.Valid).draft
            values = NetworkSourceLibraryPolicy.recordOpened(values, draft, index.toLong())
        }

        assertEquals(NetworkSourceLibraryPolicy.MAX_RECENT, values.size)
        assertTrue(values.none { it.url.endsWith("video-0.mp4") })
        assertTrue(values.any { it.url.endsWith("video-29.mp4") })
    }
}
