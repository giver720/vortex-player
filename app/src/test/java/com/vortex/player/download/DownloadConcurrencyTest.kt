package com.vortex.player.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadConcurrencyTest {

    @Test
    fun acceptsEverySelectableValue() {
        (DownloadConcurrency.MIN..DownloadConcurrency.MAX).forEach { value ->
            assertEquals(value, DownloadConcurrency.clamp(value))
        }
    }

    @Test
    fun clampsValuesOutsideTheSupportedRange() {
        assertEquals(DownloadConcurrency.MIN, DownloadConcurrency.clamp(Int.MIN_VALUE))
        assertEquals(DownloadConcurrency.MAX, DownloadConcurrency.clamp(Int.MAX_VALUE))
    }
}
