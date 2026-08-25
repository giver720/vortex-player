package com.vortex.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.RepeatMode
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.formatRemaining
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

enum class Panel { SPEED, SLEEP, AUDIO, SUBTITLES, ASPECT, DIAGNOSTICS }

@Composable
fun ControlsOverlay(
    title: String,
    engineName: String,
    positionMs: Long,
    durationMs: Long,
    bufferedProgress: Float,
    isPlaying: Boolean,
    isBuffering: Boolean,
    locked: Boolean,
    audioOnly: Boolean,
    speed: Float,
    preset: AspectPreset,
    repeat: RepeatMode,
    shuffle: Boolean,
    queueCount: Int,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPanel: (Panel) -> Unit,
    onPip: () -> Unit,
    onPopup: () -> Unit,
    castButton: @Composable () -> Unit = {}
) {
    // Con el candado echado sólo queda el propio candado: es el punto de todo el modo.
    if (locked) {
        Box(Modifier.fillMaxSize()) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp)
                    .background(VortexPalette.Graphite.copy(alpha = 0.7f), VortexShapes.small)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "Desbloquear", tint = VortexPalette.Neon)
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {

        // Velos superior e inferior: garantizan legibilidad sobre escenas claras.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
        )

        // ------------------------------------------------------------ superior
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = VortexPalette.TextHigh)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = VortexPalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = engineName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.Amber
                    )
                    if (speed != 1f) {
                        Text(
                            text = "×$speed",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.Cyan
                        )
                    }
                    SleepBadge()
                }
            }
            castButton()
            Box {
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Cola de reproducción, $queueCount elementos",
                        tint = VortexPalette.TextMid
                    )
                }
                if (queueCount > 0) {
                    Text(
                        queueCount.coerceAtMost(99).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.Graphite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(VortexPalette.Neon, VortexShapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            IconButton(onClick = { onOpenPanel(Panel.DIAGNOSTICS) }) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Diagnóstico de reproducción",
                    tint = VortexPalette.Cyan
                )
            }
            IconButton(onClick = onToggleLock) {
                Icon(Icons.Filled.LockOpen, contentDescription = "Bloquear", tint = VortexPalette.TextMid)
            }
            IconButton(onClick = onPip) {
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Miniatura del sistema",
                    tint = VortexPalette.TextMid
                )
            }
        }

        // -------------------------------------------------------------- centro
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ShuffleButton(enabled = shuffle, onClick = onToggleShuffle)
            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Anterior",
                    tint = VortexPalette.TextHigh,
                    modifier = Modifier.size(34.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(VortexPalette.Neon.copy(alpha = 0.14f), VortexShapes.large)
                    .border(1.dp, VortexPalette.Neon.copy(alpha = 0.6f), VortexShapes.large)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = VortexPalette.Neon,
                    modifier = Modifier.size(38.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Siguiente",
                    tint = VortexPalette.TextHigh,
                    modifier = Modifier.size(34.dp)
                )
            }
            RepeatButton(mode = repeat, onClick = onCycleRepeat)
        }

        // ------------------------------------------------------------ inferior
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.Neon
                )
                Text(
                    text = formatRemaining(positionMs, durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.TextLow
                )
            }

            SeekBar(
                progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                buffered = bufferedProgress,
                onSeek = onSeekTo,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(
                    icon = if (audioOnly) Icons.Filled.Videocam else Icons.Filled.Headphones,
                    label = if (audioOnly) "VÍDEO" else "AUDIO",
                    tint = if (audioOnly) VortexPalette.Cyan else VortexPalette.TextMid,
                    onClick = onToggleAudioOnly
                )
                ActionButton(
                    icon = Icons.Filled.Speed,
                    label = "×$speed",
                    onClick = { onOpenPanel(Panel.SPEED) }
                )
                ActionButton(
                    icon = Icons.Filled.Tune,
                    label = "PISTAS",
                    onClick = { onOpenPanel(Panel.AUDIO) }
                )
                ActionButton(
                    icon = Icons.Filled.ClosedCaption,
                    label = "SUBS",
                    onClick = { onOpenPanel(Panel.SUBTITLES) }
                )
                ActionButton(
                    icon = Icons.Filled.AspectRatio,
                    label = preset.label,
                    tint = if (preset == AspectPreset.FIT) {
                        VortexPalette.TextMid
                    } else {
                        VortexPalette.Cyan
                    },
                    onClick = onOpenAspect
                )
                ActionButton(
                    icon = Icons.Filled.Bedtime,
                    label = "DORMIR",
                    onClick = { onOpenPanel(Panel.SLEEP) }
                )
                ActionButton(
                    icon = Icons.Filled.PictureInPictureAlt,
                    label = "POPUP",
                    onClick = onPopup
                )
            }
        }
    }
}

/** Cuenta atrás del temporizador, sólo visible cuando está armado. */
@Composable
private fun SleepBadge() {
    val sleepAt by PlaybackHub.sleepAtMs.collectAsStateWithLifecycle()
    val target = sleepAt ?: return
    val remaining = (target - System.currentTimeMillis()).coerceAtLeast(0L)
    Text(
        text = "⏱ " + formatDuration(remaining),
        style = MaterialTheme.typography.labelSmall,
        color = VortexPalette.Amber
    )
}

/**
 * Aleatorio y repetición. Van junto a anterior/siguiente y no en la fila de acciones de
 * abajo porque modifican lo mismo que esos dos botones —por dónde sigue la cola— y ahí es
 * donde los busca la mano.
 *
 * El estado se lee del color y, cuando está activo, de un punto bajo el icono: sólo el
 * tinte se pierde de un vistazo en pantallas al sol.
 */
@Composable
private fun ShuffleButton(enabled: Boolean, onClick: () -> Unit) {
    OrderButton(
        icon = Icons.Filled.Shuffle,
        description = if (enabled) "Aleatorio activado" else "Aleatorio desactivado",
        active = enabled,
        onClick = onClick
    )
}

@Composable
private fun RepeatButton(mode: RepeatMode, onClick: () -> Unit) {
    OrderButton(
        icon = if (mode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
        description = when (mode) {
            RepeatMode.OFF -> "Sin repetición"
            RepeatMode.ALL -> "Repetir toda la cola"
            RepeatMode.ONE -> "Repetir esta pista"
        },
        active = mode != RepeatMode.OFF,
        onClick = onClick
    )
}

@Composable
private fun OrderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint = if (active) VortexPalette.Neon else VortexPalette.TextMid
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(4.dp)
                .background(
                    if (active) VortexPalette.Neon else Color.Transparent,
                    VortexShapes.extraSmall
                )
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = VortexPalette.TextMid,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/**
 * Barra de búsqueda propia en vez del `Slider` de Material: hace falta pintar el búfer
 * como segunda capa y un pulgar romboidal que encaje con el lenguaje angular de la app.
 */
@Composable
private fun SeekBar(
    progress: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: progress

    Box(
        modifier = modifier
            .height(26.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let(onSeek)
                        dragFraction = null
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragFraction = ((dragFraction ?: progress) + delta / size.width)
                            .coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(VortexPalette.Outline)
        )
        Box(
            Modifier
                .fillMaxWidth(buffered)
                .height(3.dp)
                .background(VortexPalette.TextLow.copy(alpha = 0.5f))
        )
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(listOf(VortexPalette.Cyan, VortexPalette.Neon))
                )
        )
        Box(Modifier.fillMaxWidth(shown), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .size(13.dp)
                    .background(VortexPalette.Neon, VortexShapes.extraSmall)
            )
        }
    }
}
