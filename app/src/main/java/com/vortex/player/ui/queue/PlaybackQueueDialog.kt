package com.vortex.player.ui.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.vortex.player.data.MediaEntry
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.rememberThumbnailRequest
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

private enum class QueueView { QUEUE, PICKER }
private enum class MediaKind(val label: String) { ALL("TODO"), AUDIO("MP3 / AUDIO"), VIDEO("VÍDEO") }

/** Cola visual compartida por la biblioteca y el reproductor a pantalla completa. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackQueueDialog(
    queue: List<MediaEntry>,
    currentIndex: Int,
    availableMedia: List<MediaEntry>,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Set<Int>) -> Unit,
    onAdd: (List<MediaEntry>, Boolean) -> Unit
) {
    var view by remember { mutableStateOf(QueueView.QUEUE) }
    var selectedQueue by remember { mutableStateOf(emptySet<Int>()) }
    var selectedMedia by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(MediaKind.ALL) }

    LaunchedEffect(queue.size) {
        selectedQueue = selectedQueue.filterTo(emptySet<Int>().toMutableSet()) { it in queue.indices }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = VortexPalette.GraphiteRaised,
            shape = VortexShapes.large,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 18.dp)
                .border(0.5.dp, VortexPalette.Outline, VortexShapes.large)
        ) {
            if (view == QueueView.PICKER) {
                QueueMediaPicker(
                    entries = availableMedia,
                    selected = selectedMedia,
                    query = query,
                    kind = kind,
                    onQuery = { query = it },
                    onKind = { kind = it },
                    onToggle = { uri ->
                        selectedMedia = if (uri in selectedMedia) selectedMedia - uri
                        else selectedMedia + uri
                    },
                    onBack = {
                        view = QueueView.QUEUE
                        selectedMedia = emptySet()
                    },
                    onAdd = { playNext ->
                        val picked = availableMedia.filter { it.uri.toString() in selectedMedia }
                        if (picked.isNotEmpty()) onAdd(picked, playNext)
                        selectedMedia = emptySet()
                        view = QueueView.QUEUE
                    }
                )
            } else {
                QueueContents(
                    queue = queue,
                    currentIndex = currentIndex,
                    selected = selectedQueue,
                    canAdd = availableMedia.isNotEmpty(),
                    onDismiss = onDismiss,
                    onOpenPicker = { view = QueueView.PICKER },
                    onToggle = { index ->
                        selectedQueue = if (index in selectedQueue) selectedQueue - index
                        else selectedQueue + index
                    },
                    onSelectAll = { selectedQueue = queue.indices.toSet() },
                    onClearSelection = { selectedQueue = emptySet() },
                    onPlay = onPlay,
                    onMove = onMove,
                    onRemove = { indices ->
                        selectedQueue = emptySet()
                        onRemove(indices)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueContents(
    queue: List<MediaEntry>,
    currentIndex: Int,
    selected: Set<Int>,
    canAdd: Boolean,
    onDismiss: () -> Unit,
    onOpenPicker: () -> Unit,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Set<Int>) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VortexPalette.GraphiteHigh)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected.isNotEmpty()) {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Filled.Close, "Cancelar selección", tint = VortexPalette.TextHigh)
                }
                Text(
                    "${selected.size} SELECCIONADOS",
                    style = MaterialTheme.typography.labelLarge,
                    color = VortexPalette.Neon,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Filled.SelectAll, "Seleccionar toda la cola", tint = VortexPalette.TextMid)
                }
                IconButton(onClick = { onRemove(selected) }) {
                    Icon(Icons.Filled.Delete, "Quitar seleccionados", tint = VortexPalette.Magenta)
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = VortexPalette.Cyan,
                    modifier = Modifier.padding(start = 10.dp).size(25.dp)
                )
                Column(Modifier.padding(start = 11.dp).weight(1f)) {
                    Text(
                        "COLA DE REPRODUCCIÓN",
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextHigh
                    )
                    Text(
                        "${queue.size} ELEMENTOS · ${queue.count { it.isVideo }} VÍDEOS · " +
                            "${queue.count { !it.isVideo }} AUDIOS",
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Cerrar cola", tint = VortexPalette.TextMid)
                }
            }
        }

        if (canAdd) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onOpenPicker, onLongClick = {})
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, tint = VortexPalette.Neon)
                Text(
                    "ELEGIR VÍDEOS O MP3 PARA AÑADIR",
                    style = MaterialTheme.typography.labelLarge,
                    color = VortexPalette.Neon,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        null,
                        tint = VortexPalette.TextLow,
                        modifier = Modifier.size(42.dp)
                    )
                    Text(
                        "La cola está vacía",
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextMid,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        "Elige audios y vídeos para decidir qué se reproduce.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VortexPalette.TextLow,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = queue,
                    key = { index, entry -> "${entry.uri}#$index" }
                ) { index, entry ->
                    QueueEntryRow(
                        entry = entry,
                        index = index,
                        isCurrent = index == currentIndex,
                        selected = index in selected,
                        selectionActive = selected.isNotEmpty(),
                        canMoveUp = index > 0,
                        canMoveDown = index < queue.lastIndex,
                        onClick = {
                            if (selected.isNotEmpty()) onToggle(index) else onPlay(index)
                        },
                        onLongClick = { onToggle(index) },
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        onRemove = { onRemove(setOf(index)) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueEntryRow(
    entry: MediaEntry,
    index: Int,
    isCurrent: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> VortexPalette.Neon.copy(alpha = 0.11f)
                    isCurrent -> VortexPalette.Cyan.copy(alpha = 0.08f)
                    else -> VortexPalette.GraphiteRaised
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isCurrent) "▶" else "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) VortexPalette.Neon else VortexPalette.TextLow,
            modifier = Modifier.width(28.dp)
        )
        QueueThumbnail(entry)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                entry.title.ifBlank { entry.displayName },
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) VortexPalette.Neon else VortexPalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(if (entry.isVideo) "VÍDEO" else "AUDIO")
                    append(" · ")
                    append(formatDuration(entry.durationMs))
                    entry.artist?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selectionActive) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (selected) "Seleccionado" else "No seleccionado",
                tint = if (selected) VortexPalette.Neon else VortexPalette.TextLow
            )
        } else {
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Subir", tint = VortexPalette.TextMid)
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Bajar", tint = VortexPalette.TextMid)
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Delete, "Quitar de la cola", tint = VortexPalette.Magenta)
            }
        }
    }
}

@Composable
private fun QueueThumbnail(entry: MediaEntry) {
    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 42.dp)
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small),
        contentAlignment = Alignment.Center
    ) {
        if (entry.isVideo) {
            AsyncImage(
                model = rememberThumbnailRequest(entry),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = VortexPalette.Cyan)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueMediaPicker(
    entries: List<MediaEntry>,
    selected: Set<String>,
    query: String,
    kind: MediaKind,
    onQuery: (String) -> Unit,
    onKind: (MediaKind) -> Unit,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onAdd: (Boolean) -> Unit
) {
    val visible = remember(entries, query, kind) {
        val term = query.trim()
        entries.filter { entry ->
            (kind == MediaKind.ALL || (kind == MediaKind.VIDEO) == entry.isVideo) &&
                (term.isEmpty() || entry.title.contains(term, true) ||
                    entry.displayName.contains(term, true) || entry.artist?.contains(term, true) == true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VortexPalette.GraphiteHigh)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a la cola", tint = VortexPalette.TextHigh)
            }
            Column(Modifier.weight(1f)) {
                Text("AÑADIR A LA COLA", style = MaterialTheme.typography.titleMedium, color = VortexPalette.TextHigh)
                Text(
                    "${selected.size} SELECCIONADOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected.isEmpty()) VortexPalette.TextLow else VortexPalette.Neon
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .background(VortexPalette.GraphiteHigh, VortexShapes.small)
                .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = VortexPalette.TextLow)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isBlank()) {
                    Text("Buscar título o artista", style = MaterialTheme.typography.bodyMedium, color = VortexPalette.TextLow)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = VortexPalette.TextHigh),
                    cursorBrush = SolidColor(VortexPalette.Neon),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Close, "Limpiar búsqueda", tint = VortexPalette.TextMid)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MediaKind.entries.forEach { value ->
                Text(
                    value.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (kind == value) VortexPalette.Graphite else VortexPalette.TextMid,
                    modifier = Modifier
                        .background(
                            if (kind == value) VortexPalette.Neon else VortexPalette.GraphiteHigh,
                            VortexShapes.small
                        )
                        .combinedClickable(onClick = { onKind(value) }, onLongClick = {})
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            itemsIndexed(visible, key = { _, entry -> entry.uri.toString() }) { _, entry ->
                val checked = entry.uri.toString() in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (checked) VortexPalette.Neon.copy(alpha = 0.10f) else VortexPalette.GraphiteRaised)
                        .combinedClickable(
                            onClick = { onToggle(entry.uri.toString()) },
                            onLongClick = { onToggle(entry.uri.toString()) }
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QueueThumbnail(entry)
                    Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                        Text(
                            entry.title.ifBlank { entry.displayName },
                            style = MaterialTheme.typography.titleSmall,
                            color = VortexPalette.TextHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(
                                if (entry.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                                null,
                                tint = if (entry.isVideo) VortexPalette.Amber else VortexPalette.Cyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                formatDuration(entry.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = VortexPalette.TextLow
                            )
                        }
                    }
                    Icon(
                        if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        if (checked) "Seleccionado" else "Seleccionar",
                        tint = if (checked) VortexPalette.Neon else VortexPalette.TextLow
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VortexPalette.GraphiteHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onAdd(true) }, enabled = selected.isNotEmpty()) {
                Icon(Icons.Filled.SkipNext, null, tint = VortexPalette.Cyan)
                Text("SIGUIENTE", color = VortexPalette.Cyan, modifier = Modifier.padding(start = 5.dp))
            }
            TextButton(onClick = { onAdd(false) }, enabled = selected.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = VortexPalette.Neon)
                Text("AL FINAL", color = VortexPalette.Neon, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}
