package com.vortex.player.audio

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Resultado puro que después se aplica al ecualizador nativo de libVLC. */
data class VlcEqualizerPlan(
    val enabled: Boolean,
    val preampDb: Float,
    /** Etapa de volumen software de VLC: 100 normal, 200 máximo. */
    val volumePercent: Int,
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
    private const val NORMAL_VOLUME_PERCENT = 100
    private const val MAX_VOLUME_PERCENT = 200
    /** 200 % equivale a 20·log10(2), aproximadamente +6,02 dB. */
    private const val MAX_VOLUME_STAGE_DB = 6.0206f

    private val bassCurve = listOf(1f, 0.85f, 0.6f, 0.3f, 0.1f, 0f, 0f, 0f, 0f, 0f)
    private val clarityCurve = listOf(0f, 0f, 0f, 0f, 0.1f, 0.35f, 0.8f, 1f, 0.85f, 0.4f)

    fun build(settings: AudioSettings, vlcFrequenciesHz: List<Float>): VlcEqualizerPlan {
        if (!settings.enabled) {
            return VlcEqualizerPlan(false, 0f, NORMAL_VOLUME_PERCENT, emptyList())
        }

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
        // El boost perceptible usa primero la misma etapa de 100–200 % que VLC para Android.
        // Lo que exceda +6,02 dB pasa al preamplificador del ecualizador.
        val volumePercent = volumePercentForBoost(boost)
        val volumeStageDb = boost.coerceAtMost(MAX_VOLUME_STAGE_DB)
        val remainingBoostDb = (boost - volumeStageDb).coerceAtLeast(0f)

        // Sin boost, la protección reserva el margen completo de la curva. Con boost no se
        // resta esa ganancia: hacerlo cancelaba el control en los presets con más pegada.
        val headroom = if (settings.limiterOn) mapped.maxOrNull()?.coerceAtLeast(0f) ?: 0f else 0f
        return VlcEqualizerPlan(
            enabled = true,
            preampDb = (if (boost > 0f) remainingBoostDb else -headroom)
                .coerceIn(MIN_VLC_DB, MAX_VLC_DB),
            volumePercent = volumePercent,
            bandGainsDb = mapped
        )
    }

    /** Valor que la API `MediaPlayer.setVolume` de VLC recibirá para este boost. */
    fun volumePercentForBoost(boostDb: Float): Int {
        val stageDb = boostDb.coerceIn(0f, MAX_VOLUME_STAGE_DB)
        return (NORMAL_VOLUME_PERCENT * 10.0.pow(stageDb / 20.0)).roundToInt()
            .coerceIn(NORMAL_VOLUME_PERCENT, MAX_VOLUME_PERCENT)
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
