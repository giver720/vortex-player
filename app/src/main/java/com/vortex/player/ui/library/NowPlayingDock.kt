package com.vortex.player.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackService
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.rememberPlayerUiState
import com.vortex.player.ui.theme.VortexMark
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Barra inferior de "sonando ahora".
 *
 * Es donde vive la firma funcional de Vórtex: el interruptor de solo-audio y el salto
 * a ventana flotante están a un toque desde la biblioteca, sin tener que entrar al
 * reproductor. La insignia del motor (MEDIA3 / VLC) es deliberadamente visible: si un
 * fichero raro obliga al cambio, el usuario lo ve en lugar de sufrir un fallo silencioso.
 */
@Composable
fun NowPlayingDock(
    onExpand: () -> Unit,
    onRequestPopup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player by PlaybackHub.player.collectAsStateWithLifecycle()
    val controls by PlaybackHub.controls.collectAsStateWithLifecycle()
    val entry by PlaybackHub.currentEntry.collectAsStateWithLifecycle()
    val audioOnly by PlaybackHub.audioOnly.collectAsStateWithLifecycle()
    val uiState by rememberPlayerUiState(player)

    AnimatedVisibility(
        visible = entry != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        val current = entry ?: return@AnimatedVisibility

        Column(
            Modifier
                .fillMaxWidth()
                .background(VortexPalette.GraphiteRaised, VortexShapes.large)
                .border(0.5.dp, VortexPalette.Outline, VortexShapes.large)
        ) {
            // Hilo de progreso pegado al borde superior del dock.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(VortexPalette.Graphite)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(uiState.progress)
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(VortexPalette.Cyan, VortexPalette.Neon)
                            )
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VortexMark(
                    modifier = Modifier.size(34.dp),
                    spinning = uiState.isPlaying,
                    strokeWidth = 2.5.dp
                )

                Column(Modifier.weight(1f)) {
                    Text(
                        text = current.title.ifBlank { current.displayName },
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = controls?.engineName?.uppercase() ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (controls?.engineName == "VLC") {
                                VortexPalette.Amber
                            } else {
                                VortexPalette.NeonDim
                            }
                        )
                        Text(
                            text = formatDuration(uiState.positionMs) + " / " +
                                formatDuration(uiState.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow,
                            maxLines = 1
                        )
                        if (audioOnly) {
                            Text(
                                text = "SOLO AUDIO",
                                style = MaterialTheme.typography.labelSmall,
                                color = VortexPalette.Cyan
                            )
                        }
                    }
                }

                // Sólo tiene sentido ofrecer solo-audio si hay vídeo que apagar.
                if (current.isVideo) {
                    IconButton(onClick = { PlaybackService.setAudioOnly(!audioOnly) }) {
                        Icon(
                            imageVector = if (audioOnly) Icons.Filled.Videocam else Icons.Filled.Headphones,
                            contentDescription = if (audioOnly) "Restaurar vídeo" else "Solo audio",
                            tint = if (audioOnly) VortexPalette.Cyan else VortexPalette.TextMid
                        )
                    }
                    IconButton(onClick = onRequestPopup) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt,
                            contentDescription = "Ventana flotante",
                            tint = VortexPalette.TextMid
                        )
                    }
                }

                IconButton(onClick = { PlaybackService.togglePlayPause(context) }) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pausar" else "Reproducir",
                        tint = VortexPalette.Neon
                    )
                }
            }
        }
    }
}
