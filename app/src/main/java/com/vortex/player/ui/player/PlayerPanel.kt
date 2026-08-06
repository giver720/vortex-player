package com.vortex.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.TrackOption
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Panel inferior de opciones. Es una superficie propia y no un `ModalBottomSheet` porque
 * necesita convivir con la capa de gestos a pantalla completa sin robarle el foco al vídeo.
 */
@Composable
fun PlayerPanel(
    panel: Panel,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (Int?) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    currentPreset: AspectPreset,
    onSelectAspect: (AspectPreset) -> Unit
) {
    val controls by PlaybackHub.controls.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            // Un toque fuera cierra: en un reproductor el gesto de salida debe ser inmediato.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .background(VortexPalette.Graphite.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp)
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
                .background(VortexPalette.GraphiteRaised, VortexShapes.large)
                .border(0.5.dp, VortexPalette.Outline, VortexShapes.large)
                .padding(16.dp)
                .heightIn(max = 380.dp)
        ) {
            Text(
                text = when (panel) {
                    Panel.SPEED -> "VELOCIDAD"
                    Panel.SLEEP -> "TEMPORIZADOR"
                    Panel.AUDIO -> "PISTA DE AUDIO"
                    Panel.SUBTITLES -> "SUBTÍTULOS"
                    Panel.ASPECT -> "RELACIÓN DE ASPECTO"
                },
                style = MaterialTheme.typography.labelMedium,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            when (panel) {
                Panel.SPEED -> SpeedOptions(onSpeed)
                Panel.SLEEP -> SleepOptions(onSleep)
                Panel.AUDIO -> TrackOptions(
                    tracks = controls?.audioTracks.orEmpty(),
                    allowNone = false,
                    onSelect = { it?.let(onSelectAudio) }
                )
                Panel.SUBTITLES -> TrackOptions(
                    tracks = controls?.subtitleTracks.orEmpty(),
                    allowNone = true,
                    onSelect = onSelectSubtitle
                )
                Panel.ASPECT -> AspectOptions(currentPreset, onSelectAspect)
            }
        }
    }
}

/**
 * Selector de aspecto al estilo VLC. Los tres modos de encaje van arriba y las
 * relaciones forzadas debajo, porque son el recurso para cuando el vídeo trae mal los
 * metadatos y ningún encaje automático lo cuadra.
 */
@Composable
private fun AspectOptions(
    current: AspectPreset,
    onSelect: (AspectPreset) -> Unit
) {
    val modes = listOf(AspectPreset.FIT, AspectPreset.CROP, AspectPreset.STRETCH)
    val ratios = AspectPreset.entries - modes.toSet()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.forEach { mode ->
            OptionChip(
                label = mode.label,
                tint = if (mode == current) VortexPalette.Neon else VortexPalette.TextHigh
            ) { onSelect(mode) }
        }
    }
    Text(
        text = "RELACIÓN FORZADA",
        style = MaterialTheme.typography.labelSmall,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ratios.take(3).forEach { ratio ->
            OptionChip(
                label = ratio.label,
                tint = if (ratio == current) VortexPalette.Neon else VortexPalette.TextHigh
            ) { onSelect(ratio) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ratios.drop(3).forEach { ratio ->
            OptionChip(
                label = ratio.label,
                tint = if (ratio == current) VortexPalette.Neon else VortexPalette.TextHigh
            ) { onSelect(ratio) }
        }
    }
    Text(
        text = "Consejo: con dos dedos puedes acercar o alejar sobre cualquier preset.",
        style = MaterialTheme.typography.bodySmall,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(top = 14.dp)
    )
}

@Composable
private fun SpeedOptions(onSpeed: (Float) -> Unit) {
    // 0,5× a 3×: por encima de 2× el audio ya no se entiende sin corrección de tono,
    // que ambos motores aplican, así que 3× sigue siendo utilizable para repasar.
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        speeds.take(5).forEach { OptionChip("×$it") { onSpeed(it) } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        speeds.drop(5).forEach { OptionChip("×$it") { onSpeed(it) } }
    }
}

@Composable
private fun SleepOptions(onSleep: (Int?) -> Unit) {
    val minutes = listOf(10, 15, 30, 45, 60, 90)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        minutes.take(3).forEach { OptionChip("$it min") { onSleep(it) } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        minutes.drop(3).forEach { OptionChip("$it min") { onSleep(it) } }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OptionChip("CANCELAR", tint = VortexPalette.Magenta) { onSleep(null) }
    }
}

@Composable
private fun TrackOptions(
    tracks: List<TrackOption>,
    allowNone: Boolean,
    onSelect: (String?) -> Unit
) {
    if (tracks.isEmpty() && !allowNone) {
        Text(
            text = "Este medio sólo tiene una pista.",
            style = MaterialTheme.typography.bodyMedium,
            color = VortexPalette.TextLow
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (allowNone) {
            item {
                TrackRow(
                    label = "Desactivados",
                    selected = tracks.none { it.selected },
                    onClick = { onSelect(null) }
                )
            }
        }
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                label = track.label,
                selected = track.selected,
                onClick = { onSelect(track.id) }
            )
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = VortexPalette.Neon)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) VortexPalette.TextHigh else VortexPalette.TextMid
        )
    }
}

@Composable
private fun OptionChip(
    label: String,
    tint: androidx.compose.ui.graphics.Color = VortexPalette.TextHigh,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = tint,
        modifier = Modifier
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
