package com.vortex.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProProfilesTest {

    @Test
    fun `safe profile enables protection and avoids artificial boost`() {
        val result = AudioProProfiles.apply(AudioProMode.SAFE, AudioSettings())

        assertTrue(result.enabled)
        assertFalse(result.bypassOn)
        assertTrue(result.limiterOn)
        assertFalse(result.boostOn)
        assertEquals(AudioProMode.SAFE, result.proMode)
        assertEquals(ClippingRisk.SAFE, VlcEqualizerPlanner.analyze(result).risk)
    }

    @Test
    fun `powerful profile is intentionally audible and reports its risk`() {
        val result = AudioProProfiles.apply(AudioProMode.POWERFUL, AudioSettings())

        assertTrue(result.boostOn)
        assertEquals(6f, result.boostDb, 0.001f)
        assertEquals(EqPreset.SMILE, result.preset)
        assertEquals(ClippingRisk.HIGH, VlcEqualizerPlanner.analyze(result).risk)
    }

    @Test
    fun `voice profile removes bass and focuses presence`() {
        val result = AudioProProfiles.apply(AudioProMode.VOICE, AudioSettings())

        assertFalse(result.bassBoostOn)
        assertTrue(result.clarityOn)
        assertEquals(EqPreset.VOCAL, result.preset)
        assertEquals(AudioProMode.VOICE, result.proMode)
    }

    @Test
    fun `custom mode does not overwrite manual values`() {
        val source = AudioSettings(
            enabled = true,
            preset = EqPreset.ROCK,
            boostOn = true,
            boostDb = 4f
        )

        val result = AudioProProfiles.apply(AudioProMode.CUSTOM, source)

        assertEquals(EqPreset.ROCK, result.preset)
        assertEquals(4f, result.boostDb, 0.001f)
        assertEquals(AudioProMode.CUSTOM, result.proMode)
    }
}
