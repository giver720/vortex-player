package com.vortex.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.player.data.PlaylistWithItems
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Barra que sustituye a la cabecera cuando hay algo seleccionado.
 *
 * Reemplazar la cabecera en vez de superponer otra barra deja claro que la app ha
 * cambiado de modo: mientras esté ahí, un toque selecciona en vez de reproducir.
 */
@Composable
fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onQueue: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VortexPalette.GraphiteHigh)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Salir de selección",
                tint = VortexPalette.TextHigh
            )
        }
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelLarge,
            color = VortexPalette.Neon,
            modifier = Modifier.padding(end = 10.dp)
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, "Seleccionar todo", tint = VortexPalette.TextMid)
            }
            IconButton(onClick = onQueue) {
                Icon(Icons.Filled.PlaylistPlay, "Reproducir", tint = VortexPalette.TextMid)
            }
            IconButton(onClick = onFavorite) {
                Icon(Icons.Filled.Favorite, "Favorito", tint = VortexPalette.TextMid)
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Filled.PlaylistAdd, "Añadir a lista", tint = VortexPalette.TextMid)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, "Compartir", tint = VortexPalette.TextMid)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Eliminar", tint = VortexPalette.Magenta)
            }
        }
    }
}

/** Elegir una lista existente o crear una nueva con lo seleccionado. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistWithItems>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexPalette.GraphiteRaised,
        titleContentColor = VortexPalette.TextHigh,
        textContentColor = VortexPalette.TextMid,
        title = { Text("AÑADIR A LISTA", style = MaterialTheme.typography.labelLarge) },
        text = {
            Column {
                if (playlists.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(playlists, key = { it.playlist.id }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item.playlist.id) }
                                    .padding(vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = VortexPalette.TextHigh,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${item.items.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VortexPalette.TextLow
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "NUEVA LISTA",
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(VortexPalette.GraphiteHigh, VortexShapes.small)
                        .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    if (newName.isEmpty()) {
                        Text(
                            text = "Nombre de la lista",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VortexPalette.TextLow
                        )
                    }
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = VortexPalette.TextHigh
                        ),
                        cursorBrush = SolidColor(VortexPalette.Neon),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(newName) },
                enabled = newName.isNotBlank()
            ) {
                Text("CREAR", color = VortexPalette.Neon)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR", color = VortexPalette.TextLow)
            }
        }
    )
}
