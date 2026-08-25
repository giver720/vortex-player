@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vortex.player.ui.queue

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.MediaRepository
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackQueueItem
import com.vortex.player.playback.PlaybackService
import com.vortex.player.playback.QueueOrigin
import com.vortex.player.playback.QueueSortMode
import com.vortex.player.playback.splitQueueDuplicates
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.rememberThumbnailRequest
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes
import com.vortex.player.ui.theme.VortexTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class QueueActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = MediaRepository.get(this)
        setContent {
            VortexTheme {
                val library by repository.library.collectAsStateWithLifecycle()
                QueueScreen(library = library, repository = repository, onBack = ::finish)
            }
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, QueueActivity::class.java))
    }
}

private enum class QueueMode { QUEUE, PICKER }
private enum class QueueMediaKind(val label: String) { ALL("TODO"), AUDIO("AUDIO"), VIDEO("VÍDEO") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QueueScreen(
    library: LibraryState,
    repository: MediaRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val queue by PlaybackHub.queue.collectAsStateWithLifecycle()
    val currentIndex by PlaybackHub.currentIndex.collectAsStateWithLifecycle()
    val autoplay by PlaybackHub.autoplay.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(QueueMode.QUEUE) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var playedExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<Set<String>?>(null) }
    var duplicateRequest by remember { mutableStateOf<DuplicateRequest?>(null) }

    LaunchedEffect(queue.map { it.queueId }) {
        selected = selected.intersect(queue.mapTo(hashSetOf()) { it.queueId })
    }

    val safeCurrent = currentIndex.takeIf { it in queue.indices } ?: 0
    val current = queue.getOrNull(safeCurrent)
    val remaining = queue.drop(safeCurrent + 1).sumOf { it.media.durationMs.coerceAtLeast(0L) }

    fun removeWithUndo(ids: Set<String>) {
        if (ids.isEmpty()) return
        if (current?.queueId in ids) {
            pendingRemoval = ids
            return
        }
        val before = queue
        val currentId = current?.queueId
        PlaybackService.removeQueueItemsById(context, ids)
        selected = emptySet()
        scope.launch {
            val result = snackbar.showSnackbar(
                message = if (ids.size == 1) "Elemento quitado" else "${ids.size} elementos quitados",
                actionLabel = "DESHACER",
                withDismissAction = true
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                PlaybackService.restoreQueue(context, before, currentId)
            }
        }
    }

    Scaffold(
        containerColor = VortexPalette.Graphite,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VortexPalette.GraphiteHigh),
                navigationIcon = {
                    IconButton(onClick = { if (mode == QueueMode.PICKER) mode = QueueMode.QUEUE else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = VortexPalette.TextHigh)
                    }
                },
                title = {
                    Column {
                        Text(
                            if (mode == QueueMode.PICKER) "AÑADIR A LA FILA" else "FILA DE REPRODUCCIÓN",
                            style = MaterialTheme.typography.titleMedium,
                            color = VortexPalette.TextHigh
                        )
                        Text(
                            if (mode == QueueMode.PICKER) "${selected.size} seleccionados"
                            else "${queue.size} elementos · ${formatDuration(remaining)} restantes",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow
                        )
                    }
                },
                actions = {
                    if (mode == QueueMode.QUEUE) {
                        IconButton(onClick = { mode = QueueMode.PICKER; selected = emptySet(); query = "" }) {
                            Icon(Icons.Filled.Add, "Añadir", tint = VortexPalette.Neon)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, "Más opciones", tint = VortexPalette.TextMid)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                QueueMenuItem(Icons.Filled.Shuffle, "Mezclar pendientes") {
                                    menuExpanded = false; PlaybackService.shuffleUpcoming(context)
                                }
                                QueueMenuItem(Icons.AutoMirrored.Filled.Sort, "Ordenar pendientes") {
                                    menuExpanded = false; sortDialog = true
                                }
                                QueueMenuItem(Icons.Filled.ClearAll, "Limpiar reproducidos") {
                                    menuExpanded = false; PlaybackService.clearPlayed(context)
                                }
                                QueueMenuItem(Icons.Filled.Delete, "Limpiar siguientes") {
                                    menuExpanded = false; PlaybackService.clearUpcoming(context)
                                }
                                QueueMenuItem(Icons.Filled.Save, "Guardar como playlist") {
                                    menuExpanded = false; saveDialog = true
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (mode == QueueMode.PICKER) {
            QueuePicker(
                modifier = Modifier.padding(padding),
                entries = library.entries,
                selected = selected,
                onSelected = { selected = it },
                onCancel = { mode = QueueMode.QUEUE; selected = emptySet() },
                onAdd = { entries, playNext ->
                    val split = splitQueueDuplicates(entries, queue.drop(safeCurrent + 1))
                    if (split.duplicates.isEmpty()) {
                        PlaybackService.addToQueue(context, entries, playNext)
                        mode = QueueMode.QUEUE
                        selected = emptySet()
                    } else {
                        duplicateRequest = DuplicateRequest(split.newEntries, split.duplicates, playNext)
                    }
                }
            )
        } else {
            QueueContents(
                modifier = Modifier.padding(padding),
                queue = queue,
                currentIndex = safeCurrent,
                current = current,
                query = query,
                onQuery = { query = it },
                autoplay = autoplay,
                onToggleAutoplay = { PlaybackService.toggleAutoplay(context) },
                playedExpanded = playedExpanded,
                onPlayedExpanded = { playedExpanded = !playedExpanded },
                selected = selected,
                onSelected = { selected = it },
                onPlay = { PlaybackService.playQueueItem(context, it) },
                onMove = { id, index -> PlaybackService.moveQueueItem(context, id, index) },
                onRemove = ::removeWithUndo
            )
        }
    }

    duplicateRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { duplicateRequest = null },
            title = { Text("Ya están en la fila") },
            text = { Text("${request.duplicates.size} elementos están pendientes. Puedes omitirlos o añadir otra copia.") },
            confirmButton = {
                TextButton(onClick = {
                    PlaybackService.addToQueue(context, request.fresh + request.duplicates, request.playNext)
                    duplicateRequest = null; selected = emptySet(); mode = QueueMode.QUEUE
                }) { Text("AÑADIR TODO") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { duplicateRequest = null }) { Text("CANCELAR") }
                    TextButton(onClick = {
                        PlaybackService.addToQueue(context, request.fresh, request.playNext)
                        duplicateRequest = null; selected = emptySet(); mode = QueueMode.QUEUE
                    }, enabled = request.fresh.isNotEmpty()) { Text("OMITIR REPETIDOS") }
                }
            }
        )
    }

    pendingRemoval?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("¿Quitar lo que está sonando?") },
            text = { Text("Vórtex continuará con el siguiente elemento disponible.") },
            confirmButton = {
                TextButton(onClick = {
                    val before = queue
                    val currentId = current?.queueId
                    PlaybackService.removeQueueItemsById(context, ids)
                    pendingRemoval = null
                    scope.launch {
                        if (snackbar.showSnackbar("Elemento actual quitado", "DESHACER") ==
                            androidx.compose.material3.SnackbarResult.ActionPerformed
                        ) PlaybackService.restoreQueue(context, before, currentId)
                    }
                }) { Text("QUITAR", color = VortexPalette.Magenta) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("CANCELAR") } }
        )
    }

    if (sortDialog) {
        AlertDialog(
            onDismissRequest = { sortDialog = false },
            title = { Text("Ordenar lo siguiente") },
            text = {
                Column {
                    QueueSortMode.entries.forEach { modeValue ->
                        TextButton(onClick = {
                            PlaybackService.sortUpcoming(context, modeValue); sortDialog = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(modeValue.label(), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (saveDialog) {
        SaveQueueDialog(
            hasAutoplay = queue.any { it.origin == QueueOrigin.AUTOPLAY },
            onDismiss = { saveDialog = false },
            onSave = { name, includeAutoplay ->
                val entries = queue.filter { includeAutoplay || it.origin == QueueOrigin.MANUAL }.map { it.media }
                scope.launch {
                    repository.createPlaylistNow(name, entries, source = "QUEUE")
                    snackbar.showSnackbar("Playlist «$name» guardada")
                }
                saveDialog = false
            }
        )
    }
}

private data class DuplicateRequest(
    val fresh: List<MediaEntry>,
    val duplicates: List<MediaEntry>,
    val playNext: Boolean
)

@Composable
private fun QueueMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, action: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = action)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueContents(
    modifier: Modifier,
    queue: List<PlaybackQueueItem>,
    currentIndex: Int,
    current: PlaybackQueueItem?,
    query: String,
    onQuery: (String) -> Unit,
    autoplay: Boolean,
    onToggleAutoplay: () -> Unit,
    playedExpanded: Boolean,
    onPlayedExpanded: () -> Unit,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    onPlay: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize()) {
        current?.let { CurrentQueueCard(it, autoplay, onToggleAutoplay) }
        QueueSearch(query, onQuery)
        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selected.size,
                onCancel = { onSelected(emptySet()) },
                onNext = {
                    PlaybackService.moveQueueSelection(context, selected, true)
                    onSelected(emptySet())
                },
                onEnd = {
                    PlaybackService.moveQueueSelection(context, selected, false)
                    onSelected(emptySet())
                },
                onRemove = { onRemove(selected) }
            )
        }

        val term = query.trim()
        val indexed = queue.mapIndexed { index, item -> index to item }.filter { (_, item) ->
            term.isBlank() || item.media.matches(term)
        }
        val played = indexed.filter { it.first < currentIndex }
        val upcoming = indexed.filter { it.first > currentIndex }

        LazyColumn(Modifier.fillMaxSize()) {
            if (played.isNotEmpty()) {
                stickyHeader {
                    SectionHeader(
                        title = "REPRODUCIDO · ${played.size}",
                        expanded = playedExpanded,
                        onClick = onPlayedExpanded
                    )
                }
                if (playedExpanded) itemsIndexed(played, key = { _, value -> value.second.queueId }) { _, value ->
                    QueueRow(value.second, value.first, currentIndex, selected, onSelected, onPlay, onMove, onRemove)
                }
            }
            item { SectionHeader("A CONTINUACIÓN · ${upcoming.size}", expanded = null, onClick = {}) }
            itemsIndexed(upcoming, key = { _, value -> value.second.queueId }) { _, value ->
                QueueRow(value.second, value.first, currentIndex, selected, onSelected, onPlay, onMove, onRemove)
            }
            if (queue.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = VortexPalette.TextLow, modifier = Modifier.size(48.dp))
                    Text("La fila está vacía", color = VortexPalette.TextMid, modifier = Modifier.padding(top = 12.dp))
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CurrentQueueCard(item: PlaybackQueueItem, autoplay: Boolean, onToggleAutoplay: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(VortexPalette.Cyan.copy(alpha = .08f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QueueThumbnail(item.media)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("SONANDO AHORA", style = MaterialTheme.typography.labelSmall, color = VortexPalette.Neon)
            Text(item.media.title.ifBlank { item.media.displayName }, color = VortexPalette.TextHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.media.artist ?: item.media.folderName, color = VortexPalette.TextLow, maxLines = 1)
        }
        IconButton(onClick = { PlaybackService.togglePlayPause(context) }) {
            Icon(Icons.Filled.PlayArrow, "Reproducir o pausar", tint = VortexPalette.Neon)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(VortexPalette.GraphiteHigh).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = if (autoplay) VortexPalette.Cyan else VortexPalette.TextLow)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("AUTOPLAY LOCAL", style = MaterialTheme.typography.labelMedium, color = VortexPalette.TextHigh)
            Text("Añade 5 sugerencias cuando quedan 2", style = MaterialTheme.typography.labelSmall, color = VortexPalette.TextLow)
        }
        Switch(checked = autoplay, onCheckedChange = { onToggleAutoplay() })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    item: PlaybackQueueItem,
    index: Int,
    currentIndex: Int,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    onPlay: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var dragY by remember(item.queueId) { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val checked = item.queueId in selected
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd ->
                    PlaybackService.moveQueueSelection(context, setOf(item.queueId), true)
                SwipeToDismissBoxValue.EndToStart -> onRemove(setOf(item.queueId))
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // La mutación actualiza la lista; no dejamos una fila visible estacionada a un lado.
            false
        }
    )
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            val remove = swipeState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(if (remove) VortexPalette.Magenta.copy(alpha = .25f) else VortexPalette.Cyan.copy(alpha = .20f)).padding(horizontal = 18.dp),
                horizontalArrangement = if (remove) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (remove) Icons.Filled.Delete else Icons.Filled.SkipNext, null, tint = if (remove) VortexPalette.Magenta else VortexPalette.Cyan)
                Text(if (remove) "  QUITAR" else "  SIGUIENTE", color = VortexPalette.TextHigh)
            }
        }
    ) {
      Row(
        Modifier.fillMaxWidth()
            .background(if (checked) VortexPalette.Neon.copy(alpha = .10f) else VortexPalette.Graphite)
            .combinedClickable(
                onClick = { if (selected.isEmpty()) onPlay(item.queueId) else onSelected(selected.toggle(item.queueId)) },
                onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onSelected(selected.toggle(item.queueId)) }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", color = VortexPalette.TextLow, modifier = Modifier.width(28.dp))
        QueueThumbnail(item.media)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.media.title.ifBlank { item.media.displayName }, color = VortexPalette.TextHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(item.media.durationMs), style = MaterialTheme.typography.labelSmall, color = VortexPalette.TextLow)
                if (item.origin == QueueOrigin.AUTOPLAY) {
                    Text("  SUGERIDO", style = MaterialTheme.typography.labelSmall, color = VortexPalette.Cyan)
                }
            }
        }
        if (selected.isNotEmpty()) {
            Icon(if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (checked) VortexPalette.Neon else VortexPalette.TextLow)
        } else {
            Icon(
                Icons.Filled.DragHandle,
                "Mantén para reordenar",
                tint = VortexPalette.TextMid,
                modifier = Modifier.size(38.dp).pointerInput(item.queueId, index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDragEnd = {
                            val steps = (dragY / 64.dp.toPx()).roundToInt()
                            if (steps != 0) onMove(item.queueId, (index + steps).coerceAtLeast(currentIndex + 1))
                            dragY = 0f
                        },
                        onDragCancel = { dragY = 0f },
                        onDrag = { change, amount -> change.consume(); dragY += amount.y }
                    )
                }
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opciones", tint = VortexPalette.TextMid) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reproducir ahora") }, onClick = { menu = false; onPlay(item.queueId) })
                    DropdownMenuItem(text = { Text("Reproducir después") }, onClick = { menu = false; PlaybackService.moveQueueSelection(context, setOf(item.queueId), true) })
                    DropdownMenuItem(text = { Text("Mover al final") }, onClick = { menu = false; PlaybackService.moveQueueSelection(context, setOf(item.queueId), false) })
                    DropdownMenuItem(text = { Text("Quitar") }, onClick = { menu = false; onRemove(setOf(item.queueId)) })
                }
            }
        }
      }
    }
    HorizontalDivider(color = VortexPalette.Outline.copy(alpha = .45f))
}

@Composable
private fun QueueSearch(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(10.dp).background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(.5.dp, VortexPalette.Outline, VortexShapes.small).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = VortexPalette.TextLow)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (query.isBlank()) Text("Buscar en la fila", color = VortexPalette.TextLow)
            BasicTextField(query, onQuery, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = VortexPalette.TextHigh), cursorBrush = SolidColor(VortexPalette.Neon), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, "Limpiar", tint = VortexPalette.TextMid) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeader(title: String, expanded: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(VortexPalette.GraphiteHigh)
            .combinedClickable(onClick = onClick, onLongClick = {}).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = VortexPalette.Cyan, modifier = Modifier.weight(1f))
        expanded?.let { Icon(if (it) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = VortexPalette.TextMid) }
    }
}

@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onNext: () -> Unit, onEnd: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(VortexPalette.GraphiteRaised).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancelar", tint = VortexPalette.TextHigh) }
        Text("$count", color = VortexPalette.Neon, modifier = Modifier.weight(1f))
        TextButton(onClick = onNext) { Text("SIGUIENTE") }
        TextButton(onClick = onEnd) { Text("AL FINAL") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Quitar", tint = VortexPalette.Magenta) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuePicker(
    modifier: Modifier,
    entries: List<MediaEntry>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    onCancel: () -> Unit,
    onAdd: (List<MediaEntry>, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(QueueMediaKind.ALL) }
    val visible = remember(entries, query, kind) {
        entries.filter { entry ->
            (kind == QueueMediaKind.ALL || (kind == QueueMediaKind.VIDEO) == entry.isVideo) &&
                (query.isBlank() || entry.matches(query))
        }
    }
    Column(modifier.fillMaxSize()) {
        QueueSearch(query) { query = it }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            QueueMediaKind.entries.forEach { value ->
                Text(
                    value.label,
                    color = if (kind == value) VortexPalette.Graphite else VortexPalette.TextMid,
                    modifier = Modifier.background(if (kind == value) VortexPalette.Neon else VortexPalette.GraphiteHigh, VortexShapes.small)
                        .combinedClickable(onClick = { kind = value }, onLongClick = {}).padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            itemsIndexed(visible, key = { _, entry -> entry.uri.toString() }) { _, entry ->
                val id = entry.uri.toString()
                val checked = id in selected
                Row(
                    Modifier.fillMaxWidth().background(if (checked) VortexPalette.Neon.copy(alpha = .10f) else VortexPalette.Graphite)
                        .combinedClickable(onClick = { onSelected(selected.toggle(id)) }, onLongClick = { onSelected(selected.toggle(id)) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QueueThumbnail(entry)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(entry.title.ifBlank { entry.displayName }, color = VortexPalette.TextHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(entry.artist, entry.album, entry.folderName).joinToString(" · "), color = VortexPalette.TextLow, maxLines = 1)
                    }
                    Icon(if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (checked) VortexPalette.Neon else VortexPalette.TextLow)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(VortexPalette.GraphiteHigh).padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("CANCELAR") }
            TextButton(onClick = { onAdd(entries.filter { it.uri.toString() in selected }, true) }, enabled = selected.isNotEmpty()) {
                Icon(Icons.Filled.SkipNext, null); Text(" SIGUIENTE")
            }
            Button(onClick = { onAdd(entries.filter { it.uri.toString() in selected }, false) }, enabled = selected.isNotEmpty()) {
                Text("AL FINAL")
            }
        }
    }
}

@Composable
private fun SaveQueueDialog(hasAutoplay: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var includeAutoplay by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar fila") },
        text = {
            Column {
                BasicTextField(
                    name, { name = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = VortexPalette.TextHigh),
                    cursorBrush = SolidColor(VortexPalette.Neon),
                    modifier = Modifier.fillMaxWidth().border(1.dp, VortexPalette.Outline, RoundedCornerShape(6.dp)).padding(12.dp)
                )
                if (hasAutoplay) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Incluir sugerencias de autoplay", modifier = Modifier.weight(1f))
                    Switch(includeAutoplay, { includeAutoplay = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), includeAutoplay) }, enabled = name.isNotBlank()) { Text("GUARDAR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } }
    )
}

@Composable
private fun QueueThumbnail(entry: MediaEntry) {
    Box(
        Modifier.size(width = 66.dp, height = 42.dp).background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(.5.dp, VortexPalette.Outline, VortexShapes.small),
        contentAlignment = Alignment.Center
    ) {
        if (entry.isVideo) AsyncImage(
            model = rememberThumbnailRequest(entry), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        ) else Icon(Icons.Filled.MusicNote, null, tint = VortexPalette.Cyan)
    }
}

private fun MediaEntry.matches(term: String): Boolean =
    title.contains(term, true) || displayName.contains(term, true) ||
        artist?.contains(term, true) == true || album?.contains(term, true) == true ||
        folderName.contains(term, true) || folderPath.contains(term, true)

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

private fun QueueSortMode.label(): String = when (this) {
    QueueSortMode.TITLE -> "Título"
    QueueSortMode.ARTIST -> "Artista"
    QueueSortMode.ALBUM -> "Álbum"
    QueueSortMode.DURATION -> "Duración"
    QueueSortMode.RECENTLY_ADDED -> "Añadidos recientemente"
}
