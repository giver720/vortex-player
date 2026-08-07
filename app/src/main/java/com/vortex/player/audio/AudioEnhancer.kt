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
    val hasCompressor: Boolean = false,
    /** Se pidió procesar todo el sistema y el dispositivo lo aceptó. */
    val systemWide: Boolean = false
)

/**
 * Procesado de audio sobre una sesión.
 *
 * En Android 9 y posteriores todo se monta dentro de `DynamicsProcessing`: ecualizador de
 * diez bandas, refuerzo de graves, compresor multibanda y limitador. **Una sola cadena.**
 *
 * Esto último no es un detalle de estilo. Antes el refuerzo de graves usaba el efecto
 * `BassBoost` por separado, encadenado sobre `DynamicsProcessing` en la misma sesión, y
 * en algunos dispositivos eso rompía la ruta de audio: al activarlo dejaba de oírse todo.
 * Metiendo los graves dentro del propio ecualizador, como un realce de las bandas bajas,
 * hay un único efecto en la cadena y además el limitador ve ese realce y lo controla, en
 * lugar de recibirlo ya amplificado desde fuera.
 */
class AudioEnhancer {

    private var dynamics: DynamicsProcessing? = null
    private var virtualizer: Virtualizer? = null

    // Camino de respaldo para Android 8 y anteriores, donde no hay DynamicsProcessing.
    private var legacyBass: BassBoost? = null
    private var legacyLoudness: LoudnessEnhancer? = null

    private var sessionId: Int = -1
    private var channelCount: Int = 2

    var capabilities: AudioCapabilities = AudioCapabilities()
        private set

    /**
     * @param playerSessionId sesión del reproductor de Vórtex.
     * @param systemWide engancharse a la mezcla global (sesión 0) para afectar a todas
     *   las apps. Android lo permite pero lo desaconseja, y hay ROMs que lo ignoran.
     */
    fun attach(playerSessionId: Int, systemWide: Boolean): AudioCapabilities {
        val target = if (systemWide) GLOBAL_SESSION else playerSessionId
        if (target == sessionId && dynamics != null) return capabilities
        release()
        sessionId = target
        if (!systemWide && playerSessionId == 0) {
            return AudioCapabilities().also { capabilities = it }
        }

        val advanced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            createDynamics(target)
        } else {
            false
        }

        if (!advanced) {
            legacyBass = runCatching { BassBoost(0, target) }.getOrNull()
            legacyLoudness = runCatching { LoudnessEnhancer(target) }.getOrNull()
        }

        capabilities = AudioCapabilities(
            advanced = advanced,
            hasEqualizer = advanced,
            // En la cadena moderna los graves son parte del ecualizador, así que siempre
            // están disponibles; en la antigua dependen de que exista el efecto.
            hasBassBoost = advanced || legacyBass?.strengthSupported == true,
            hasVirtualizer = true,
            hasBoost = advanced || legacyLoudness != null,
            hasCompressor = advanced,
            systemWide = systemWide && (advanced || legacyLoudness != null)
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
        Log.w(TAG, "DynamicsProcessing no disponible en la sesión $session", it)
        dynamics = null
        false
    }

    fun apply(settings: AudioSettings) {
        val on = settings.enabled
        applyDynamics(settings, on)
        applyLegacy(settings, on)
        applyVirtualizer(settings, on)
    }

    /**
     * El virtualizador es el único efecto que `DynamicsProcessing` no cubre, así que sigue
     * siendo un efecto aparte. Se crea sólo cuando se va a usar y se destruye al apagarlo,
     * en vez de dejarlo enganchado y desactivado: cuantos menos efectos haya en la cadena,
     * menos ocasiones de que una ROM se atragante.
     */
    private fun applyVirtualizer(settings: AudioSettings, on: Boolean) {
        val wanted = on && settings.virtualizerOn && settings.virtualizer > 0
        if (!wanted) {
            runCatching { virtualizer?.release() }
            virtualizer = null
            return
        }
        runCatching {
            val effect = virtualizer ?: Virtualizer(0, sessionId).also { virtualizer = it }
            if (effect.strengthSupported) {
                effect.enabled = true
                effect.setStrength(settings.virtualizer.toShort())
            }
        }.onFailure {
            Log.w(TAG, "Virtualizador no aplicado", it)
            runCatching { virtualizer?.release() }
            virtualizer = null
        }
    }

    private fun applyDynamics(settings: AudioSettings, on: Boolean) {
        val dp = dynamics ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        runCatching {
            dp.enabled = on
            if (!on) return

            // --- Ecualizador, con los graves sumados dentro --------------------
            val eqOn = settings.equalizerOn
            val base = settings.effectiveBands
            val bass = bassShelf(settings)
            for (channel in 0 until channelCount) {
                val preEq = dp.getPreEqByChannelIndex(channel)
                // El pre-ecualizador se deja activo aunque el usuario apague el
                // ecualizador, porque es también quien aplica el refuerzo de graves.
                preEq.isEnabled = true
                EQ_BANDS.forEachIndexed { index, frequency ->
                    val band = preEq.getBand(index)
                    band.isEnabled = true
                    band.cutoffFrequency = frequency.toFloat()
                    val eqGain = if (eqOn) base.getOrElse(index) { 0f } else 0f
                    band.gain = (eqGain + bass[index]).coerceIn(MIN_BAND_DB, MAX_BAND_DB)
                    preEq.setBand(index, band)
                }
                dp.setPreEqByChannelIndex(channel, preEq)
            }

            // --- Nivelador -----------------------------------------------------
            val compressorOn = settings.compressorOn && settings.compressor > 0f
            val amount = settings.compressor.coerceIn(0f, 1f)
            for (channel in 0 until channelCount) {
                val mbc = dp.getMbcByChannelIndex(channel)
                mbc.isEnabled = compressorOn
                MBC_BANDS.forEachIndexed { index, frequency ->
                    val band = mbc.getBand(index)
                    band.isEnabled = compressorOn
                    band.cutoffFrequency = frequency.toFloat()
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

            // --- Amplificación y limitador -------------------------------------
            val boost = if (settings.boostOn) {
                settings.boostDb.coerceIn(0f, AudioSettings.MAX_BOOST_DB)
            } else {
                0f
            }
            dp.setInputGainAllChannelsTo(boost)

            for (channel in 0 until channelCount) {
                val limiter = dp.getLimiterByChannelIndex(channel)
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

    /**
     * Realce de graves repartido por bandas. Es una curva de estantería: mucho en 31 Hz y
     * desvaneciéndose hacia los medios, que es como suena natural. Sumarlo al ecualizador
     * en vez de usar un efecto aparte deja el limitador al final de todo, controlándolo.
     */
    private fun bassShelf(settings: AudioSettings): List<Float> {
        if (!settings.bassBoostOn || settings.bassBoost <= 0) return List(EQ_BANDS.size) { 0f }
        val amount = settings.bassBoost.toFloat() / AudioSettings.MAX_STRENGTH
        return BASS_CURVE.map { it * MAX_BASS_DB * amount }
    }

    private fun applyLegacy(settings: AudioSettings, on: Boolean) {
        runCatching {
            legacyBass?.let {
                val active = on && settings.bassBoostOn && settings.bassBoost > 0
                it.enabled = active
                if (active) it.setStrength(settings.bassBoost.toShort())
            }
        }.onFailure { Log.w(TAG, "Graves clásicos no aplicados", it) }

        runCatching {
            legacyLoudness?.let {
                val active = on && settings.boostOn && settings.boostDb > 0f
                it.enabled = active
                // Sin limitador se recorta a la mitad: pasado de ahí la distorsión es segura.
                if (active) {
                    val capped = settings.boostDb.coerceAtMost(AudioSettings.MAX_BOOST_DB / 2f)
                    it.setTargetGain((capped * 100).toInt())
                }
            }
        }.onFailure { Log.w(TAG, "Amplificación clásica no aplicada", it) }
    }

    fun release() {
        runCatching { dynamics?.release() }
        runCatching { virtualizer?.release() }
        runCatching { legacyBass?.release() }
        runCatching { legacyLoudness?.release() }
        dynamics = null
        virtualizer = null
        legacyBass = null
        legacyLoudness = null
        sessionId = -1
    }

    private companion object {
        const val TAG = "AudioEnhancer"

        /** Sesión 0: la mezcla de salida del sistema, común a todas las apps. */
        const val GLOBAL_SESSION = 0

        /** Bandas del compresor. Menos que en el ecualizador: comprimir fino suena raro. */
        val MBC_BANDS = listOf(120, 500, 2_000, 8_000)

        /** Peso del realce de graves por banda, de 31 Hz a 16 kHz. */
        val BASS_CURVE = listOf(1f, 0.85f, 0.6f, 0.3f, 0.1f, 0f, 0f, 0f, 0f, 0f)

        const val MAX_BASS_DB = 10f

        // Margen interno: la suma del ecualizador y los graves puede pasarse del rango
        // que ve el usuario, y recortarla ahí dejaría la curva plana justo donde importa.
        const val MIN_BAND_DB = -18f
        const val MAX_BAND_DB = 18f
    }
}
