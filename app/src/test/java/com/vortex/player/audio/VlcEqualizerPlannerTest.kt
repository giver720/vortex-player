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
        assertTrue(plan.bandGainsDb.isEmpty())
    }

    @Test
    fun `flat preset maps to zero gain`() {
        val plan = VlcEqualizerPlanner.build(AudioSettings(enabled = true), vlcBands)

        assertTrue(plan.enabled)
        plan.bandGainsDb.forEach { assertEquals(0f, it, 0.001f) }
        assertEquals(0f, plan.preampDb, 0.001f)
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
    fun `peak protection reserves headroom while boost remains bounded`() {
        val settings = AudioSettings(
            enabled = true,
            preset = EqPreset.ELECTRONIC,
            boostOn = true,
            boostDb = 15f,
            limiterOn = true
        )

        val protected = VlcEqualizerPlanner.build(settings, vlcBands)
        val unprotected = VlcEqualizerPlanner.build(settings.copy(limiterOn = false), vlcBands)

        assertEquals(15f - protected.bandGainsDb.max(), protected.preampDb, 0.001f)
        assertEquals(15f, unprotected.preampDb, 0.001f)
        assertTrue(protected.bandGainsDb.all { it in -20f..20f })
    }
}
