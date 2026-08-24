package com.vortex.player.audio

/** Capacidades que el motor activo expone a la pantalla de sonido. */
data class AudioCapabilities(
    /** Hay ecualizador de diez bandas y protección de picos propios del motor activo. */
    val advanced: Boolean = false,
    val hasEqualizer: Boolean = false,
    val hasBassBoost: Boolean = false,
    val hasVirtualizer: Boolean = false,
    val hasBoost: Boolean = false,
    val hasCompressor: Boolean = false,
    /** Se pidió procesar todo el sistema y el dispositivo lo aceptó. */
    val systemWide: Boolean = false
)
