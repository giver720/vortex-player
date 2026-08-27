package com.vortex.player.download

import com.vortex.player.data.db.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {

    @Test
    fun nightSchedulesHandleMidnightBoundaries() {
        assertTrue(DownloadSchedule.ANYTIME.allows(12 * 60))
        assertTrue(DownloadSchedule.NIGHT.allows(22 * 60))
        assertTrue(DownloadSchedule.NIGHT.allows(6 * 60 + 59))
        assertFalse(DownloadSchedule.NIGHT.allows(7 * 60))
        assertFalse(DownloadSchedule.NIGHT.allows(21 * 60 + 59))
        assertTrue(DownloadSchedule.MIDNIGHT.allows(0))
        assertFalse(DownloadSchedule.MIDNIGHT.allows(7 * 60))
    }

    @Test
    fun retryBackoffGrowsAndCaps() {
        assertEquals(15_000L, DownloadRetryPolicy.delayMs(1))
        assertEquals(60_000L, DownloadRetryPolicy.delayMs(2))
        assertEquals(5 * 60_000L, DownloadRetryPolicy.delayMs(3))
        assertEquals(30 * 60_000L, DownloadRetryPolicy.delayMs(99))
    }

    @Test
    fun permanentErrorsAreNotRetriedAndFormatErrorsUseFallback() {
        assertFalse(DownloadRetryPolicy.isRetryable("ERROR: Private video"))
        assertFalse(DownloadRetryPolicy.isRetryable("Libera espacio y reintenta"))
        assertFalse(
            DownloadRetryPolicy.isRetryable(
                "ERROR: [DRM] The requested site is known to use DRM protection"
            )
        )
        assertFalse(DownloadRetryPolicy.isRetryable(YoutubeAutomation.RECOVERY_FAILED))
        assertFalse(DownloadRetryPolicy.isRetryable(YoutubeAutomation.AUTH_RECOVERY_FAILED))
        assertTrue(DownloadRetryPolicy.isRetryable("HTTP Error 503"))
        assertTrue(
            DownloadRetryPolicy.isFormatFailure("Requested format is not available")
        )
    }

    @Test
    fun sourceLimitsAreClampedAndSpotifySearchesCountAsYoutube() {
        val policy = DownloadPolicy(youtubeLimit = 99, otherLimit = -4)
        assertEquals(DownloadConcurrency.MAX, policy.sourceLimit(DownloadSource.YOUTUBE))
        assertEquals(DownloadConcurrency.MIN, policy.sourceLimit(DownloadSource.OTHER))
        assertEquals(
            DownloadSource.YOUTUBE,
            DownloadSource.from(
                DownloadEntity(
                    url = "https://open.spotify.com/track/abc",
                    searchQuery = "ytsearch5:artista canción"
                )
            )
        )
    }

    @Test
    fun diagnosticsKeepUsefulWarningsAndDiscardProgressNoise() {
        assertEquals(
            "WARNING: No supported JavaScript runtime could be found",
            DownloadDiagnostics.relevantLine(
                "WARNING: No supported JavaScript runtime could be found"
            )
        )
        assertEquals(
            "ERROR: Postprocessing failed",
            DownloadDiagnostics.relevantLine("ERROR: Postprocessing failed")
        )
        assertEquals(null, DownloadDiagnostics.relevantLine("[download] 51.2% of 10 MiB"))
        assertEquals(
            null,
            DownloadDiagnostics.relevantLine(
                "WARNING: [youtube] No title found in player responses; falling back to initial data"
            )
        )
        assertEquals(
            "ERROR: Sign in to confirm you're not a bot",
            DownloadDiagnostics.finalMessage(
                listOf(
                    "WARNING: metadata may be missing",
                    "ERROR: Sign in to confirm you're not a bot"
                ),
                "Process exited with code 1"
            )
        )
    }

    @Test
    fun estimatorAccountsForDurationAndRequestedQuality() {
        val audio = DownloadEntity(
            url = "https://example.test/audio",
            kind = DownloadKind.AUDIO,
            audioBitrate = AudioBitrate.K320,
            targetDurationMs = 180_000
        )
        val hd = DownloadEntity(
            url = "https://example.test/video",
            kind = DownloadKind.VIDEO,
            videoQuality = VideoQuality.HD,
            targetDurationMs = 180_000
        )
        assertTrue(DownloadEstimator.estimateBytes(audio) in 8_000_000L..8_100_000L)
        assertTrue(DownloadEstimator.estimateBytes(hd) > DownloadEstimator.estimateBytes(audio))
        assertEquals(0L, DownloadEstimator.estimateBytes(audio, durationMs = 0))
    }

    @Test
    fun devicePolicyBlocksInOrderAndAdaptsParallelism() {
        val healthy = DownloadDeviceSnapshot(
            connected = true,
            wifi = true,
            charging = false,
            batteryPercent = 80,
            lowMemory = false,
            thermalStatus = 0,
            minuteOfDay = 23 * 60
        )
        assertEquals(
            null,
            DownloadDeviceConditions.blockReason(
                DownloadPolicy(wifiOnly = true, schedule = DownloadSchedule.NIGHT),
                healthy
            )
        )
        assertEquals(8, DownloadDeviceConditions.adaptiveLimit(8, true, healthy))
        assertEquals(
            1,
            DownloadDeviceConditions.adaptiveLimit(
                8,
                true,
                healthy.copy(batteryPercent = 10)
            )
        )
        assertEquals(
            "Esperando una red Wi-Fi",
            DownloadDeviceConditions.blockReason(
                DownloadPolicy(wifiOnly = true),
                healthy.copy(wifi = false)
            )
        )
        assertEquals(
            "Esperando conexión a internet",
            DownloadDeviceConditions.blockReason(
                DownloadPolicy(wifiOnly = true),
                healthy.copy(connected = false, wifi = false)
            )
        )
    }
}
