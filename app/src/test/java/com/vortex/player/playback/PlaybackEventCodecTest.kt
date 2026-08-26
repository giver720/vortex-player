package com.vortex.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEventCodecTest {

    @Test
    fun `event replaces media identity with a stable hash`() {
        val secret = "content://media/external/video/42?token=private"
        val encoded = PlaybackEventCodec.encode(
            PlaybackEvent(PlaybackEventType.OPEN, mediaIdentity = secret)
        )

        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("token=private"))
        assertTrue(encoded.contains(PlaybackEventCodec.mediaHash(secret)))
    }

    @Test
    fun `detail token removes arbitrary characters and bounds its size`() {
        val encoded = PlaybackEventCodec.encode(
            PlaybackEvent(
                type = PlaybackEventType.ERROR,
                detailCode = "decoder <failed> ${"x".repeat(400)}"
            )
        )

        assertFalse(encoded.contains("<failed>"))
        assertTrue(encoded.length < 300)
    }
}
