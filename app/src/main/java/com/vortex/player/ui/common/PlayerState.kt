package com.vortex.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay

/** Fotografía del reproductor pensada para Compose: sin `Player` dentro, todo inmutable. */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val hasVideo: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val bufferedProgress: Float
        get() = if (durationMs <= 0) 0f else (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val aspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 16f / 9f
}

/**
 * Observa un [Player]. Los eventos discretos llegan por listener; la posición se
 * refresca aparte cada 250 ms, porque `Player` no notifica el avance del reloj.
 */
@Composable
fun rememberPlayerUiState(player: Player?): State<PlayerUiState> {
    val state = remember { mutableStateOf(PlayerUiState()) }

    DisposableEffect(player) {
        if (player == null) {
            state.value = PlayerUiState()
            return@DisposableEffect onDispose { }
        }

        fun snapshot(previous: PlayerUiState) = previous.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            speed = player.playbackParameters.speed,
            videoWidth = player.videoSize.width,
            videoHeight = player.videoSize.height,
            hasVideo = player.videoSize.width > 0
        )

        state.value = snapshot(state.value)

        val listener = object : Player.Listener {
            override fun onEvents(target: Player, events: Player.Events) {
                state.value = snapshot(state.value)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                state.value = state.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                    hasVideo = videoSize.width > 0
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                state.value = state.value.copy(error = error.errorCodeName)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            state.value = state.value.copy(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            )
            delay(250)
        }
    }

    return state
}
