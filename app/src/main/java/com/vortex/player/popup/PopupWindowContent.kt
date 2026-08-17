package com.vortex.player.popup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.platform.LocalContext
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackService
import com.vortex.player.ui.common.rememberPlayerUiState
import com.vortex.player.ui.theme.VortexMark
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes
import com.vortex.player.ui.theme.VortexTheme
import kotlinx.coroutines.delay

/**
 * Contenido de la ventana flotante. Un dedo la arrastra, dos la redimensionan y un toque
 * revela los controles, que vuelven a esconderse solos para no tapar el vídeo.
 */
@Composable
fun PopupWindowContent(
    onMove: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit
) {
    VortexTheme {
        val context = LocalContext.current
        val player by PlaybackHub.player.collectAsStateWithLifecycle()
        val controls by PlaybackHub.controls.collectAsStateWithLifecycle()
        val entry by PlaybackHub.currentEntry.collectAsStateWithLifecycle()
        val audioOnly by PlaybackHub.audioOnly.collectAsStateWithLifecycle()
        val uiState by rememberPlayerUiState(player)

        var showControls by remember { mutableStateOf(true) }

        LaunchedEffect(showControls) {
            if (showControls) {
                delay(2_800)
                showControls = false
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .clip(VortexShapes.medium)
                .background(Color.Black)
                .border(1.dp, VortexPalette.Neon.copy(alpha = 0.45f), VortexShapes.medium)
                .pointerInput(Unit) {
                    // Un solo reconocedor para arrastrar y escalar: si fueran dos,
                    // el primer dedo del pellizco se interpretaría como movimiento.
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) onResize(zoom) else onMove(pan.x, pan.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { onExpand() }
                    )
                }
        ) {
            if (audioOnly) {
                PopupAudioFace(title = entry?.title.orEmpty(), playing = uiState.isPlaying)
            } else {
                PopupVideoSurface(videoAspect = uiState.aspectRatio)
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        PopupIcon(Icons.Filled.OpenInFull, "Abrir a pantalla completa", onExpand)
                        PopupIcon(Icons.Filled.Close, "Cerrar", onClose)
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(46.dp)
                            .background(VortexPalette.Neon.copy(alpha = 0.16f), VortexShapes.medium)
                            .border(1.dp, VortexPalette.Neon.copy(alpha = 0.55f), VortexShapes.medium)
                            .pointerInput(Unit) {
                                detectTapGestures { PlaybackService.togglePlayPause(context) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pausar" else "Reproducir",
                            tint = VortexPalette.Neon,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // El interruptor que pediste: colapsar el vídeo a sonido sin salir del popup.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        PopupIcon(
                            icon = if (audioOnly) Icons.Filled.Videocam else Icons.Filled.Headphones,
                            description = if (audioOnly) "Restaurar vídeo" else "Solo audio",
                            tint = if (audioOnly) VortexPalette.Cyan else VortexPalette.TextHigh,
                            onClick = { PlaybackService.setAudioOnly(!audioOnly) }
                        )
                    }

                    Text(
                        text = controls?.engineName?.uppercase().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )
                }
            }

            // Hilo de progreso siempre visible: en una ventana pequeña es la única
            // referencia temporal que cabe sin estorbar.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
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
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PopupVideoSurface(videoAspect: Float) {
    val controls by PlaybackHub.controls.collectAsStateWithLifecycle()
    var container by remember { mutableStateOf<AspectRatioFrameLayout?>(null) }

    DisposableEffect(controls, container) {
        container?.let { controls?.attachVideoOutput(it) }
        onDispose { controls?.detachVideoOutput() }
    }

    AndroidView(
        factory = { ctx -> AspectRatioFrameLayout(ctx).also { container = it } },
        update = { it.setAspectRatio(videoAspect) },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun PopupAudioFace(title: String, playing: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(VortexPalette.GraphiteRaised, Color.Black))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VortexMark(modifier = Modifier.size(54.dp), spinning = playing, strokeWidth = 3.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp)
            )
        }
    }
}

@Composable
private fun PopupIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) = PopupIcon(icon, description, VortexPalette.TextHigh, onClick)

@Composable
private fun PopupIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(Color.Black.copy(alpha = 0.45f), VortexShapes.extraSmall)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}
