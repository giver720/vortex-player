package com.vortex.player.spotify

/** Reintentos acotados. Una espera larga se comunica, nunca se acorta para insistir antes. */
internal object SpotifyRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val MAX_AUTOMATIC_WAIT_SECONDS = 30L

    fun delayMs(status: Int, retryAfter: String?, attempt: Int): Long? {
        if (attempt >= MAX_ATTEMPTS - 1) return null
        if (status == 429) {
            val seconds = if (retryAfter.isNullOrBlank()) 2L else {
                retryAfter.trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
            }
            if (seconds > MAX_AUTOMATIC_WAIT_SECONDS) return null
            return seconds.coerceAtLeast(1L) * 1_000L
        }
        return if (status == -1 || status in 500..599) 700L * (attempt + 1) else null
    }

    fun failureMessage(status: Int): String = when (status) {
        401 -> "La sesión de Spotify caducó o fue rechazada. Vuelve a conectar tu cuenta."
        403 -> "Spotify no permite acceder a esta lista con la cuenta o los permisos actuales."
        404 -> "Spotify no encontró esa lista o ya no está disponible."
        429 -> "Spotify limitó las solicitudes. Espera el plazo indicado por el servicio antes de reintentar."
        else -> "Spotify respondió HTTP $status"
    }
}
