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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.vortex.player.download.SponsorCategory
import com.vortex.player.download.SponsorMode
import com.vortex.player.download.SponsorSettings
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
    val isSpotify by viewModel.isSpotifyLink.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val partialWarning by viewModel.partialWarning.collectAsStateWithLifecycle()
    val failedCount by viewModel.failedCount.collectAsStateWithLifecycle()
    val sponsorSettings by viewModel.sponsor.collectAsStateWithLifecycle()

    var confirmClearAll by remember { mutableStateOf(false) }

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

            if (partialWarning) {
                item { PartialListWarning(onDismiss = viewModel::dismissPartialWarning) }
            }

            if (isSpotify) {
                item { SpotifyNotice(resolving) }
            }

            if (!isSpotify) {
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
            }

            item {
                // De Spotify siempre sale audio, así que las opciones de vídeo sobran.
                if (kind == DownloadKind.VIDEO && !isSpotify) {
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

            if (!isSpotify) {
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
                            text = "Cada lista se guardará en su propia carpeta, con las " +
                                "pistas numeradas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexPalette.TextLow,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            item {
                SponsorBlockSection(
                    settings = sponsorSettings,
                    onMode = viewModel::setSponsorMode,
                    onToggleCategory = viewModel::toggleSponsorCategory
                )
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
                    if (downloads.isNotEmpty()) {
                        QueueMenu(
                            hasFinished = downloads.any { it.status.isTerminal },
                            hasPending = downloads.any { !it.status.isTerminal },
                            failedCount = failedCount,
                            onRetryFailed = viewModel::retryAllFailed,
                            onCancelPending = viewModel::cancelPending,
                            onClearFinished = viewModel::clearFinished,
                            onClearAll = { confirmClearAll = true }
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

        if (confirmClearAll) {
            AlertDialog(
                onDismissRequest = { confirmClearAll = false },
                containerColor = VortexPalette.GraphiteRaised,
                titleContentColor = VortexPalette.TextHigh,
                textContentColor = VortexPalette.TextMid,
                title = {
                    Text("VACIAR LA COLA", style = MaterialTheme.typography.labelLarge)
                },
                text = {
                    Text(
                        text = "Se quitarán las ${downloads.size} entradas, incluidas las " +
                            "que estén descargando ahora. Los archivos ya guardados en el " +
                            "destino no se tocan.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClearAll = false
                            viewModel.clearAll()
                        }
                    ) {
                        Text("VACIAR", color = VortexPalette.Magenta)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearAll = false }) {
                        Text("CANCELAR", color = VortexPalette.TextLow)
                    }
                }
            )
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

/**
 * Explica qué va a pasar con un enlace de Spotify. Conviene decirlo: el usuario espera
 * que el audio salga de Spotify, y lo que ocurre es otra cosa.
 */
/**
 * SponsorBlock: qué hacer con los tramos que la comunidad ha marcado en el vídeo.
 *
 * Las categorías sólo aparecen si hay un modo activo; enseñar ocho casillas que no hacen
 * nada porque el modo está desactivado sería ruido.
 */
@Composable
private fun SponsorBlockSection(
    settings: SponsorSettings,
    onMode: (SponsorMode) -> Unit,
    onToggleCategory: (SponsorCategory) -> Unit
) {
    Column {
        SectionLabel("SPONSORBLOCK")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SponsorMode.entries.forEach { mode ->
                Chip(
                    label = mode.label,
                    selected = mode == settings.mode,
                    onClick = { onMode(mode) }
                )
            }
        }

        Text(
            text = settings.mode.description,
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )

        if (settings.mode == SponsorMode.OFF) return@Column

        Text(
            text = "CATEGORÍAS",
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp)
        )

        SponsorCategory.entries.forEach { category ->
            val checked = category in settings.categories
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCategory(category) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (checked) {
                        Icons.Filled.CheckBox
                    } else {
                        Icons.Filled.CheckBoxOutlineBlank
                    },
                    contentDescription = null,
                    tint = if (checked) VortexPalette.Neon else VortexPalette.TextLow,
                    modifier = Modifier.size(19.dp)
                )
                Column {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (checked) VortexPalette.TextHigh else VortexPalette.TextMid
                    )
                    Text(
                        text = category.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPalette.TextLow
                    )
                }
            }
        }

        if (settings.categories.isEmpty()) {
            Text(
                text = "Sin ninguna categoría marcada, SponsorBlock no hará nada.",
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.Amber,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        Text(
            text = "Sólo se aplica a YouTube. En otras fuentes se ignora sin dar error.",
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

/** Acciones que afectan a la cola entera, agrupadas para no llenar la cabecera. */
@Composable
private fun QueueMenu(
    hasFinished: Boolean,
    hasPending: Boolean,
    failedCount: Int,
    onRetryFailed: () -> Unit,
    onCancelPending: () -> Unit,
    onClearFinished: () -> Unit,
    onClearAll: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "GESTIONAR",
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextMid
            )
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Acciones de la cola",
                tint = VortexPalette.TextMid,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = VortexPalette.GraphiteRaised
        ) {
            if (failedCount > 0) {
                QueueMenuItem(
                    label = "Reintentar fallidas ($failedCount)",
                    tint = VortexPalette.Cyan
                ) { open = false; onRetryFailed() }
            }
            if (hasPending) {
                QueueMenuItem(label = "Cancelar pendientes") {
                    open = false
                    onCancelPending()
                }
            }
            if (hasFinished) {
                QueueMenuItem(label = "Limpiar terminadas") {
                    open = false
                    onClearFinished()
                }
            }
            QueueMenuItem(label = "Vaciar la cola entera", tint = VortexPalette.Magenta) {
                open = false
                onClearAll()
            }
        }
    }
}

@Composable
private fun QueueMenuItem(
    label: String,
    tint: androidx.compose.ui.graphics.Color = VortexPalette.TextHigh,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
        },
        onClick = onClick
    )
}

@Composable
private fun SpotifyNotice(resolving: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(0.5.dp, VortexPalette.Cyan.copy(alpha = 0.4f), VortexShapes.medium)
            .padding(12.dp)
    ) {
        Text(
            text = if (resolving) "LEYENDO SPOTIFY…" else "ENLACE DE SPOTIFY",
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.Cyan
        )
        Text(
            text = "De Spotify sólo se leen los datos de las canciones: título, artista, " +
                "duración y portada. El audio se busca después en YouTube Music y se " +
                "etiqueta con esos datos. Cada canción entra en la cola por separado.",
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextMid,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * La lista se quedó en 100. Es importante decirlo: si no, el usuario cree que su
 * playlist de trescientas se bajó entera y descubre el hueco semanas después.
 */
@Composable
private fun PartialListWarning(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(0.5.dp, VortexPalette.Amber.copy(alpha = 0.5f), VortexShapes.medium)
            .padding(12.dp)
    ) {
        Text(
            text = "LISTA INCOMPLETA",
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.Amber
        )
        Text(
            text = "Spotify sólo dejó leer las primeras 100 canciones. Vuelve a pegar el " +
                "enlace dentro de un rato para intentar el resto: el acceso rápido a " +
                "listas largas está limitado y suele desbloquearse solo.",
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextMid,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = "ENTENDIDO",
            style = MaterialTheme.typography.labelLarge,
            color = VortexPalette.Amber,
            modifier = Modifier
                .padding(top = 10.dp)
                .clickable(onClick = onDismiss)
                .padding(vertical = 4.dp)
        )
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
