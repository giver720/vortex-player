package com.vortex.player.audio

/** Perfiles rápidos que configuran toda la cadena de sonido con una sola acción. */
enum class AudioProMode(val label: String, val description: String) {
    CUSTOM("PERSONAL", "Conserva tus ajustes manuales."),
    SAFE("SEGURO", "Sonido limpio, con margen para evitar saturación."),
    POWERFUL("POTENTE", "Más pegada y volumen; puede saturar mezclas muy fuertes."),
    NIGHT("NOCHE", "Diálogo presente y graves contenidos para escuchar bajo."),
    VOICE("VOZ", "Consonantes y medios al frente para podcasts y películas.")
}

/** Diagnóstico preventivo; no pretende medir el PCM que VLC no expone. */
enum class ClippingRisk(val label: String) {
    SAFE("MARGEN SEGURO"),
    CAUTION("PRECAUCIÓN"),
    HIGH("RIESGO ALTO")
}

data class AudioSignalReport(
    /** Ganancia máxima teórica de la cadena respecto al archivo original. */
    val estimatedPeakDb: Float,
    val risk: ClippingRisk
)

/**
 * Los perfiles sólo usan etapas que libVLC aplica de verdad. No activan el antiguo
 * compresor porque la API Java de libVLC 3.x no ofrece compresión dinámica.
 */
object AudioProProfiles {
    fun apply(mode: AudioProMode, current: AudioSettings): AudioSettings = when (mode) {
        AudioProMode.CUSTOM -> current.copy(proMode = mode)
        AudioProMode.SAFE -> current.copy(
            enabled = true,
            bypassOn = false,
            proMode = mode,
            preset = EqPreset.FLAT,
            bands = EqPreset.FLAT.gains,
            equalizerOn = true,
            bassBoost = 150,
            bassBoostOn = true,
            clarity = 150,
            clarityOn = true,
            boostDb = 0f,
            boostOn = false,
            limiterOn = true
        )
        AudioProMode.POWERFUL -> current.copy(
            enabled = true,
            bypassOn = false,
            proMode = mode,
            preset = EqPreset.SMILE,
            bands = EqPreset.SMILE.gains,
            equalizerOn = true,
            bassBoost = 700,
            bassBoostOn = true,
            clarity = 250,
            clarityOn = true,
            boostDb = 6f,
            boostOn = true,
            limiterOn = false
        )
        AudioProMode.NIGHT -> current.copy(
            enabled = true,
            bypassOn = false,
            proMode = mode,
            preset = EqPreset.NIGHT,
            bands = EqPreset.NIGHT.gains,
            equalizerOn = true,
            bassBoost = 100,
            bassBoostOn = true,
            clarity = 350,
            clarityOn = true,
            boostDb = 0f,
            boostOn = false,
            limiterOn = true
        )
        AudioProMode.VOICE -> current.copy(
            enabled = true,
            bypassOn = false,
            proMode = mode,
            preset = EqPreset.VOCAL,
            bands = EqPreset.VOCAL.gains,
            equalizerOn = true,
            bassBoost = 0,
            bassBoostOn = false,
            clarity = 500,
            clarityOn = true,
            boostDb = 0f,
            boostOn = false,
            limiterOn = true
        )
    }
}
