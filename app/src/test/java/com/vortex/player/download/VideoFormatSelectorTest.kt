package com.vortex.player.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatSelectorTest {

    @Test
    fun `mp4 prioritizes avc video and m4a audio`() {
        val selector = VideoQuality.UHD.formatSelector(VideoContainer.MP4)

        assertTrue(
            selector.startsWith(
                "bestvideo[height<=2160][vcodec^=avc1]+bestaudio[ext=m4a]/"
            )
        )
    }

    @Test
    fun `mkv and webm keep maximum codec selection`() {
        val mkv = VideoQuality.BEST.formatSelector(VideoContainer.MKV)
        val webm = VideoQuality.FHD.formatSelector(VideoContainer.WEBM)

        assertFalse(mkv.contains("vcodec^=avc1"))
        assertFalse(webm.contains("vcodec^=avc1"))
    }
}
