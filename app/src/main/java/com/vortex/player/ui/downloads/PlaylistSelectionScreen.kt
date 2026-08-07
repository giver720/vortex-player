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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                        Icons.Filled.ArrowBack,
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

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                itemsIndexed(
                    items = selection.tracks,
                    key = { index, item -> item.track.id ?: "$index-${item.track.title}" }
                ) { index, item ->
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
