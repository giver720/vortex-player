package com.vortex.player.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cancelar una sincronización no es un error de Spotify ni autoriza escribir su caché. */
internal suspend inline fun <T> spotifyResult(block: () -> T): Result<T> = try {
    currentCoroutineContext().ensureActive()
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
