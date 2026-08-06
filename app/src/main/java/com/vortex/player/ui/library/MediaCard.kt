package com.vortex.player.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.db.MediaStateEntity
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.rememberThumbnailRequest
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Tarjeta de la retícula. La miniatura manda; los datos técnicos (resolución, duración)
 * van en cápsulas monoespaciadas para que se lean como telemetría y no compitan con el título.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    entry: MediaEntry,
    state: MediaStateEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(VortexShapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(VortexShapes.medium)
                .background(VortexPalette.GraphiteRaised)
                .border(
                    width = if (selected) 2.dp else 0.5.dp,
                    color = if (selected) VortexPalette.Neon else VortexPalette.Outline,
                    shape = VortexShapes.medium
                )
        ) {
            if (entry.isVideo) {
                var failed by remember(entry.uri) { mutableStateOf(false) }
                AsyncImage(
                    model = rememberThumbnailRequest(entry),
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    onSuccess = { failed = false },
                    modifier = Modifier.fillMaxSize()
                )
                // Sin esto, un vídeo cuya miniatura no se puede extraer es
                // indistinguible de uno cuyo fotograma es negro de verdad.
                if (failed) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Movie,
                            contentDescription = null,
                            tint = VortexPalette.Outline,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            } else {
                // El audio no tiene fotograma que mostrar: un glifo centrado evita
                // una retícula llena de rectángulos vacíos.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = VortexPalette.Cyan.copy(alpha = 0.55f),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            // Degradado inferior: garantiza contraste de las cápsulas sobre cualquier fotograma.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to VortexPalette.Graphite.copy(alpha = 0.92f)
                        )
                    )
            )

            entry.resolutionLabel?.let { label ->
                TelemetryChip(
                    text = label,
                    tint = VortexPalette.Neon,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }

            if (state?.isFavorite == true) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Favorito",
                    tint = VortexPalette.Magenta,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(15.dp)
                )
            }

            TelemetryChip(
                text = formatDuration(entry.durationMs),
                tint = VortexPalette.TextHigh,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )

            if (state != null && state.progressFraction > 0.01f && !state.isFinished) {
                ProgressHairline(
                    fraction = state.progressFraction,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }

            if (state?.isFinished == true) {
                TelemetryChip(
                    text = "VISTO",
                    tint = VortexPalette.NeonDim,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                )
            }

            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(VortexPalette.Neon.copy(alpha = 0.18f))
                )
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = VortexPalette.Neon,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                )
            }
        }

        Text(
            text = entry.title.ifBlank { entry.displayName },
            style = MaterialTheme.typography.titleMedium,
            color = VortexPalette.TextHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        )
        Text(
            text = entry.artist ?: entry.folderName,
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, bottom = 4.dp)
        )
    }
}

/** Cápsula de datos: fondo translúcido, esquina cortada y tipografía monoespaciada. */
@Composable
fun TelemetryChip(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = modifier
            .background(
                VortexPalette.Graphite.copy(alpha = 0.78f),
                CutCornerShape(topStart = 3.dp, bottomEnd = 3.dp)
            )
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

/** Línea de progreso de 2 dp pegada al borde inferior de la miniatura. */
@Composable
private fun ProgressHairline(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(VortexPalette.Graphite.copy(alpha = 0.6f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(VortexPalette.Cyan, VortexPalette.Neon)
                    )
                )
        )
    }
}

/** Tarjeta ancha del carrusel "Continuar", con la posición exacta donde se dejó. */
@Composable
fun ContinueCard(
    entry: MediaEntry,
    state: MediaStateEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(VortexShapes.medium)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(VortexShapes.medium)
                .background(VortexPalette.GraphiteRaised)
                .border(1.dp, VortexPalette.Neon.copy(alpha = 0.35f), VortexShapes.medium)
        ) {
            AsyncImage(
                model = rememberThumbnailRequest(entry),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to VortexPalette.Graphite.copy(alpha = 0.95f)
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = VortexPalette.Neon,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = formatDuration(state.positionMs) + " / " + formatDuration(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = VortexPalette.TextMid
                )
            }
            ProgressHairline(
                fraction = state.progressFraction,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
        Text(
            text = entry.title.ifBlank { entry.displayName },
            style = MaterialTheme.typography.titleMedium,
            color = VortexPalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp)
        )
    }
}
