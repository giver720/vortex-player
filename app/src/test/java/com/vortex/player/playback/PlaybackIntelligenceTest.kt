package com.vortex.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntelligenceTest {

    @Test
    fun `local files use a short file cache and hardware first`() {
        val plan = PlaybackIntelligencePlanner.plan(
            uri = "content://media/external/video/media/42",
            mimeType = "video/mp4",
            lowRamDevice = false
        )

        assertEquals(PlaybackSourceKind.LOCAL, plan.source)
        assertEquals(DecoderMode.HARDWARE, plan.decoder)
        assertEquals(350, plan.cacheMs)
        assertEquals(listOf(":file-caching=350"), plan.mediaOptions)
    }

    @Test
    fun `progressive http gets reconnect and medium network cache`() {
        val plan = PlaybackIntelligencePlanner.plan(
            uri = "https://cdn.example.org/movie.mp4",
            mimeType = "video/mp4",
            lowRamDevice = false
        )

        assertEquals(PlaybackSourceKind.HTTP, plan.source)
        assertEquals(2_500, plan.cacheMs)
        assertTrue(plan.mediaOptions.contains(":network-caching=2500"))
        assertTrue(plan.mediaOptions.contains(":http-reconnect"))
    }

    @Test
    fun `hls is detected by extension or mime and receives the largest cache`() {
        val byExtension = PlaybackIntelligencePlanner.plan(
            "https://example.org/live/channel.m3u8?token=x",
            null,
            false
        )
        val byMime = PlaybackIntelligencePlanner.plan(
            "https://example.org/manifest",
            "application/x-mpegURL",
            false
        )

        assertEquals(PlaybackSourceKind.HLS, byExtension.source)
        assertEquals(PlaybackSourceKind.HLS, byMime.source)
        assertEquals(4_000, byMime.cacheMs)
        assertTrue(byMime.mediaOptions.contains(":live-caching=4000"))
    }

    @Test
    fun `rtsp forces tcp and low ram profile reduces memory pressure`() {
        val regular = PlaybackIntelligencePlanner.plan("rtsp://camera.local/live", null, false)
        val lowRam = PlaybackIntelligencePlanner.plan("rtsp://camera.local/live", null, true)

        assertEquals(PlaybackSourceKind.RTSP, regular.source)
        assertEquals(1_800, regular.cacheMs)
        assertEquals(1_200, lowRam.cacheMs)
        assertTrue(lowRam.mediaOptions.contains(":rtsp-tcp"))
    }

    @Test
    fun `first recovery leaves hardware and second keeps safe software`() {
        val first = PlaybackRecoveryPolicy.decide(DecoderMode.HARDWARE, 0)
        val second = PlaybackRecoveryPolicy.decide(first.decoder, 1)

        assertTrue(first.shouldRetry)
        assertEquals(DecoderMode.SOFTWARE, first.decoder)
        assertTrue(second.shouldRetry)
        assertEquals(DecoderMode.SOFTWARE, second.decoder)
    }

    @Test
    fun `recovery policy stops after two attempts`() {
        val exhausted = PlaybackRecoveryPolicy.decide(
            DecoderMode.SOFTWARE,
            PlaybackRecoveryPolicy.MAX_AUTOMATIC_RECOVERIES
        )

        assertFalse(exhausted.shouldRetry)
        assertEquals(DecoderMode.SOFTWARE, exhausted.decoder)
    }

    @Test
    fun `resume opens cleanly and seeks only after VLC is playing`() {
        val resume = PlaybackStartPolicy.plan(91_250L)
        val beginning = PlaybackStartPolicy.plan(0L)

        assertEquals(0L, resume.mediaStartPositionMs)
        assertEquals(91_250L, resume.seekAfterPlayingMs)
        assertEquals(0L, beginning.mediaStartPositionMs)
        assertEquals(null, beginning.seekAfterPlayingMs)
    }

    @Test
    fun `audio progress without displayed video triggers hardware fallback`() {
        val blackVideo = VideoLivenessSample(
            playbackActive = true,
            videoEnabled = true,
            outputAttached = true,
            hasVideoTrack = true,
            statsAvailable = true,
            decoder = DecoderMode.HARDWARE,
            elapsedWithoutDisplayedFrameMs = VideoLivenessPolicy.FRAME_TIMEOUT_MS,
            timelineAdvanceMs = VideoLivenessPolicy.MIN_TIMELINE_ADVANCE_MS
        )

        assertTrue(VideoLivenessPolicy.shouldFallbackToSoftware(blackVideo))
    }

    @Test
    fun `video watchdog ignores audio only detached and software playback`() {
        val base = VideoLivenessSample(
            playbackActive = true,
            videoEnabled = true,
            outputAttached = true,
            hasVideoTrack = true,
            statsAvailable = true,
            decoder = DecoderMode.HARDWARE,
            elapsedWithoutDisplayedFrameMs = 20_000L,
            timelineAdvanceMs = 10_000L
        )

        assertFalse(VideoLivenessPolicy.shouldFallbackToSoftware(base.copy(videoEnabled = false)))
        assertFalse(VideoLivenessPolicy.shouldFallbackToSoftware(base.copy(outputAttached = false)))
        assertFalse(VideoLivenessPolicy.shouldFallbackToSoftware(base.copy(hasVideoTrack = false)))
        assertFalse(
            VideoLivenessPolicy.shouldFallbackToSoftware(base.copy(decoder = DecoderMode.SOFTWARE))
        )
    }

    @Test
    fun `video watchdog waits for stats grace and timeline evidence`() {
        val base = VideoLivenessSample(
            playbackActive = true,
            videoEnabled = true,
            outputAttached = true,
            hasVideoTrack = true,
            statsAvailable = true,
            decoder = DecoderMode.HARDWARE,
            elapsedWithoutDisplayedFrameMs = VideoLivenessPolicy.FRAME_TIMEOUT_MS,
            timelineAdvanceMs = VideoLivenessPolicy.MIN_TIMELINE_ADVANCE_MS
        )

        assertFalse(VideoLivenessPolicy.shouldFallbackToSoftware(base.copy(statsAvailable = false)))
        assertFalse(
            VideoLivenessPolicy.shouldFallbackToSoftware(
                base.copy(elapsedWithoutDisplayedFrameMs = VideoLivenessPolicy.FRAME_TIMEOUT_MS - 1)
            )
        )
        assertFalse(
            VideoLivenessPolicy.shouldFallbackToSoftware(
                base.copy(timelineAdvanceMs = VideoLivenessPolicy.MIN_TIMELINE_ADVANCE_MS - 1)
            )
        )
    }

    @Test
    fun `diagnostics formats unavailable and known resolution`() {
        assertEquals("—", PlaybackDiagnostics().resolutionLabel)
        assertEquals("3840 × 2160", PlaybackDiagnostics(width = 3840, height = 2160).resolutionLabel)
    }
}
