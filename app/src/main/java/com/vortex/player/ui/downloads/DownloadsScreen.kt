package com.vortex.player.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DestinationStore
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadStatus
import com.vortex.player.download.PlaylistProgress
import com.vortex.player.download.SponsorCategory
import com.vortex.player.download.SponsorMode
import com.vortex.player.download.SponsorSettings
import com.vortex.player.download.VideoContainer
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
    val container by viewModel.container.collectAsStateWithLifecycle()
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
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    // Mientras haya una lista resuelta esperando decisión, ocupa la pantalla entera: es
    // el paso siguiente del mismo flujo, no una capa encima de la cola.
    selection?.let { pending ->
        BackHandler { viewModel.cancelSelection() }
        PlaylistSelectionScreen(
            selection = pending,
            onToggle = viewModel::toggleTrack,
            onSelectAll = viewModel::selectAllTracks,
            onOnlyMissing = viewModel::selectOnlyMissing,
            onConfirm = viewModel::confirmSelection,
            onCancel = viewModel::cancelSelection
        )
        return
    }

    var confirmClearAll by remember { mutableStateOf(false) }

    // Los ajustes arrancan plegados. Se cambian una vez y valen para todas las descargas,
    // mientras que la cola se mira constantemente: tenerlos siempre desplegados empujaba
    // lo que de verdad se consulta al fondo de la pantalla.
    var optionsExpanded by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    val pending = remember(downloads) { downloads.filter { !it.status.isTerminal } }
    val finished = remember(downloads) { downloads.filter { it.status.isTerminal } }
    val visible = if (showDone) finished else pending

    val optionsSummary = remember(kind, quality, container, codec, bitrate, playlist, subtitles, sponsorSettings) {
        buildList {
            add(kind.label)
            if (kind == DownloadKind.VIDEO) {
                add(container.label)
                add(quality.label)
            } else {
                add(codec.label)
            }
            if (playlist) add("LISTA")
            if (subtitles && kind == DownloadKind.VIDEO) add("SUBS")
            if (sponsorSettings.isActive) add("SPONSOR")
        }.joinToString(" · ")
    }

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

            item {
                OptionsSummaryBar(
                    summary = optionsSummary,
                    destination = DestinationStore.displayName(context, destination),
                    expanded = optionsExpanded,
                    onToggle = { optionsExpanded = !optionsExpanded }
                )
            }

            if (optionsExpanded && !isSpotify) {
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

            if (optionsExpanded) item {
                // De Spotify siempre sale audio, así que las opciones de vídeo sobran.
                if (kind == DownloadKind.VIDEO && !isSpotify) {
                    SectionLabel("CALIDAD DE VÍDEO")
                    ChipRow(
                        options = VideoQuality.entries.map { it.label },
                        selectedIndex = VideoQuality.entries.indexOf(quality),
                        onSelect = { viewModel.setQuality(VideoQuality.entries[it]) }
                    )
                    SectionLabel("FORMATO DE ARCHIVO")
                    ChipRow(
                        options = VideoContainer.entries.map { it.label },
                        selectedIndex = VideoContainer.entries.indexOf(container),
                        onSelect = { viewModel.setContainer(VideoContainer.entries[it]) }
                    )
                    Text(
                        text = when (container) {
                            VideoContainer.MP4 ->
                                "El que reproduce cualquier cosa: galería, WhatsApp, " +
                                    "televisores, editores. Es la opción recomendada."
                            VideoContainer.MKV ->
                                "Acepta cualquier códec sin reconvertir. Útil en 4K, donde " +
                                    "el vídeo viene en AV1 o VP9, pero no todo lo reproduce."
                            VideoContainer.WEBM ->
                                "Formato de YouTube. Se guarda sin tocar nada, pero muchas " +
                                    "apps y televisores no lo abren."
                            VideoContainer.ORIGINAL ->
                                "Se guarda tal y como venga de la fuente, sin reenvasar. " +
                                    "Lo más rápido y lo menos predecible."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPalette.TextLow,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
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

            if (optionsExpanded && !isSpotify) {
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
                                "pistas numeradas. Un enlace suelto se descarga como un " +
                                "archivo más, sin carpeta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexPalette.TextLow,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (optionsExpanded) item {
                SponsorBlockSection(
                    settings = sponsorSettings,
                    onMode = viewModel::setSponsorMode,
                    onToggleCategory = viewModel::toggleSponsorCategory
                )
            }

            if (optionsExpanded) item {
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
                    // Lo activo y el historial compartían lista, así que una descarga nueva
                    // aparecía entre decenas de entradas viejas.
                    QueueTab(
                        label = "EN CURSO",
                        count = pending.size,
                        selected = !showDone,
                        onClick = { showDone = false }
                    )
                    Spacer(Modifier.width(16.dp))
                    QueueTab(
                        label = "HECHAS",
                        count = finished.size,
                        selected = showDone,
                        onClick = { showDone = true }
                    )
                    Spacer(Modifier.weight(1f))
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

            items(visible, key = { it.id }) { job ->
                DownloadRow(
                    job = job,
                    isActive = job.id == activeJobId,
                    onCancel = viewModel::cancelCurrent,
                    onRetry = { viewModel.retry(job.id) },
                    onRemove = { viewModel.remove(job.id) }
                )
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        text = when {
                            showDone -> "Aún no has terminado ninguna descarga."
                            // Sin este aviso, terminar la última descarga vacía la pestaña
                            // y parece que lo bajado se ha esfumado.
                            finished.isNotEmpty() ->
                                "No queda nada descargando. Lo ya bajado está en HECHAS."
                            else -> "Nada en la cola. Pega un enlace de YouTube, Vimeo, " +
                                "Twitter, Twitch, TikTok o cualquier otra fuente compatible."
                        },
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

/**
 * Resumen plegable de los ajustes.
 *
 * Plegada, la barra sigue diciendo con qué se va a bajar —formato, calidad, si hay lista—,
 * que es lo único que se comprueba de un vistazo antes de pegar un enlace. El formulario
 * entero sigue estando a un toque para cuando de verdad haya que cambiar algo.
 */
@Composable
private fun OptionsSummaryBar(
    summary: String,
    destination: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = VortexPalette.TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // El destino se queda a la vista aunque los ajustes estén plegados: "¿dónde ha
            // ido a parar el archivo?" es lo que más se pregunta, y esconderlo detrás de un
            // desplegable sería peor que la pantalla larga que estamos arreglando.
            Text(
                text = "→ $destination",
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Plegar ajustes" else "Desplegar ajustes",
            tint = VortexPalette.TextLow,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Pestaña de la cola. El subrayado marca la activa; el número, cuánto hay detrás. */
@Composable
private fun QueueTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = "$label · $count",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) VortexPalette.Neon else VortexPalette.TextLow,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            Modifier
                .width(if (selected) 28.dp else 0.dp)
                .height(2.dp)
                .background(VortexPalette.Neon)
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

/**
 * Formato de salida, tal y como acabará el fichero. En vídeo siempre es MP4 desde que se
 * fuerza el envase; en audio, el códec que se haya pedido.
 */
private val DownloadEntity.formatLabel: String
    get() = if (kind == DownloadKind.VIDEO) "MP4" else audioCodec.label

/**
 * Miniatura de la descarga.
 *
 * El dato ya se guardaba al consultar la fuente, pero la cola no lo pintaba: era una lista
 * de títulos donde no se distinguía de un vistazo qué se estaba bajando. Cuando la fuente
 * no da imagen —o aún no se ha consultado— se cae a un icono según el formato, que al
 * menos separa vídeo de música.
 */
@Composable
private fun JobThumbnail(job: DownloadEntity) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(54.dp)
            .background(VortexPalette.GraphiteHigh, VortexShapes.small),
        contentAlignment = Alignment.Center
    ) {
        if (job.thumbnailUrl != null) {
            AsyncImage(
                model = job.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(VortexShapes.small)
            )
        } else {
            Icon(
                imageVector = if (job.kind == DownloadKind.VIDEO) {
                    Icons.Filled.Movie
                } else {
                    Icons.Filled.MusicNote
                },
                contentDescription = null,
                tint = VortexPalette.Outline,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Cápsula de metadato: formato, calidad, posición en la lista. */
@Composable
private fun Badge(text: String, filled: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (filled) VortexPalette.Graphite else VortexPalette.TextMid,
        modifier = Modifier
            .background(
                if (filled) VortexPalette.Neon else Color.Transparent,
                VortexShapes.extraSmall
            )
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.border(0.5.dp, VortexPalette.Outline, VortexShapes.extraSmall)
                }
            )
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

/** Barra de avance. Existe suelta porque con lista se pintan dos, una sobre otra. */
@Composable
private fun ProgressBar(
    fraction: Float,
    height: Dp,
    modifier: Modifier = Modifier,
    dim: Boolean = false
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(VortexPalette.Outline)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(
                    if (dim) {
                        Brush.horizontalGradient(
                            listOf(
                                VortexPalette.Cyan.copy(alpha = 0.45f),
                                VortexPalette.Neon.copy(alpha = 0.45f)
                            )
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(VortexPalette.Cyan, VortexPalette.Neon)
                        )
                    }
                )
        )
    }
}

/**
 * Lo que la lista ya ha traído, pista a pista.
 *
 * Es la respuesta a "sólo veo un vídeo": una lista es un único trabajo en la cola porque
 * yt-dlp la descarga en un solo proceso, así que en vez de partirla en filas —lo que
 * costaría la carpeta y la numeración que la propia lista genera— se despliega aquí dentro.
 */
@Composable
private fun PlaylistItems(job: DownloadEntity) {
    val items = remember(job.playlistItems) {
        job.playlistItems.split('\n').filter { it.isNotBlank() }
    }
    if (job.playlistCount <= 1 || items.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    // Plegada enseña sólo la cola de la lista: con cuarenta pistas, la tarjeta ocuparía
    // varias pantallas y enterraría el resto de la cola.
    val shown = if (expanded) items else items.takeLast(3)
    val hidden = items.size - shown.size

    Column(Modifier.padding(top = 8.dp)) {
        if (hidden > 0 && !expanded) {
            Text(
                text = "+$hidden anterior${if (hidden == 1) "" else "es"}",
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(vertical = 3.dp)
            )
        }
        // La posición se calcula por desplazamiento y no buscando el nombre: dos pistas de
        // una misma lista pueden llamarse igual y todas apuntarían a la primera.
        val offset = items.size - shown.size
        shown.forEachIndexed { position, name ->
            val number = offset + position + 1
            val isCurrent = number == items.size && !job.status.isTerminal
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Text(
                    text = if (isCurrent) "▶" else "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) VortexPalette.Cyan else VortexPalette.Neon
                )
                Text(
                    text = "$number. $name",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) VortexPalette.TextHigh else VortexPalette.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expanded && items.size > 3) {
            Text(
                text = "Plegar",
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                modifier = Modifier
                    .clickable { expanded = false }
                    .padding(vertical = 3.dp)
            )
        }
    }
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
        Row(verticalAlignment = Alignment.Top) {
            JobThumbnail(job)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = job.title.ifBlank { job.url },
                    style = MaterialTheme.typography.titleMedium,
                    color = VortexPalette.TextHigh,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Las etiquetas van en su propia fila y en cápsulas: antes competían con el
                // estado en una única línea de texto suelto y no se distinguía qué era qué.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 5.dp)
                ) {
                    Badge(text = job.formatLabel)
                    if (job.kind == DownloadKind.VIDEO) {
                        Badge(text = job.videoQuality.label)
                    }
                    if (job.playlistCount > 1) {
                        Badge(
                            text = "LISTA ${job.playlistIndex}/${job.playlistCount}",
                            filled = true
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 5.dp)
                ) {
                    Text(
                        text = job.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
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
            val isPlaylist = job.playlistCount > 1

            // Con lista, la barra gruesa es la de todo el trabajo y la fina la del fichero
            // en curso. Al revés —que es como estaba— sólo se veía una barra que volvía a
            // cero en cada pista, sin pista alguna de cuánto quedaba en realidad.
            ProgressBar(
                fraction = PlaylistProgress.overall(
                    job.playlistIndex,
                    job.playlistCount,
                    job.progress
                ),
                height = 3.dp,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (isPlaylist) {
                ProgressBar(
                    fraction = job.progress,
                    height = 2.dp,
                    dim = true,
                    modifier = Modifier.padding(top = 3.dp)
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

        PlaylistItems(job)

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
