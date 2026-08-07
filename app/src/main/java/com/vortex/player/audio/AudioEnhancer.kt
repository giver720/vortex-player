package com.vortex.player.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log

/** Lo que el dispositivo permite tocar. Cada ROM ofrece cosas distintas. */
data class AudioCapabilities(
    /** Hay ecualizador de diez bandas, compresor y limitador propios. */
    val advanced: Boolean = false,
    val hasEqualizer: Boolean = false,
    val hasBassBoost: Boolean = false,
    val hasVirtualizer: Boolean = false,
    val hasBoost: Boolean = false,
    val hasCompressor: Boolean = false
)

/**
 * Procesado de audio sobre la sesión del reproductor.
 *
 * En Android 9 y posteriores se usa `DynamicsProcessing`, que permite montar la cadena
 * entera: ecualizador de diez bandas a las frecuencias que queramos, compresor multibanda
 * y **limitador**. El limitador es la pieza que justifica todo lo demás: sin él, subir el
 * volumen por encima del máximo del sistema recorta los picos y lo que se gana en
 * potencia se pierde en distorsión. Con él se puede empujar de verdad y que siga sonando
 * limpio.
 *
 * En versiones anteriores se cae a los efectos clásicos, que dan menos control: el
 * ecualizador trae las bandas que traiga el móvil y la amplificación no lleva red.
 *
 * Nada de esto se aplica cuando reproduce libVLC: ese motor no expone una sesión de audio
 * a la que engancharse, y la interfaz lo advierte en vez de fingir que funciona.
 */
class AudioEnhancer {

    private var dynamics: DynamicsProcessing? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var legacyLoudness: LoudnessEnhancer? = null

    private var sessionId: Int = 0
    private var channelCount: Int = 2

    var capabilities: AudioCapabilities = AudioCapabilities()
        private set

    fun attach(newSessionId: Int): AudioCapabilities {
        if (newSessionId == sessionId && (dynamics != null || legacyLoudness != null)) {
            return capabilities
        }
        release()
        sessionId = newSessionId
        if (newSessionId == 0) return AudioCapabilities().also { capabilities = it }

        // Estos dos son efectos independientes y conviven con la cadena principal, así
        // que se crean igual en ambos caminos.
        bassBoost = runCatching { BassBoost(0, newSessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, newSessionId) }.getOrNull()

        val advanced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            createDynamics(newSessionId)
        } else {
            false
        }

        if (!advanced) {
            legacyLoudness = runCatching { LoudnessEnhancer(newSessionId) }.getOrNull()
        }

        capabilities = AudioCapabilities(
            advanced = advanced,
            hasEqualizer = advanced,
            hasBassBoost = bassBoost?.strengthSupported == true,
            hasVirtualizer = virtualizer?.strengthSupported == true,
            hasBoost = advanced || legacyLoudness != null,
            hasCompressor = advanced
        )
        return capabilities
    }

    private fun createDynamics(session: Int): Boolean = runCatching {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channelCount,
            /* preEqInUse = */ true,
            /* preEqBandCount = */ EQ_BANDS.size,
            /* mbcInUse = */ true,
            /* mbcBandCount = */ MBC_BANDS.size,
            /* postEqInUse = */ false,
            /* postEqBandCount = */ 0,
            /* limiterInUse = */ true
        ).build()
        dynamics = DynamicsProcessing(0, session, config)
        true
    }.getOrElse {
        Log.w(TAG, "DynamicsProcessing no disponible, se usan los efectos clásicos", it)
        dynamics = null
        false
    }

    fun apply(settings: AudioSettings) {
        val on = settings.enabled
        applyDynamics(settings, on)
        applyLegacy(settings, on)

        runCatching {
            bassBoost?.let {
                val active = on && settings.bassBoostOn && settings.bassBoost > 0
                it.enabled = active
                if (active) it.setStrength(settings.bassBoost.toShort())
            }
        }.onFailure { Log.w(TAG, "Refuerzo de graves no aplicado", it) }

        runCatching {
            virtualizer?.let {
                val active = on && settings.virtualizerOn && settings.virtualizer > 0
                it.enabled = active
                if (active) it.setStrength(settings.virtualizer.toShort())
            }
        }.onFailure { Log.w(TAG, "Virtualizador no aplicado", it) }
    }

    private fun applyDynamics(settings: AudioSettings, on: Boolean) {
        val dp = dynamics ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        runCatching {
            dp.enabled = on
            if (!on) return

            // --- Ecualizador -------------------------------------------------
            val eqOn = settings.equalizerOn
            val gains = settings.effectiveBands
            for (channel in 0 until channelCount) {
                val preEq = dp.getPreEqByChannelIndex(channel)
                preEq.isEnabled = eqOn
                EQ_BANDS.forEachIndexed { index, frequency ->
                    val band = preEq.getBand(index)
                    band.isEnabled = eqOn
                    band.cutoffFrequency = frequency.toFloat()
                    band.gain = gains.getOrElse(index) { 0f }.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
                    preEq.setBand(index, band)
                }
                dp.setPreEqByChannelIndex(channel, preEq)
            }

            // --- Nivelador (compresor multibanda) -----------------------------
            val compressorOn = settings.compressorOn && settings.compressor > 0f
            val amount = settings.compressor.coerceIn(0f, 1f)
            for (channel in 0 until channelCount) {
                val mbc = dp.getMbcByChannelIndex(channel)
                mbc.isEnabled = compressorOn
                MBC_BANDS.forEachIndexed { index, frequency ->
                    val band = mbc.getBand(index)
                    band.isEnabled = compressorOn
                    band.cutoffFrequency = frequency.toFloat()
                    // Cuanto más nivelador, antes entra y más aprieta. La ganancia de
                    // salida compensa lo que el compresor baja, que es de donde sale la
                    // sensación de "suena más lleno" sin tocar el volumen.
                    band.threshold = -20f - 20f * amount
                    band.ratio = 1.5f + 4.5f * amount
                    band.attackTime = 8f
                    band.releaseTime = 120f
                    band.kneeWidth = 6f
                    band.postGain = 3f * amount
                    band.preGain = 0f
                    band.noiseGateThreshold = -90f
                    band.expanderRatio = 1f
                    mbc.setBand(index, band)
                }
                dp.setMbcByChannelIndex(channel, mbc)
            }

            // --- Amplificación y limitador ------------------------------------
            val boostOn = settings.boostOn && settings.boostDb > 0f
            val boost = if (boostOn) {
                settings.boostDb.coerceIn(0f, AudioSettings.MAX_BOOST_DB)
            } else {
                0f
            }
            dp.setInputGainAllChannelsTo(boost)

            for (channel in 0 until channelCount) {
                val limiter = dp.getLimiterByChannelIndex(channel)
                // El limitador se deja siempre puesto mientras el procesado esté activo:
                // también protege de los picos que crean el ecualizador y el compresor,
                // no sólo de la amplificación.
                limiter.isEnabled = true
                limiter.threshold = -1f
                limiter.ratio = 20f
                limiter.attackTime = 1f
                limiter.releaseTime = 60f
                limiter.postGain = 0f
                dp.setLimiterByChannelIndex(channel, limiter)
            }
        }.onFailure { Log.w(TAG, "Cadena de audio no aplicada", it) }
    }

    private fun applyLegacy(settings: AudioSettings, on: Boolean) {
        val loudness = legacyLoudness ?: return
        runCatching {
            val active = on && settings.boostOn && settings.boostDb > 0f
            loudness.enabled = active
            // Sin limitador disponible se recorta la amplificación a la mitad: pasado de
            // ahí, en este camino, la distorsión es segura.
            if (active) {
                val capped = settings.boostDb.coerceAtMost(AudioSettings.MAX_BOOST_DB / 2f)
                loudness.setTargetGain((capped * 100).toInt())
            }
        }.onFailure { Log.w(TAG, "Amplificación clásica no aplicada", it) }
    }

    fun release() {
        runCatching { dynamics?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { legacyLoudness?.release() }
        dynamics = null
        bassBoost = null
        virtualizer = null
        legacyLoudness = null
        sessionId = 0
    }

    private companion object {
        const val TAG = "AudioEnhancer"

        /** Bandas del compresor. Menos que en el ecualizador: comprimir fino suena raro. */
        val MBC_BANDS = listOf(120, 500, 2_000, 8_000)
    }
}
