package com.vortex.player.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DestinationStore
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadStatus
import com.vortex.player.download.VideoQuality
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Centro de descargas. La composición vertical sigue el orden real de la decisión:
 * primero el enlace, luego qué formato quieres, luego dónde va, y por último la cola.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current

    val url by viewModel.url.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val codec by viewModel.codec.collectAsStateWithLifecycle()
    val bitrate by viewModel.bitrate.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val subtitles by viewModel.embedSubtitles.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val activeJobId by viewModel.activeJobId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val engineReady by viewModel.engineReady.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2_600)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = VortexPalette.TextHigh
                        )
                    }
                    Text(
                        text = "DESCARGAS",
                        style = MaterialTheme.typography.labelLarge,
                        color = VortexPalette.TextHigh,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::updateEngine) {
                        Icon(
                            Icons.Filled.SystemUpdateAlt,
                            contentDescription = "Actualizar yt-dlp",
                            tint = VortexPalette.TextMid
                        )
                    }
                }
            }

            item {
                EngineBanner(engineReady)
            }

            item {
                UrlField(
                    url = url,
                    onUrlChange = viewModel::setUrl,
                    onPaste = {
                        clipboard.getText()?.text?.let(viewModel::setUrl)
                    },
                    onEnqueue = viewModel::enqueue
                )
            }

            item {
                SectionLabel("FORMATO")
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DownloadKind.entries.forEach { option ->
                        Chip(
                            label = option.label,
                            selected = option == kind,
                            onClick = { viewModel.setKind(option) }
                        )
                    }
                }
            }

            item {
                if (kind == DownloadKind.VIDEO) {
                    SectionLabel("CALIDAD DE VÍDEO")
                    ChipRow(
                        options = VideoQuality.entries.map { it.label },
                        selectedIndex = VideoQuality.entries.indexOf(quality),
                        onSelect = { viewModel.setQuality(VideoQuality.entries[it]) }
                    )
                } else {
                    SectionLabel("CÓDEC")
                    ChipRow(
                        options = AudioCodec.entries.map { it.label },
                        selectedIndex = AudioCodec.entries.indexOf(codec),
                        onSelect = { viewModel.setCodec(AudioCodec.entries[it]) }
                    )
                    SectionLabel("BITRATE")
                    ChipRow(
                        options = AudioBitrate.entries.map { it.label },
                        selectedIndex = AudioBitrate.entries.indexOf(bitrate),
                        onSelect = { viewModel.setBitrate(AudioBitrate.entries[it]) }
                    )
                }
            }

            item {
                SectionLabel("OPCIONES")
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip(
                        label = "LISTA COMPLETA",
                        selected = playlist,
                        onClick = viewModel::togglePlaylist
                    )
                    if (kind == DownloadKind.VIDEO) {
                        Chip(
                            label = "SUBTÍTULOS",
                            selected = subtitles,
                            onClick = viewModel::toggleSubtitles
                        )
                    }
                }
                if (playlist) {
                    Text(
                        text = "Cada lista se guardará en su propia carpeta, con las pistas numeradas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPalette.TextLow,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            item {
                SectionLabel("DESTINO")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
                        .border(0.5.dp, VortexPalette.Outline, VortexShapes.medium)
                        .clickable(onClick = onPickFolder)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = VortexPalette.Neon)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = DestinationStore.displayName(context, destination),
                            style = MaterialTheme.typography.titleMedium,
                            color = VortexPalette.TextHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (destination == null) {
                                "Carpeta por defecto · toca para elegir otra"
                            } else {
                                "Carpeta elegida · toca para cambiar"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexPalette.TextLow
                        )
                    }
                    if (destination != null) {
                        IconButton(onClick = viewModel::useDefaultDestination) {
                            Icon(
                                Icons.Filled.Replay,
                                contentDescription = "Volver al destino por defecto",
                                tint = VortexPalette.TextLow
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "COLA · ${downloads.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = VortexPalette.TextLow,
                        modifier = Modifier.weight(1f)
                    )
                    if (downloads.any { it.status.isTerminal }) {
                        Text(
                            text = "LIMPIAR",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.Magenta,
                            modifier = Modifier
                                .clickable(onClick = viewModel::clearFinished)
                                .padding(6.dp)
                        )
                    }
                }
            }

            items(downloads, key = { it.id }) { job ->
                DownloadRow(
                    job = job,
                    isActive = job.id == activeJobId,
                    onCancel = viewModel::cancelCurrent,
                    onRetry = { viewModel.retry(job.id) },
                    onRemove = { viewModel.remove(job.id) }
                )
            }

            if (downloads.isEmpty()) {
                item {
                    Text(
                        text = "Nada en la cola. Pega un enlace de YouTube, Vimeo, Twitter, " +
                            "Twitch, TikTok o cualquier otra fuente compatible.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VortexPalette.TextLow,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp)
                    )
                }
            }
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = VortexPalette.Graphite,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(VortexPalette.Neon, VortexShapes.small)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun EngineBanner(engineReady: Boolean?) {
    val (text, color) = when (engineReady) {
        null -> "Preparando el motor de descargas…" to VortexPalette.TextLow
        true -> null to VortexPalette.Neon
        false -> "El motor de descargas no arrancó. Reinstala o revisa el espacio libre." to
            VortexPalette.Magenta
    }
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun UrlField(
    url: String,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onEnqueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .background(VortexPalette.GraphiteRaised, VortexShapes.small)
                .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (url.isEmpty()) {
                Text(
                    text = "https://…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexPalette.TextLow
                )
            }
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = VortexPalette.TextHigh
                ),
                cursorBrush = SolidColor(VortexPalette.Neon),
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(onClick = onPaste) {
            Icon(
                Icons.Filled.ContentPaste,
                contentDescription = "Pegar",
                tint = VortexPalette.TextMid
            )
        }
        Text(
            text = "BAJAR",
            style = MaterialTheme.typography.labelLarge,
            color = VortexPalette.Graphite,
            modifier = Modifier
                .background(VortexPalette.Neon, VortexShapes.small)
                .clickable(onClick = onEnqueue)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            Chip(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) VortexPalette.Graphite else VortexPalette.TextMid,
        modifier = Modifier
            .background(
                if (selected) VortexPalette.Neon else VortexPalette.GraphiteHigh,
                VortexShapes.small
            )
            .border(
                0.5.dp,
                if (selected) VortexPalette.Neon else VortexPalette.Outline,
                VortexShapes.small
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun DownloadRow(
    job: DownloadEntity,
    isActive: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val accent = when (job.status) {
        DownloadStatus.COMPLETED -> VortexPalette.Neon
        DownloadStatus.FAILED -> VortexPalette.Magenta
        DownloadStatus.CANCELLED -> VortexPalette.TextLow
        else -> VortexPalette.Cyan
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(
                if (isActive) 1.dp else 0.5.dp,
                if (isActive) accent.copy(alpha = 0.6f) else VortexPalette.Outline,
                VortexShapes.medium
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = job.title.ifBlank { job.url },
                    style = MaterialTheme.typography.titleMedium,
                    color = VortexPalette.TextHigh,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = job.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                    Text(
                        text = job.kind.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow
                    )
                    if (job.etaSeconds > 0 && !job.status.isTerminal) {
                        Text(
                            text = "queda " + formatDuration(job.etaSeconds * 1000),
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow
                        )
                    }
                }
            }

            when {
                isActive -> IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Cancelar",
                        tint = VortexPalette.Magenta
                    )
                }
                job.status == DownloadStatus.FAILED ||
                    job.status == DownloadStatus.CANCELLED -> IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Reintentar",
                        tint = VortexPalette.Cyan
                    )
                }
                else -> IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Quitar",
                        tint = VortexPalette.TextLow
                    )
                }
            }
        }

        if (!job.status.isTerminal) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(VortexPalette.Outline)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(job.progress)
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(VortexPalette.Cyan, VortexPalette.Neon)
                            )
                        )
                )
            }
            if (job.statusLine.isNotBlank()) {
                Text(
                    text = job.statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = VortexPalette.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        job.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.Magenta,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (job.status == DownloadStatus.COMPLETED) {
            Text(
                text = buildString {
                    append("${job.fileCount} archivo${if (job.fileCount == 1) "" else "s"}")
                    job.playlistFolder?.let { append(" · carpeta “$it”") }
                    job.outputLocation?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
