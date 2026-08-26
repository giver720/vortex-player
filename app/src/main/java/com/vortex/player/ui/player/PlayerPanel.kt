package com.vortex.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackDiagnostics
import com.vortex.player.playback.PlaybackHealth
import com.vortex.player.playback.TrackOption
import com.vortex.player.subtitle.OnlineSubtitleResult
import com.vortex.player.subtitle.OnlineSubtitleTarget
import com.vortex.player.subtitle.OnlineSubtitleUiState
import com.vortex.player.subtitle.SubtitleTextSize
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes
import java.util.Locale

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
    primarySubtitleName: String?,
    subtitleDelayMs: Long,
    secondarySubtitleName: String?,
    secondaryDelayMs: Long,
    secondaryTextSize: SubtitleTextSize,
    secondaryBackground: Boolean,
    subtitleStatus: String?,
    onlineSubtitleState: OnlineSubtitleUiState,
    onLoadPrimarySubtitle: () -> Unit,
    onLoadSecondarySubtitle: () -> Unit,
    onRemoveSecondarySubtitle: () -> Unit,
    onAdjustSubtitleDelay: (Long) -> Unit,
    onAdjustSecondaryDelay: (Long) -> Unit,
    onCycleSecondaryTextSize: () -> Unit,
    onToggleSecondaryBackground: () -> Unit,
    onOnlineApiKeyChange: (String) -> Unit,
    onSaveOnlineApiKey: () -> Unit,
    onClearOnlineApiKey: () -> Unit,
    onOnlineQueryChange: (String) -> Unit,
    onCycleOnlineLanguage: () -> Unit,
    onSearchOnlineSubtitles: () -> Unit,
    onDownloadOnlineSubtitle: (OnlineSubtitleResult, OnlineSubtitleTarget) -> Unit,
    diagnostics: PlaybackDiagnostics,
    onRetrySafeMode: () -> Unit,
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
                .heightIn(
                    max = when (panel) {
                        Panel.SUBTITLES -> 620.dp
                        Panel.DIAGNOSTICS -> 520.dp
                        else -> 380.dp
                    }
                )
        ) {
            Text(
                text = when (panel) {
                    Panel.SPEED -> "VELOCIDAD"
                    Panel.SLEEP -> "TEMPORIZADOR"
                    Panel.AUDIO -> "PISTA DE AUDIO"
                    Panel.SUBTITLES -> "SUBTÍTULOS"
                    Panel.ASPECT -> "RELACIÓN DE ASPECTO"
                    Panel.DIAGNOSTICS -> "MOTOR INTELIGENTE"
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
                Panel.SUBTITLES -> SubtitleCenter(
                    tracks = controls?.subtitleTracks.orEmpty(),
                    primaryName = primarySubtitleName,
                    primaryDelayMs = subtitleDelayMs,
                    secondaryName = secondarySubtitleName,
                    secondaryDelayMs = secondaryDelayMs,
                    secondaryTextSize = secondaryTextSize,
                    secondaryBackground = secondaryBackground,
                    status = subtitleStatus,
                    onlineState = onlineSubtitleState,
                    onSelect = onSelectSubtitle,
                    onLoadPrimary = onLoadPrimarySubtitle,
                    onLoadSecondary = onLoadSecondarySubtitle,
                    onRemoveSecondary = onRemoveSecondarySubtitle,
                    onAdjustPrimaryDelay = onAdjustSubtitleDelay,
                    onAdjustSecondaryDelay = onAdjustSecondaryDelay,
                    onCycleSecondaryTextSize = onCycleSecondaryTextSize,
                    onToggleSecondaryBackground = onToggleSecondaryBackground,
                    onOnlineApiKeyChange = onOnlineApiKeyChange,
                    onSaveOnlineApiKey = onSaveOnlineApiKey,
                    onClearOnlineApiKey = onClearOnlineApiKey,
                    onOnlineQueryChange = onOnlineQueryChange,
                    onCycleOnlineLanguage = onCycleOnlineLanguage,
                    onSearchOnline = onSearchOnlineSubtitles,
                    onDownloadOnline = onDownloadOnlineSubtitle
                )
                Panel.ASPECT -> AspectOptions(currentPreset, onSelectAspect)
                Panel.DIAGNOSTICS -> DiagnosticsPanel(diagnostics, onRetrySafeMode)
            }
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    diagnostics: PlaybackDiagnostics,
    onRetrySafeMode: () -> Unit
) {
    val healthTint = when (diagnostics.health) {
        PlaybackHealth.PLAYING -> VortexPalette.Neon
        PlaybackHealth.RECOVERING, PlaybackHealth.BUFFERING -> VortexPalette.Amber
        PlaybackHealth.ERROR -> VortexPalette.Magenta
        else -> VortexPalette.Cyan
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                text = diagnostics.health.label,
                style = MaterialTheme.typography.headlineSmall,
                color = healthTint
            )
            Text(
                text = "VLC analiza la fuente, ajusta el búfer y cambia a software si el hardware falla.",
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { DiagnosticRow("FUENTE", diagnostics.source.label) }
        item { DiagnosticRow("DECODIFICADOR", diagnostics.decoder.label) }
        item { DiagnosticRow("BÚFER OBJETIVO", "${diagnostics.cacheMs} ms") }
        item { DiagnosticRow("CÓDEC", diagnostics.codec ?: "—") }
        item { DiagnosticRow("RESOLUCIÓN", diagnostics.resolutionLabel) }
        item {
            DiagnosticRow(
                "FPS",
                diagnostics.framesPerSecond.takeIf { it > 0f }
                    ?.let { String.format(Locale.ROOT, "%.2f", it) }
                    ?: "—"
            )
        }
        item {
            DiagnosticRow(
                "ENTRADA",
                diagnostics.inputBitrateKbps.takeIf { it > 0 }?.let { "$it kb/s" } ?: "—"
            )
        }
        item {
            DiagnosticRow(
                "CUADROS",
                "${diagnostics.decodedFrames} decodificados · " +
                    "${diagnostics.displayedFrames} mostrados · ${diagnostics.droppedFrames} perdidos"
            )
        }
        item { DiagnosticRow("PAQUETES DAÑADOS", diagnostics.corruptedPackets.toString()) }
        item { DiagnosticRow("RECUPERACIONES", diagnostics.recoveryCount.toString()) }
        diagnostics.lastRecovery?.let { recovery ->
            item {
                Text(
                    text = recovery,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (diagnostics.health == PlaybackHealth.ERROR) {
                        VortexPalette.Magenta
                    } else {
                        VortexPalette.Amber
                    }
                )
            }
        }
        item {
            OptionChip("REINTENTAR EN MODO SEGURO", tint = VortexPalette.Cyan, onClick = onRetrySafeMode)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubtitleCenter(
    tracks: List<TrackOption>,
    primaryName: String?,
    primaryDelayMs: Long,
    secondaryName: String?,
    secondaryDelayMs: Long,
    secondaryTextSize: SubtitleTextSize,
    secondaryBackground: Boolean,
    status: String?,
    onlineState: OnlineSubtitleUiState,
    onSelect: (String?) -> Unit,
    onLoadPrimary: () -> Unit,
    onLoadSecondary: () -> Unit,
    onRemoveSecondary: () -> Unit,
    onAdjustPrimaryDelay: (Long) -> Unit,
    onAdjustSecondaryDelay: (Long) -> Unit,
    onCycleSecondaryTextSize: () -> Unit,
    onToggleSecondaryBackground: () -> Unit,
    onOnlineApiKeyChange: (String) -> Unit,
    onSaveOnlineApiKey: () -> Unit,
    onClearOnlineApiKey: () -> Unit,
    onOnlineQueryChange: (String) -> Unit,
    onCycleOnlineLanguage: () -> Unit,
    onSearchOnline: () -> Unit,
    onDownloadOnline: (OnlineSubtitleResult, OnlineSubtitleTarget) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            SubtitleSectionLabel("PISTA PRINCIPAL")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OptionChip("ABRIR ARCHIVO", tint = VortexPalette.Cyan, onClick = onLoadPrimary)
                OptionChip("DESACTIVAR", tint = VortexPalette.Magenta) { onSelect(null) }
            }
            Text(
                text = primaryName ?: "Usa una pista incluida o abre SRT, VTT, ASS o SSA.",
                style = MaterialTheme.typography.bodySmall,
                color = if (primaryName == null) VortexPalette.TextLow else VortexPalette.Neon,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        if (tracks.isNotEmpty()) {
            items(tracks, key = { "primary-${it.id}" }) { track ->
                TrackRow(
                    label = track.label,
                    selected = track.selected,
                    onClick = { onSelect(track.id) }
                )
            }
        }

        item {
            DelayControl(
                label = "SINCRONIZACIÓN PRINCIPAL",
                valueMs = primaryDelayMs,
                onDelta = onAdjustPrimaryDelay
            )
        }

        item {
            SubtitleSectionLabel("SEGUNDO SUBTÍTULO")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OptionChip("ABRIR SRT / VTT", tint = VortexPalette.Cyan, onClick = onLoadSecondary)
                if (secondaryName != null) {
                    OptionChip("QUITAR", tint = VortexPalette.Magenta, onClick = onRemoveSecondary)
                }
            }
            Text(
                text = secondaryName ?: "Superpone otro idioma sin reemplazar la pista principal.",
                style = MaterialTheme.typography.bodySmall,
                color = if (secondaryName == null) VortexPalette.TextLow else VortexPalette.Neon,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        if (secondaryName != null) {
            item {
                DelayControl(
                    label = "SINCRONIZACIÓN SECUNDARIA",
                    valueMs = secondaryDelayMs,
                    onDelta = onAdjustSecondaryDelay
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OptionChip("TAMAÑO · ${secondaryTextSize.label}", onClick = onCycleSecondaryTextSize)
                    OptionChip(
                        if (secondaryBackground) "FONDO · SÍ" else "FONDO · NO",
                        tint = if (secondaryBackground) VortexPalette.Neon else VortexPalette.TextHigh,
                        onClick = onToggleSecondaryBackground
                    )
                }
            }
        }

        item {
            SubtitleSectionLabel("ONLINE · OPENSUBTITLES")
            if (!onlineState.configured) {
                Text(
                    text = "Añade la API key gratuita de tu aplicación. Se cifra en este dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                SubtitleInput(
                    value = onlineState.apiKeyDraft,
                    placeholder = "API KEY",
                    secret = true,
                    onValueChange = onOnlineApiKeyChange
                )
                Row(Modifier.padding(top = 7.dp)) {
                    OptionChip("GUARDAR KEY", tint = VortexPalette.Cyan, onClick = onSaveOnlineApiKey)
                }
            } else {
                SubtitleInput(
                    value = onlineState.query,
                    placeholder = "PELÍCULA, SERIE O EPISODIO",
                    onValueChange = onOnlineQueryChange
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    OptionChip(
                        "IDIOMA · ${onlineState.language.label}",
                        tint = VortexPalette.Neon,
                        onClick = onCycleOnlineLanguage
                    )
                    OptionChip(
                        if (onlineState.searching) "BUSCANDO…" else "BUSCAR",
                        tint = VortexPalette.Cyan,
                        onClick = { if (!onlineState.searching) onSearchOnline() }
                    )
                    OptionChip("CAMBIAR KEY", tint = VortexPalette.TextLow, onClick = onClearOnlineApiKey)
                }
            }
        }

        if (onlineState.configured && onlineState.results.isEmpty() && !onlineState.searching) {
            item {
                Text(
                    text = "Busca y carga el resultado directamente como pista principal o segundo subtítulo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexPalette.TextLow
                )
            }
        }

        items(onlineState.results, key = { "online-${it.fileId}" }) { result ->
            OnlineSubtitleRow(
                result = result,
                downloading = onlineState.downloadingFileId == result.fileId,
                onPrimary = { onDownloadOnline(result, OnlineSubtitleTarget.PRIMARY) },
                onSecondary = { onDownloadOnline(result, OnlineSubtitleTarget.SECONDARY) }
            )
        }

        status?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("ERROR")) {
                        VortexPalette.Magenta
                    } else {
                        VortexPalette.Cyan
                    },
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun SubtitleInput(
    value: String,
    placeholder: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = TextStyle(
            color = VortexPalette.TextHigh,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { field ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.labelMedium,
                        color = VortexPalette.TextLow
                    )
                }
                field()
            }
        }
    )
}

@Composable
private fun OnlineSubtitleRow(
    result: OnlineSubtitleResult,
    downloading: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .padding(10.dp)
    ) {
        Text(
            text = result.release,
            style = MaterialTheme.typography.bodyMedium,
            color = VortexPalette.TextHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val flags = buildList {
            add(result.language.uppercase(Locale.ROOT))
            add("${result.downloadCount} DESCARGAS")
            if (result.hearingImpaired) add("CC")
            if (result.machineTranslated) add("TRADUCCIÓN AUTOMÁTICA")
        }.joinToString(" · ")
        Text(
            text = flags,
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
        Row(
            modifier = Modifier
                .padding(top = 7.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (downloading) {
                OptionChip("DESCARGANDO…", tint = VortexPalette.Cyan) {}
            } else {
                OptionChip("PRINCIPAL", tint = VortexPalette.Neon, onClick = onPrimary)
                OptionChip("SEGUNDO", tint = VortexPalette.Cyan, onClick = onSecondary)
            }
        }
    }
}

@Composable
private fun SubtitleSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun DelayControl(label: String, valueMs: Long, onDelta: (Long) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format(Locale.getDefault(), "%+.1f s", valueMs / 1_000f),
                style = MaterialTheme.typography.labelMedium,
                color = if (valueMs == 0L) VortexPalette.TextMid else VortexPalette.Neon
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OptionChip("−1 s") { onDelta(-1_000L) }
            OptionChip("−0,1 s") { onDelta(-100L) }
            OptionChip("CERO", tint = VortexPalette.Cyan) { onDelta(-valueMs) }
            OptionChip("+0,1 s") { onDelta(100L) }
            OptionChip("+1 s") { onDelta(1_000L) }
        }
    }
}

/**
 * Selector de aspecto al estilo VLC. Los modos de encaje van arriba y las
 * relaciones forzadas debajo, porque son el recurso para cuando el vídeo trae mal los
 * metadatos y ningún encaje automático lo cuadra.
 */
@Composable
private fun AspectOptions(
    current: AspectPreset,
    onSelect: (AspectPreset) -> Unit
) {
    val modes = listOf(
        AspectPreset.FIT,
        AspectPreset.CROP,
        AspectPreset.STRETCH,
        AspectPreset.ORIGINAL
    )
    val ratios = AspectPreset.entries - modes.toSet()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.take(2).forEach { mode ->
            OptionChip(
                label = mode.label,
                tint = if (mode == current) VortexPalette.Neon else VortexPalette.TextHigh
            ) { onSelect(mode) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.drop(2).forEach { mode ->
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
        ratios.drop(3).take(3).forEach { ratio ->
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
        ratios.drop(6).forEach { ratio ->
            OptionChip(
                label = ratio.label,
                tint = if (ratio == current) VortexPalette.Neon else VortexPalette.TextHigh
            ) { onSelect(ratio) }
        }
    }
    Text(
        text = "Ajustar conserva el cuadro completo; Llenar ocupa la ventana recortando los bordes.",
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
