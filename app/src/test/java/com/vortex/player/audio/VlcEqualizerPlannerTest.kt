package com.vortex.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcEqualizerPlannerTest {

    private val vlcBands = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

    @Test
    fun `disabled processing removes the native equalizer`() {
        val plan = VlcEqualizerPlanner.build(AudioSettings(enabled = false), vlcBands)

        assertFalse(plan.enabled)
        assertEquals(100, plan.volumePercent)
        assertTrue(plan.bandGainsDb.isEmpty())
    }

    @Test
    fun `original bypass removes equalizer and boost without losing settings`() {
        val settings = AudioSettings(
            enabled = true,
            bypassOn = true,
            preset = EqPreset.ROCK,
            boostOn = true,
            boostDb = 9f
        )

        val plan = VlcEqualizerPlanner.build(settings, vlcBands)

        assertFalse(plan.enabled)
        assertEquals(100, plan.volumePercent)
        assertTrue(plan.bandGainsDb.isEmpty())
    }

    @Test
    fun `flat preset maps to zero gain`() {
        val plan = VlcEqualizerPlanner.build(AudioSettings(enabled = true), vlcBands)

        assertTrue(plan.enabled)
        plan.bandGainsDb.forEach { assertEquals(0f, it, 0.001f) }
        assertEquals(0f, plan.preampDb, 0.001f)
        assertEquals(100, plan.volumePercent)
    }

    @Test
    fun `native frequencies receive logarithmic interpolation`() {
        val settings = AudioSettings(
            enabled = true,
            preset = null,
            bands = EQ_BANDS.mapIndexed { index, _ -> index.toFloat() }
        )

        val plan = VlcEqualizerPlanner.build(settings, listOf(62f, 88f, 125f))

        assertEquals(1f, plan.bandGainsDb[0], 0.03f)
        assertTrue(plan.bandGainsDb[1] in 1.45f..1.55f)
        assertEquals(2f, plan.bandGainsDb[2], 0.001f)
    }

    @Test
    fun `bass and clarity are added even with manual equalizer off`() {
        val settings = AudioSettings(
            enabled = true,
            equalizerOn = false,
            bassBoostOn = true,
            bassBoost = AudioSettings.MAX_STRENGTH,
            clarityOn = true,
            clarity = AudioSettings.MAX_STRENGTH,
            limiterOn = false
        )

        val plan = VlcEqualizerPlanner.build(settings, vlcBands)

        assertTrue(plan.bandGainsDb.first() > 9.8f)
        assertTrue(plan.bandGainsDb[7] > 6.8f)
        assertEquals(0f, plan.preampDb, 0.001f)
    }

    @Test
    fun `peak protection reserves equalizer headroom when boost is off`() {
        val settings = AudioSettings(
            enabled = true,
            preset = EqPreset.ELECTRONIC,
            limiterOn = true
        )

        val protected = VlcEqualizerPlanner.build(settings, vlcBands)

        assertEquals(-protected.bandGainsDb.max(), protected.preampDb, 0.001f)
        assertEquals(100, protected.volumePercent)
        assertTrue(protected.bandGainsDb.all { it in -20f..20f })
    }

    @Test
    fun `boost uses VLC 200 percent stage before equalizer preamp`() {
        val sixDb = VlcEqualizerPlanner.build(
            AudioSettings(enabled = true, boostOn = true, boostDb = 6f),
            vlcBands
        )
        val fifteenDb = VlcEqualizerPlanner.build(
            AudioSettings(enabled = true, boostOn = true, boostDb = 15f),
            vlcBands
        )

        assertEquals(200, sixDb.volumePercent)
        assertEquals(0f, sixDb.preampDb, 0.03f)
        assertEquals(200, fifteenDb.volumePercent)
        assertEquals(15f - 6.0206f, fifteenDb.preampDb, 0.001f)
    }

    @Test
    fun `boost is not cancelled by a positive preset`() {
        val plan = VlcEqualizerPlanner.build(
            AudioSettings(
                enabled = true,
                preset = EqPreset.ROCK,
                boostOn = true,
                boostDb = 5f,
                limiterOn = true
            ),
            vlcBands
        )

        assertTrue(plan.volumePercent >= 177)
        assertEquals(0f, plan.preampDb, 0.001f)
    }

    @Test
    fun `signal diagnosis distinguishes protected and risky chains`() {
        val safe = VlcEqualizerPlanner.analyze(
            AudioSettings(enabled = true, preset = EqPreset.ELECTRONIC, limiterOn = true)
        )
        val risky = VlcEqualizerPlanner.analyze(
            AudioSettings(
                enabled = true,
                preset = EqPreset.ELECTRONIC,
                boostOn = true,
                boostDb = 9f,
                limiterOn = false
            )
        )

        assertEquals(ClippingRisk.SAFE, safe.risk)
        assertTrue(safe.estimatedPeakDb <= 0.5f)
        assertEquals(ClippingRisk.HIGH, risky.risk)
        assertTrue(risky.estimatedPeakDb > 6f)
    }
}
