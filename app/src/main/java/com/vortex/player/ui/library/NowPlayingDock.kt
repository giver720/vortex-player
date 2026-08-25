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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.CastConnected
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
import com.vortex.player.cast.CastCoordinator
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
 * reproductor. La insignia VLC deja claro qué motor único está reproduciendo.
 */
@Composable
fun NowPlayingDock(
    onExpand: () -> Unit,
    onRequestPopup: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player by PlaybackHub.player.collectAsStateWithLifecycle()
    val controls by PlaybackHub.controls.collectAsStateWithLifecycle()
    val entry by PlaybackHub.currentEntry.collectAsStateWithLifecycle()
    val queue by PlaybackHub.queue.collectAsStateWithLifecycle()
    val audioOnly by PlaybackHub.audioOnly.collectAsStateWithLifecycle()
    val castState by CastCoordinator.state.collectAsStateWithLifecycle()
    val uiState by rememberPlayerUiState(player)

    AnimatedVisibility(
        visible = entry != null || castState.mediaTitle != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        val current = entry
        val remote = castState.connected
        val positionMs = if (remote) castState.positionMs else uiState.positionMs
        val durationMs = if (remote) castState.durationMs else uiState.durationMs
        val playing = if (remote) castState.isPlaying else uiState.isPlaying
        val progress = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

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
                        .fillMaxWidth(progress)
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
                    .clickable {
                        if (remote) CastCoordinator.openExpandedControls(context) else onExpand()
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VortexMark(
                    modifier = Modifier.size(34.dp),
                    spinning = if (remote) castState.isPlaying else uiState.isPlaying,
                    strokeWidth = 2.5.dp
                )

                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (remote) {
                            castState.mediaTitle.orEmpty()
                        } else {
                            current?.let { it.title.ifBlank { it.displayName } }.orEmpty()
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (remote) {
                                "CAST · ${castState.deviceName.orEmpty().uppercase()}"
                            } else {
                                controls?.engineName?.uppercase() ?: "—"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.Amber
                        )
                        Text(
                            text = formatDuration(positionMs) + " / " + formatDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow,
                            maxLines = 1
                        )
                        if (audioOnly && !remote) {
                            Text(
                                text = "SOLO AUDIO",
                                style = MaterialTheme.typography.labelSmall,
                                color = VortexPalette.Cyan
                            )
                        }
                    }
                }

                // Sólo tiene sentido ofrecer solo-audio si hay vídeo que apagar.
                if (!remote && current?.isVideo == true) {
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

                if (remote) {
                    Icon(
                        Icons.Filled.CastConnected,
                        contentDescription = "Reproduciendo en TV",
                        tint = VortexPalette.Cyan,
                        modifier = Modifier.size(25.dp)
                    )
                }

                Box {
                    IconButton(onClick = onOpenQueue) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Abrir cola de ${queue.size} elementos",
                            tint = VortexPalette.TextMid
                        )
                    }
                    if (queue.isNotEmpty()) {
                        Text(
                            text = queue.size.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.Graphite,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(VortexPalette.Neon, VortexShapes.extraSmall)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (remote) CastCoordinator.toggleRemotePlayback()
                        else PlaybackService.togglePlayPause(context)
                    }
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pausar" else "Reproducir",
                        tint = VortexPalette.Neon
                    )
                }
            }
        }
    }
}
