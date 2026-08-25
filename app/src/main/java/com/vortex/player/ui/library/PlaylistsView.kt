@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vortex.player.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.PlaylistOrganizer
import com.vortex.player.data.PlaylistResolvedItem
import com.vortex.player.data.PlaylistSortMode
import com.vortex.player.data.PlaylistWithItems
import com.vortex.player.data.SmartPlaylistRule
import com.vortex.player.data.db.PlaylistEntity
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.rememberThumbnailRequest
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

@Composable
fun PlaylistsIndex(
    playlists: List<PlaylistWithItems>,
    library: LibraryState,
    favoritesCount: Int,
    queueCount: Int,
    onOpen: (Long) -> Unit,
    onPlay: (Long, Boolean) -> Unit,
    onPlayNext: (Long) -> Unit,
    onAddQueue: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCreate: () -> Unit,
    onCreateSmart: () -> Unit,
    onImport: () -> Unit,
    onSaveQueue: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(164.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PlaylistActions(queueCount, onCreate, onCreateSmart, onImport, onSaveQueue)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .clip(VortexShapes.medium).background(VortexPalette.GraphiteRaised)
                    .border(1.dp, VortexPalette.Magenta.copy(alpha = 0.4f), VortexShapes.medium)
                    .combinedClickable(
                        onClick = { onOpen(LibraryViewModel.FAVORITES_ID) },
                        onLongClick = { onOpen(LibraryViewModel.FAVORITES_ID) }
                    ).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Favorite, null, tint = VortexPalette.Magenta, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f)) {
                    Text("FAVORITOS", style = MaterialTheme.typography.titleMedium, color = VortexPalette.TextHigh)
                    Text("$favoritesCount elementos marcados", style = MaterialTheme.typography.labelSmall, color = VortexPalette.TextLow)
                }
                Icon(Icons.Filled.PlayArrow, "Abrir favoritos", tint = VortexPalette.Neon)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "MIS PLAYLISTS · ${playlists.size}",
                style = MaterialTheme.typography.labelMedium,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
        if (playlists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Crea una playlist, importa un M3U o guarda la cola actual. Después podrás " +
                        "añadir pistas directamente desde su editor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
        items(playlists, key = { it.playlist.id }) { item ->
            val resolved = remember(item.items, library.entries) {
                PlaylistOrganizer.resolve(item, library)
            }
            PlaylistCard(
                item = item,
                resolved = resolved,
                onOpen = { onOpen(item.playlist.id) },
                onPlay = { onPlay(item.playlist.id, false) },
                onShuffle = { onPlay(item.playlist.id, true) },
                onPlayNext = { onPlayNext(item.playlist.id) },
                onAddQueue = { onAddQueue(item.playlist.id) },
                onEdit = { onEdit(item.playlist.id) },
                onExport = { onExport(item.playlist.id) },
                onDelete = { onDelete(item.playlist.id) },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun PlaylistActions(
    queueCount: Int,
    onCreate: () -> Unit,
    onCreateSmart: () -> Unit,
    onImport: () -> Unit,
    onSaveQueue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlaylistActionButton("NUEVA", Icons.Filled.Add, onCreate, Modifier.weight(1f), primary = true)
        PlaylistActionButton("SMART", Icons.Filled.AutoAwesome, onCreateSmart, Modifier.weight(1f))
        PlaylistActionButton("IMPORTAR", Icons.Filled.FileUpload, onImport, Modifier.weight(1f))
        PlaylistActionButton(
            if (queueCount > 0) "COLA · $queueCount" else "COLA VACÍA",
            Icons.Filled.Save,
            onSaveQueue,
            Modifier.weight(1f),
            enabled = queueCount > 0
        )
    }
}

@Composable
private fun PlaylistActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val background = if (primary) VortexPalette.Neon else VortexPalette.GraphiteRaised
    val foreground = if (primary) VortexPalette.Graphite else VortexPalette.TextMid
    Row(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).clip(VortexShapes.small)
            .background(background)
            .border(0.5.dp, if (primary) VortexPalette.Neon else VortexPalette.Outline, VortexShapes.small)
            .combinedClickable(onClick = { if (enabled) onClick() }, onLongClick = {})
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = foreground, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = foreground, maxLines = 1)
    }
}

@Composable
private fun PlaylistCard(
    item: PlaylistWithItems,
    resolved: List<PlaylistResolvedItem>,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onAddQueue: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    val stats = remember(resolved) { PlaylistOrganizer.stats(resolved) }
    Column(
        modifier = modifier.clip(VortexShapes.medium).background(VortexPalette.GraphiteRaised)
            .border(0.7.dp, VortexPalette.Outline, VortexShapes.medium)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
    ) {
        Box {
            PlaylistCover(
                item.playlist.coverUri,
                resolved.mapNotNull { it.media },
                Modifier.fillMaxWidth().aspectRatio(1.35f)
            )
            Text(
                item.playlist.source,
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.Graphite,
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                    .background(VortexPalette.Cyan, VortexShapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.MoreVert, "Más acciones", tint = VortexPalette.TextHigh)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Reproducir después") },
                    leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
                    onClick = { menu = false; onPlayNext() }
                )
                DropdownMenuItem(
                    text = { Text("Añadir a la cola") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    onClick = { menu = false; onAddQueue() }
                )
                DropdownMenuItem(
                    text = { Text("Editar datos") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menu = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Exportar M3U8") },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                    onClick = { menu = false; onExport() }
                )
                DropdownMenuItem(
                    text = { Text("Eliminar") },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                item.playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = VortexPalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append("${stats.count} elementos")
                    if (stats.durationMs > 0L) append(" · ${formatDuration(stats.durationMs)}")
                    if (stats.missing > 0) append(" · ${stats.missing} ausentes")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (stats.missing > 0) VortexPalette.Amber else VortexPalette.TextLow,
                maxLines = 1
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onShuffle, enabled = stats.available > 0) {
                    Icon(Icons.Filled.Shuffle, "Aleatorio", tint = VortexPalette.Cyan)
                }
                IconButton(onClick = onPlay, enabled = stats.available > 0) {
                    Icon(Icons.Filled.PlayArrow, "Reproducir", tint = VortexPalette.Neon)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditor(
    playlist: PlaylistWithItems,
    library: LibraryState,
    contentPadding: PaddingValues,
    undoAvailable: Boolean,
    onBack: () -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onPlayEntry: (MediaEntry, List<MediaEntry>) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onPlayNext: () -> Unit,
    onAddQueue: () -> Unit,
    onSort: (PlaylistSortMode) -> Unit,
    onRemoveMissing: () -> Unit,
    onRemove: (List<Long>) -> Unit,
    onMove: (Long, Int) -> Unit,
    onUndo: () -> Unit
) {
    val resolved = remember(playlist.items, library.entries) {
        PlaylistOrganizer.resolve(playlist, library)
    }
    val editable = playlist.playlist.smartRule == null
    val stats = remember(resolved) { PlaylistOrganizer.stats(resolved) }
    var query by remember(playlist.playlist.id) { mutableStateOf("") }
    var selected by remember(playlist.playlist.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    val visible = remember(resolved, query) {
        val term = query.trim()
        if (term.isBlank()) resolved else resolved.filter {
            it.title.contains(term, true) || it.artist.orEmpty().contains(term, true) ||
                it.album.orEmpty().contains(term, true)
        }
    }
    val availableQueue = visible.mapNotNull { it.media }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹ LISTAS", color = VortexPalette.TextMid) }
                Spacer(Modifier.weight(1f))
                if (undoAvailable) {
                    TextButton(onClick = onUndo) { Text("DESHACER", color = VortexPalette.Cyan) }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, "Gestionar", tint = VortexPalette.TextHigh)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Editar nombre, descripción y portada") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Reproducir después") },
                            leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
                            onClick = { menu = false; onPlayNext() }
                        )
                        DropdownMenuItem(
                            text = { Text("Añadir a la cola") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            onClick = { menu = false; onAddQueue() }
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar M3U8") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = { menu = false; onExport() }
                        )
                        if (editable) {
                            DropdownMenuItem(text = { Text("Ordenar por título") }, onClick = { menu = false; onSort(PlaylistSortMode.TITLE) })
                            DropdownMenuItem(text = { Text("Ordenar por artista") }, onClick = { menu = false; onSort(PlaylistSortMode.ARTIST) })
                            DropdownMenuItem(text = { Text("Ordenar por álbum") }, onClick = { menu = false; onSort(PlaylistSortMode.ALBUM) })
                            DropdownMenuItem(text = { Text("Ordenar por duración") }, onClick = { menu = false; onSort(PlaylistSortMode.DURATION) })
                        }
                        if (editable && stats.missing > 0) {
                            DropdownMenuItem(
                                text = { Text("Quitar ${stats.missing} ausentes") },
                                leadingIcon = { Icon(Icons.Filled.ClearAll, null) },
                                onClick = { menu = false; onRemoveMissing() }
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PlaylistCover(
                    playlist.playlist.coverUri,
                    resolved.mapNotNull { it.media },
                    Modifier.size(112.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.playlist.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = VortexPalette.TextHigh,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (playlist.playlist.description.isNotBlank()) {
                        Text(
                            playlist.playlist.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexPalette.TextMid,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "${stats.count} elementos · ${formatDuration(stats.durationMs)}" +
                            if (stats.missing > 0) " · ${stats.missing} ausentes" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stats.missing > 0) VortexPalette.Amber else VortexPalette.TextLow
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onPlayAll(false) },
                    enabled = stats.available > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VortexPalette.Neon,
                        contentColor = VortexPalette.Graphite
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text("REPRODUCIR")
                }
                OutlinedButton(onClick = { onPlayAll(true) }, enabled = stats.available > 0) {
                    Icon(Icons.Filled.Shuffle, "Aleatorio")
                }
                if (editable) {
                    OutlinedButton(onClick = onAdd) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Añadir")
                    }
                }
            }
            PlaylistSearchField(query = query, onQuery = { query = it })
            if (selected.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(VortexPalette.GraphiteHigh)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selected.size} SELECCIONADOS",
                        style = MaterialTheme.typography.labelMedium,
                        color = VortexPalette.Neon,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val entries = visible.filter { it.stored.id in selected }.mapNotNull { it.media }
                        entries.firstOrNull()?.let { onPlayEntry(it, entries) }
                    }) {
                        Icon(Icons.Filled.PlayArrow, "Reproducir selección", tint = VortexPalette.Cyan)
                    }
                    if (editable) {
                        IconButton(onClick = {
                            onRemove(selected.toList())
                            selected = emptySet()
                        }) {
                            Icon(Icons.Filled.DeleteOutline, "Quitar selección", tint = VortexPalette.Magenta)
                        }
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text("CERRAR", color = VortexPalette.TextMid)
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                Text(
                    if (query.isBlank()) "La playlist está vacía. Pulsa + para añadir medios."
                    else "Sin resultados en esta playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        itemsIndexed(visible, key = { _, item -> item.stored.id }) { index, item ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        onRemove(listOf(item.stored.id))
                        true
                    } else false
                }
            )
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = editable,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().background(VortexPalette.Magenta.copy(alpha = 0.16f))
                            .padding(end = 22.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(Icons.Filled.DeleteOutline, "Quitar", tint = VortexPalette.Magenta)
                    }
                }
            ) {
                PlaylistEditorRow(
                    item = item,
                    index = index,
                    selected = item.stored.id in selected,
                    reorderEnabled = query.isBlank() && editable,
                    removable = editable,
                    onClick = {
                        if (selected.isNotEmpty()) {
                            selected = if (item.stored.id in selected) {
                                selected - item.stored.id
                            } else {
                                selected + item.stored.id
                            }
                        } else {
                            item.media?.let { onPlayEntry(it, availableQueue) }
                        }
                    },
                    onLongClick = {
                        selected = if (item.stored.id in selected) {
                            selected - item.stored.id
                        } else {
                            selected + item.stored.id
                        }
                    },
                    onMove = { direction -> onMove(item.stored.id, direction) },
                    onRemove = { onRemove(listOf(item.stored.id)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistEditorRow(
    item: PlaylistResolvedItem,
    index: Int,
    selected: Boolean,
    reorderEnabled: Boolean,
    removable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val threshold = with(LocalDensity.current) { 42.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) VortexPalette.Neon.copy(alpha = 0.1f) else VortexPalette.Graphite)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.width(24.dp)
        )
        PlaylistItemThumb(item)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (item.available) VortexPalette.TextHigh else VortexPalette.Amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.available) {
                    listOfNotNull(item.artist, item.album).joinToString(" · ")
                        .ifBlank { if (item.isVideo) "Vídeo" else "Audio" }
                } else {
                    "ARCHIVO NO DISPONIBLE · conserva su posición"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.available) VortexPalette.TextLow else VortexPalette.Magenta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(formatDuration(item.durationMs), style = MaterialTheme.typography.labelSmall, color = VortexPalette.TextLow)
        if (selected) {
            Icon(Icons.Filled.CheckCircle, "Seleccionado", tint = VortexPalette.Neon)
        } else if (reorderEnabled) {
            Icon(
                Icons.Filled.DragHandle,
                "Mantén y arrastra para reordenar",
                tint = VortexPalette.TextMid,
                modifier = Modifier.size(28.dp).pointerInput(item.stored.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag = 0f },
                        onDragEnd = { drag = 0f },
                        onDragCancel = { drag = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            drag += amount.y
                            when {
                                drag > threshold -> {
                                    onMove(1)
                                    drag = 0f
                                }
                                drag < -threshold -> {
                                    onMove(-1)
                                    drag = 0f
                                }
                            }
                        }
                    )
                }
            )
        } else if (removable) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.DeleteOutline, "Quitar", tint = VortexPalette.TextLow)
            }
        }
    }
}

@Composable
fun SmartPlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, SmartPlaylistRule) -> Unit
) {
    var rule by remember { mutableStateOf(SmartPlaylistRule.RECENT) }
    var name by remember(rule) { mutableStateOf(rule.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexPalette.GraphiteRaised,
        title = { Text("PLAYLIST INTELIGENTE") },
        text = {
            Column {
                PlaylistTextInput(name, { name = it }, "Nombre")
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(SmartPlaylistRule.entries) { option ->
                        Row(
                            Modifier.fillMaxWidth().clip(VortexShapes.small)
                                .background(if (rule == option) VortexPalette.Neon.copy(alpha = 0.1f) else VortexPalette.GraphiteRaised)
                                .combinedClickable(
                                    onClick = { rule = option; name = option.label },
                                    onLongClick = {}
                                ).padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = if (rule == option) VortexPalette.Neon else VortexPalette.TextLow)
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text(option.label, color = VortexPalette.TextHigh)
                                Text(option.description, style = MaterialTheme.typography.labelSmall, color = VortexPalette.TextLow)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, rule) }, enabled = name.isNotBlank()) {
                Text("CREAR", color = VortexPalette.Neon)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR", color = VortexPalette.TextLow) }
        }
    )
}

@Composable
private fun PlaylistItemThumb(item: PlaylistResolvedItem) {
    Box(
        Modifier.size(width = 64.dp, height = 42.dp).clip(VortexShapes.small)
            .background(VortexPalette.GraphiteRaised)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small),
        contentAlignment = Alignment.Center
    ) {
        when {
            item.media?.isVideo == true -> AsyncImage(
                model = rememberThumbnailRequest(item.media),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            !item.available -> Icon(Icons.Filled.BrokenImage, null, tint = VortexPalette.Magenta)
            item.isVideo -> Icon(Icons.Filled.VideoLibrary, null, tint = VortexPalette.Cyan)
            else -> Icon(Icons.Filled.MusicNote, null, tint = VortexPalette.Cyan)
        }
    }
}

@Composable
private fun PlaylistCover(
    customCover: String?,
    entries: List<MediaEntry>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.clip(VortexShapes.medium).background(VortexPalette.GraphiteHigh)
            .border(0.7.dp, VortexPalette.Outline, VortexShapes.medium)
    ) {
        if (!customCover.isNullOrBlank()) {
            AsyncImage(
                model = customCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val videos = entries.filter { it.isVideo }.take(4)
            if (videos.isEmpty()) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    null,
                    tint = VortexPalette.Cyan.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.Center).size(42.dp)
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    repeat(2) { row ->
                        Row(Modifier.weight(1f)) {
                            repeat(2) { column ->
                                val entry = videos.getOrNull(row * 2 + column) ?: videos.first()
                                AsyncImage(
                                    model = rememberThumbnailRequest(entry),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(VortexShapes.small).background(VortexPalette.GraphiteHigh)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = VortexPalette.TextLow, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                Text(
                    "Buscar dentro de la playlist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexPalette.TextLow
                )
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
    }
}

@Composable
fun PlaylistMediaPickerDialog(
    library: List<MediaEntry>,
    existingUris: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<MediaEntry>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val visible = remember(library, query) {
        val term = query.trim()
        if (term.isBlank()) library else library.filter {
            it.title.contains(term, true) || it.artist.orEmpty().contains(term, true) ||
                it.album.orEmpty().contains(term, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexPalette.GraphiteRaised,
        title = { Text("AÑADIR MEDIOS · ${selected.size} seleccionados") },
        text = {
            Column {
                PlaylistSearchField(query, onQuery = { query = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        selected = visible.filterNot { it.uri.toString() in existingUris }
                            .map { it.uri.toString() }.toSet()
                    }) {
                        Text("SELECCIONAR VISIBLES", color = VortexPalette.Cyan)
                    }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(visible, key = { it.uri.toString() }) { entry ->
                        val uri = entry.uri.toString()
                        val already = uri in existingUris
                        val checked = uri in selected
                        Row(
                            Modifier.fillMaxWidth().alpha(if (already) 0.45f else 1f)
                                .combinedClickable(
                                    onClick = {
                                        if (!already) {
                                            selected = if (checked) selected - uri else selected + uri
                                        }
                                    },
                                    onLongClick = {}
                                ).padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (checked || already) Icons.Filled.CheckCircle
                                else if (entry.isVideo) Icons.Filled.VideoLibrary
                                else Icons.Filled.MusicNote,
                                null,
                                tint = if (checked) VortexPalette.Neon else VortexPalette.TextLow
                            )
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title.ifBlank { entry.displayName },
                                    color = VortexPalette.TextHigh,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (already) "YA ESTÁ EN LA PLAYLIST" else entry.artist ?: entry.folderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VortexPalette.TextLow,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(library.filter { it.uri.toString() in selected }) },
                enabled = selected.isNotEmpty()
            ) {
                Text("AÑADIR ${selected.size}", color = VortexPalette.Neon)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR", color = VortexPalette.TextLow) }
        }
    )
}

@Composable
fun PlaylistDetailsDialog(
    playlist: PlaylistEntity,
    pendingCover: String?,
    onDismiss: () -> Unit,
    onChooseCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onSave: (String, String, String?) -> Unit
) {
    var name by remember(playlist.id, playlist.name) { mutableStateOf(playlist.name) }
    var description by remember(playlist.id, playlist.description) {
        mutableStateOf(playlist.description)
    }
    val cover = when (pendingCover) {
        "" -> null
        null -> playlist.coverUri
        else -> pendingCover
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexPalette.GraphiteRaised,
        title = { Text("EDITAR PLAYLIST") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaylistTextInput(name, { name = it }, "Nombre")
                PlaylistTextInput(
                    description,
                    { description = it },
                    "Descripción opcional",
                    singleLine = false
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChooseCover, modifier = Modifier.weight(1f)) {
                        Text("ELEGIR PORTADA")
                    }
                    if (cover != null) {
                        OutlinedButton(onClick = onRemoveCover) { Text("AUTOMÁTICA") }
                    }
                }
                Text(
                    if (cover == null) "La portada se genera con los primeros vídeos."
                    else "Portada personalizada seleccionada.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VortexPalette.TextLow
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description, cover) },
                enabled = name.isNotBlank()
            ) { Text("GUARDAR", color = VortexPalette.Neon) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR", color = VortexPalette.TextLow) }
        }
    )
}

@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexPalette.GraphiteRaised,
        title = { Text(title) },
        text = { PlaylistTextInput(name, { name = it }, "Nombre de la playlist") },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("GUARDAR", color = VortexPalette.Neon)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR", color = VortexPalette.TextLow) }
        }
    )
}

@Composable
fun DeletePlaylistDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexPalette.GraphiteRaised,
        title = { Text("ELIMINAR PLAYLIST") },
        text = { Text("Se eliminará «$name». Los archivos del teléfono no se borrarán.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("ELIMINAR", color = VortexPalette.Magenta) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR", color = VortexPalette.TextLow) }
        }
    )
}

@Composable
private fun PlaylistTextInput(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    singleLine: Boolean = true
) {
    Box(
        Modifier.fillMaxWidth().clip(VortexShapes.small).background(VortexPalette.GraphiteHigh)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .padding(horizontal = 10.dp, vertical = 11.dp)
    ) {
        if (value.isBlank()) Text(hint, color = VortexPalette.TextLow)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 4,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = VortexPalette.TextHigh),
            cursorBrush = SolidColor(VortexPalette.Neon),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Cabecera compacta para la vista sintética de Favoritos. */
@Composable
fun PlaylistHeader(
    name: String,
    count: Int,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "‹ LISTAS",
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.TextLow,
            modifier = Modifier.combinedClickable(onClick = onBack, onLongClick = {})
                .padding(vertical = 4.dp)
        )
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = VortexPalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Text(
                "REPRODUCIR",
                style = MaterialTheme.typography.labelLarge,
                color = VortexPalette.Graphite,
                modifier = Modifier.background(VortexPalette.Neon, VortexShapes.small)
                    .combinedClickable(onClick = onPlayAll, onLongClick = {})
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}
