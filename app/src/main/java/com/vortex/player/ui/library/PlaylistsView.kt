package com.vortex.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.player.data.PlaylistWithItems
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Índice de listas. Favoritos va fijado arriba y no se puede borrar: no es una lista
 * de verdad sino una vista de lo marcado, y mezclarla con las demás confundiría.
 */
@Composable
fun PlaylistsIndex(
    playlists: List<PlaylistWithItems>,
    favoritesCount: Int,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCreate: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
                    .border(1.dp, VortexPalette.Magenta.copy(alpha = 0.35f), VortexShapes.medium)
                    .clickable { onOpen(LibraryViewModel.FAVORITES_ID) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = VortexPalette.Magenta
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Favoritos",
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextHigh
                    )
                    Text(
                        text = "$favoritesCount elementos marcados",
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MIS LISTAS · ${playlists.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .background(VortexPalette.Neon, VortexShapes.small)
                        .clickable(onClick = onCreate)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = VortexPalette.Graphite,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "NUEVA",
                        style = MaterialTheme.typography.labelLarge,
                        color = VortexPalette.Graphite
                    )
                }
            }
        }

        if (playlists.isEmpty()) {
            item {
                Text(
                    text = "Aún no has creado ninguna lista. Mantén pulsado sobre varios " +
                        "elementos y usa el botón de añadir a lista.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        items(playlists, key = { it.playlist.id }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
                    .border(0.5.dp, VortexPalette.Outline, VortexShapes.medium)
                    .clickable { onOpen(item.playlist.id) }
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = null,
                    tint = VortexPalette.Neon
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.items.size} elementos",
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow
                    )
                }
                IconButton(onClick = { onDelete(item.playlist.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Borrar lista",
                        tint = VortexPalette.TextLow
                    )
                }
            }
        }
    }
}

/** Cabecera de una lista abierta, con el nombre y la vuelta al índice. */
@Composable
fun PlaylistHeader(
    name: String,
    count: Int,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "‹ LISTAS",
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.TextLow,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 4.dp)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = VortexPalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Text(
                text = "REPRODUCIR",
                style = MaterialTheme.typography.labelLarge,
                color = VortexPalette.Graphite,
                modifier = Modifier
                    .background(VortexPalette.Neon, VortexShapes.small)
                    .clickable(onClick = onPlayAll)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}
