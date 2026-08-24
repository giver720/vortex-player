package com.vortex.player.audio

import kotlin.math.ln

/** Resultado puro que después se aplica al ecualizador nativo de libVLC. */
data class VlcEqualizerPlan(
    val enabled: Boolean,
    val preampDb: Float,
    val bandGainsDb: List<Float>
)

/**
 * Traduce los diez controles de Vórtex a las frecuencias reales que exponga libVLC.
 * La interpolación es logarítmica porque el oído y los ecualizadores trabajan por octavas.
 */
object VlcEqualizerPlanner {
    private const val MIN_VLC_DB = -20f
    private const val MAX_VLC_DB = 20f
    private const val MAX_BASS_DB = 10f
    private const val MAX_CLARITY_DB = 7f

    private val bassCurve = listOf(1f, 0.85f, 0.6f, 0.3f, 0.1f, 0f, 0f, 0f, 0f, 0f)
    private val clarityCurve = listOf(0f, 0f, 0f, 0f, 0.1f, 0.35f, 0.8f, 1f, 0.85f, 0.4f)

    fun build(settings: AudioSettings, vlcFrequenciesHz: List<Float>): VlcEqualizerPlan {
        if (!settings.enabled) return VlcEqualizerPlan(false, 0f, emptyList())

        val source = EQ_BANDS.indices.map { index ->
            val equalizer = if (settings.equalizerOn) {
                settings.effectiveBands.getOrElse(index) { 0f }
            } else {
                0f
            }
            val bass = if (settings.bassBoostOn) {
                bassCurve[index] * MAX_BASS_DB *
                    (settings.bassBoost.coerceIn(0, AudioSettings.MAX_STRENGTH).toFloat() /
                        AudioSettings.MAX_STRENGTH)
            } else {
                0f
            }
            val clarity = if (settings.clarityOn) {
                clarityCurve[index] * MAX_CLARITY_DB *
                    (settings.clarity.coerceIn(0, AudioSettings.MAX_STRENGTH).toFloat() /
                        AudioSettings.MAX_STRENGTH)
            } else {
                0f
            }
            (equalizer + bass + clarity).coerceIn(MIN_VLC_DB, MAX_VLC_DB)
        }

        val mapped = vlcFrequenciesHz.map { frequency -> interpolate(frequency, source) }
        val boost = if (settings.boostOn) {
            settings.boostDb.coerceIn(0f, AudioSettings.MAX_BOOST_DB)
        } else {
            0f
        }
        // libVLC no ofrece un limitador dinámico. Con la protección activada se reserva
        // margen estático igual al pico más alto de la curva, evitando clipping.
        val headroom = if (settings.limiterOn) mapped.maxOrNull()?.coerceAtLeast(0f) ?: 0f else 0f
        return VlcEqualizerPlan(
            enabled = true,
            preampDb = (boost - headroom).coerceIn(MIN_VLC_DB, MAX_VLC_DB),
            bandGainsDb = mapped
        )
    }

    private fun interpolate(frequencyHz: Float, gains: List<Float>): Float {
        if (frequencyHz <= EQ_BANDS.first()) return gains.first()
        if (frequencyHz >= EQ_BANDS.last()) return gains.last()
        val upper = EQ_BANDS.indexOfFirst { it >= frequencyHz }
        val lower = upper - 1
        val from = ln(EQ_BANDS[lower].toFloat())
        val to = ln(EQ_BANDS[upper].toFloat())
        val point = ln(frequencyHz.coerceAtLeast(1f))
        val fraction = ((point - from) / (to - from)).coerceIn(0f, 1f)
        return (gains[lower] + (gains[upper] - gains[lower]) * fraction)
            .coerceIn(MIN_VLC_DB, MAX_VLC_DB)
    }
}
