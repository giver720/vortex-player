package com.vortex.player.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.db.MediaStateEntity
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.formatSize
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Fila compacta para el modo lista.
 *
 * Cabe cuatro veces más contenido en pantalla que en la rejilla, y los datos técnicos
 * (resolución, tamaño, carpeta) se leen de un vistazo en vez de esconderse tras cápsulas
 * diminutas sobre la miniatura. Es la vista útil cuando tienes cientos de ficheros.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    entry: MediaEntry,
    state: MediaStateEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) VortexPalette.Neon.copy(alpha = 0.10f) else VortexPalette.Graphite
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(54.dp)
                .clip(VortexShapes.small)
                .background(VortexPalette.GraphiteRaised)
                .border(
                    width = if (selected) 1.5.dp else 0.5.dp,
                    color = if (selected) VortexPalette.Neon else VortexPalette.Outline,
                    shape = VortexShapes.small
                )
        ) {
            if (entry.isVideo) {
                AsyncImage(
                    model = entry.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = VortexPalette.Cyan.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (state != null && state.progressFraction > 0.01f && !state.isFinished) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(VortexPalette.Graphite.copy(alpha = 0.6f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(state.progressFraction)
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(VortexPalette.Cyan, VortexPalette.Neon)
                                )
                            )
                    )
                }
            }

            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = VortexPalette.Neon,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp)
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.displayName },
                style = MaterialTheme.typography.titleMedium,
                color = VortexPalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(formatDuration(entry.durationMs))
                    entry.resolutionLabel?.let { append(" · $it") }
                    formatSize(entry.sizeBytes).takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextMid,
                maxLines = 1
            )
            Text(
                text = entry.artist ?: entry.folderName,
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (state?.isFavorite == true) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favorito",
                tint = VortexPalette.Magenta,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
