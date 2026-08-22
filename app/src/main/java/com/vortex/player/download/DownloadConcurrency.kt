package com.vortex.player.download

/** Límites compartidos por preferencias, servicio e interfaz. */
object DownloadConcurrency {
    const val MIN = 1
    const val MAX = 10
    const val DEFAULT = 1

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}
