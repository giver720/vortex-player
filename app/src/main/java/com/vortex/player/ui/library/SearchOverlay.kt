package com.vortex.player.ui.library

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.SearchResults
import com.vortex.player.data.db.MediaStateEntity
import com.vortex.player.data.highlightRanges
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Búsqueda a pantalla completa con resultados agrupados.
 *
 * Las carpetas van primero porque suelen ser el destino real: cuando alguien busca
 * "vacaciones" casi nunca quiere un fichero suelto, quiere el sitio donde están todos.
 */
@Composable
fun SearchOverlay(
    query: String,
    results: SearchResults,
    stateFor: (MediaEntry) -> MediaStateEntity?,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onPlay: (MediaEntry, List<MediaEntry>) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(VortexPalette.Graphite)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = VortexPalette.Neon)
            Box(
                Modifier
                    .weight(1f)
                    .background(VortexPalette.GraphiteRaised, VortexShapes.small)
                    .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Nombre, carpeta o ruta…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VortexPalette.TextLow
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = VortexPalette.TextHigh
                    ),
                    cursorBrush = SolidColor(VortexPalette.Neon),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar búsqueda",
                    tint = VortexPalette.TextMid
                )
            }
        }

        when {
            query.trim().length < 2 -> Hint("Escribe al menos dos caracteres.")
            results.isEmpty -> Hint("Sin coincidencias para «${results.query}».")
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding()
                )
            ) {
                if (results.folders.isNotEmpty()) {
                    item { GroupHeader("CARPETAS", results.folders.size) }
                    items(results.folders, key = { "f" + it.path }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFolder(folder.path) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = VortexPalette.Neon,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = highlighted(folder.name, results.query),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = VortexPalette.TextHigh,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = folder.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VortexPalette.TextLow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = folder.totalFiles.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = VortexPalette.TextMid
                            )
                        }
                    }
                }

                if (results.videos.isNotEmpty()) {
                    item { GroupHeader("VÍDEOS", results.videos.size) }
                    items(results.videos, key = { "v" + it.uri }) { entry ->
                        MediaRow(
                            entry = entry,
                            state = stateFor(entry),
                            onClick = { onPlay(entry, results.videos) }
                        )
                    }
                }

                if (results.audios.isNotEmpty()) {
                    item { GroupHeader("AUDIOS", results.audios.size) }
                    items(results.audios, key = { "a" + it.uri }) { entry ->
                        MediaRow(
                            entry = entry,
                            state = stateFor(entry),
                            onClick = { onPlay(entry, results.audios) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int) {
    Text(
        text = "$label · $count",
        style = MaterialTheme.typography.labelMedium,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(top = 30.dp)
        )
    }
}

/** Resalta en neón el fragmento coincidente, para que se vea por qué salió ese resultado. */
@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    val ranges = highlightRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.first > cursor) append(text.substring(cursor, range.first))
            withStyle(
                SpanStyle(color = VortexPalette.Neon, fontWeight = FontWeight.Bold)
            ) {
                append(text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
