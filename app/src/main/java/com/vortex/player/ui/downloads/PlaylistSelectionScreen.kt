package com.vortex.player.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vortex.player.download.SourcePlaylistSelection
import com.vortex.player.spotify.PlaylistSelection
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Elegir qué canciones bajar de una lista.
 *
 * Lo ya descargado llega desmarcado y señalado, de modo que el botón por defecto
 * descarga sólo lo que falta: resincronizar una lista se convierte en pegar el enlace y
 * confirmar, sin repetir trabajo ni duplicar archivos.
 */
@Composable
fun PlaylistSelectionScreen(
    selection: PlaylistSelection,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onOnlyMissing: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val visibleTracks = remember(selection.tracks, filter) {
        selection.tracks.withIndex().filter { (_, item) ->
            filter.isBlank() || item.track.title.contains(filter, ignoreCase = true) ||
                item.track.artist.contains(filter, ignoreCase = true)
        }
    }
    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancelar",
                        tint = VortexPalette.TextHigh
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selection.name.ifBlank { "Spotify" },
                        style = MaterialTheme.typography.titleLarge,
                        color = VortexPalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${selection.tracks.size} canciones")
                            if (selection.ownedCount > 0) {
                                append(" · ${selection.ownedCount} ya la tienes")
                            }
                            if (selection.resolving) append(" · leyendo…")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionChip("TODAS") { onSelectAll(true) }
                SelectionChip("NINGUNA") { onSelectAll(false) }
                if (selection.ownedCount > 0) {
                    SelectionChip("SÓLO LO QUE FALTA", accent = true) { onOnlyMissing() }
                }
            }

            if (selection.tracks.size > 20 || filter.isNotBlank()) {
                SelectionSearchField(
                    value = filter,
                    resultCount = visibleTracks.size,
                    onValueChange = { filter = it }
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(
                    items = visibleTracks,
                    key = { indexed ->
                        "${indexed.index}-${indexed.value.track.id ?: indexed.value.track.title}"
                    }
                ) { indexed ->
                    val index = indexed.index
                    val item = indexed.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(index) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Icon(
                            imageVector = if (item.selected) {
                                Icons.Filled.CheckBox
                            } else {
                                Icons.Filled.CheckBoxOutlineBlank
                            },
                            contentDescription = null,
                            tint = if (item.selected) {
                                VortexPalette.Neon
                            } else {
                                VortexPalette.TextLow
                            },
                            modifier = Modifier.size(19.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.track.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (item.owned != null) {
                                    VortexPalette.TextLow
                                } else {
                                    VortexPalette.TextHigh
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = item.track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VortexPalette.TextLow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (item.track.durationMs > 0) {
                                    Text(
                                        text = formatDuration(item.track.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VortexPalette.TextLow
                                    )
                                }
                            }
                            item.owned?.let { reason ->
                                Text(
                                    text = reason.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VortexPalette.Cyan,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VortexPalette.GraphiteRaised)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding()
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selection.selectedCount} marcadas",
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.TextMid,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "DESCARGAR",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selection.selectedCount > 0) {
                        VortexPalette.Graphite
                    } else {
                        VortexPalette.TextLow
                    },
                    modifier = Modifier
                        .background(
                            if (selection.selectedCount > 0) {
                                VortexPalette.Neon
                            } else {
                                VortexPalette.GraphiteHigh
                            },
                            VortexShapes.small
                        )
                        .clickable(enabled = selection.selectedCount > 0, onClick = onConfirm)
                        .padding(horizontal = 18.dp, vertical = 11.dp)
                )
            }
        }
    }
}

/** Selector visual para playlists expandidas por yt-dlp. */
@Composable
fun PlaylistSelectionScreen(
    selection: SourcePlaylistSelection,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onOnlyMissing: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val visibleEntries = remember(selection.entries, filter) {
        selection.entries.withIndex().filter { (_, item) ->
            filter.isBlank() || item.entry.title.contains(filter, ignoreCase = true) ||
                item.entry.uploader.contains(filter, ignoreCase = true)
        }
    }
    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancelar",
                        tint = VortexPalette.TextHigh
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selection.name.ifBlank { "Playlist" },
                        style = MaterialTheme.typography.titleLarge,
                        color = VortexPalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${selection.entries.size} elementos")
                            if (selection.ownedCount > 0) {
                                append(" · ${selection.ownedCount} ya descargados")
                            }
                            if (selection.uploader.isNotBlank()) append(" · ${selection.uploader}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionChip("TODAS") { onSelectAll(true) }
                SelectionChip("NINGUNA") { onSelectAll(false) }
                if (selection.ownedCount > 0) {
                    SelectionChip("SÓLO LO QUE FALTA", accent = true) { onOnlyMissing() }
                }
            }

            if (selection.entries.size > 20 || filter.isNotBlank()) {
                SelectionSearchField(
                    value = filter,
                    resultCount = visibleEntries.size,
                    onValueChange = { filter = it }
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(
                    items = visibleEntries,
                    key = { indexed -> "${indexed.index}-${indexed.value.entry.id}" }
                ) { indexed ->
                    val index = indexed.index
                    val item = indexed.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(index) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (item.selected) {
                                Icons.Filled.CheckBox
                            } else {
                                Icons.Filled.CheckBoxOutlineBlank
                            },
                            contentDescription = null,
                            tint = if (item.selected) VortexPalette.Neon else VortexPalette.TextLow,
                            modifier = Modifier.size(19.dp)
                        )
                        AsyncImage(
                            model = item.entry.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 72.dp, height = 44.dp)
                                .clip(VortexShapes.small)
                                .background(VortexPalette.GraphiteHigh)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.entry.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (item.alreadyDownloaded) {
                                    VortexPalette.TextLow
                                } else {
                                    VortexPalette.TextHigh
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (item.entry.uploader.isNotBlank()) {
                                    Text(
                                        text = item.entry.uploader,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = VortexPalette.TextLow,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                                if (item.entry.durationSeconds > 0) {
                                    Text(
                                        text = formatDuration(item.entry.durationSeconds * 1_000),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VortexPalette.TextLow
                                    )
                                }
                            }
                            if (item.alreadyDownloaded) {
                                Text(
                                    text = "YA DESCARGADO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VortexPalette.Cyan,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VortexPalette.GraphiteRaised)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding()
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selection.selectedCount} marcados",
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.TextMid,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "DESCARGAR",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selection.selectedCount > 0) {
                        VortexPalette.Graphite
                    } else {
                        VortexPalette.TextLow
                    },
                    modifier = Modifier
                        .background(
                            if (selection.selectedCount > 0) {
                                VortexPalette.Neon
                            } else {
                                VortexPalette.GraphiteHigh
                            },
                            VortexShapes.small
                        )
                        .clickable(enabled = selection.selectedCount > 0, onClick = onConfirm)
                        .padding(horizontal = 18.dp, vertical = 11.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectionSearchField(
    value: String,
    resultCount: Int,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = VortexPalette.TextLow,
            modifier = Modifier.size(17.dp)
        )
        Box(Modifier.weight(1f)) {
            if (value.isBlank()) {
                Text(
                    text = "Filtrar por título o artista…",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexPalette.TextLow
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = VortexPalette.TextHigh),
                cursorBrush = SolidColor(VortexPalette.Neon),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = resultCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow
        )
    }
}

@Composable
private fun SelectionChip(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) VortexPalette.Cyan else VortexPalette.TextMid,
        modifier = Modifier
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(
                0.5.dp,
                if (accent) VortexPalette.Cyan.copy(alpha = 0.5f) else VortexPalette.Outline,
                VortexShapes.small
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}
