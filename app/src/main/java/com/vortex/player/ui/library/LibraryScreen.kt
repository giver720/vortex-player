package com.vortex.player.ui.library

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.vortex.player.data.MediaEntry
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.theme.VortexMark
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenPlayer: () -> Unit,
    onRequestPopup: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    val permissions = rememberMultiplePermissionsState(mediaPermissions())
    val library by viewModel.library.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val openFolder by viewModel.openFolder.collectAsStateWithLifecycle()

    // El escaneo arranca en cuanto hay permiso, sin botón de por medio.
    LaunchedEffect(permissions.allPermissionsGranted) {
        if (permissions.allPermissionsGranted) viewModel.refresh()
    }

    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        Column(Modifier.fillMaxSize()) {
            VortexHeader(
                query = query,
                onQueryChange = viewModel::setQuery,
                onRefresh = viewModel::refresh,
                onOpenDownloads = onOpenDownloads
            )

            if (!permissions.allPermissionsGranted) {
                PermissionGate(onGrant = { permissions.launchMultiplePermissionRequest() })
                return@Column
            }

            TabStrip(selected = tab, onSelect = viewModel::selectTab)

            val visible = viewModel.visibleEntries(library)
            val showFolderList = tab == LibraryTab.FOLDERS && openFolder == null

            when {
                library.loading -> LoadingState()

                showFolderList -> FolderGrid(
                    folders = library.folders.map { it.path to it.name to it.entries.size },
                    onOpen = viewModel::openFolder
                )

                visible.isEmpty() -> EmptyState(hasQuery = query.isNotBlank())

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(168.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        // Deja aire para que el dock no tape la última fila.
                        bottom = 108.dp + WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding()
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val continueList = library.continueWatching
                    if (tab == LibraryTab.ALL && continueList.isNotEmpty() && query.isBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle("CONTINUAR")
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(continueList, key = { it.first.uri.toString() }) { (entry, state) ->
                                    ContinueCard(
                                        entry = entry,
                                        state = state,
                                        onClick = {
                                            viewModel.play(entry, continueList.map { it.first })
                                            onOpenPlayer()
                                        },
                                        modifier = Modifier.width(230.dp)
                                    )
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle("BIBLIOTECA · ${visible.size}")
                        }
                    }

                    items(visible, key = { it.uri.toString() }) { entry ->
                        MediaCard(
                            entry = entry,
                            state = library.stateFor(entry),
                            onClick = {
                                viewModel.play(entry, visible)
                                onOpenPlayer()
                            },
                            onLongClick = { viewModel.toggleFavorite(entry) }
                        )
                    }
                }
            }
        }

        NowPlayingDock(
            onExpand = onOpenPlayer,
            onRequestPopup = onRequestPopup,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    bottom = 10.dp + WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding()
                )
        )
    }
}

private fun mediaPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@Composable
private fun VortexHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    var searching by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VortexMark(modifier = Modifier.size(30.dp), spinning = false, strokeWidth = 2.5.dp)

        if (searching) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = VortexPalette.TextHigh),
                cursorBrush = SolidColor(VortexPalette.Neon),
                modifier = Modifier
                    .weight(1f)
                    .background(VortexPalette.GraphiteRaised, VortexShapes.small)
                    .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        } else {
            Text(
                text = "VÓRTEX",
                style = MaterialTheme.typography.labelLarge,
                color = VortexPalette.TextHigh,
                modifier = Modifier.weight(1f)
            )
        }

        IconButton(
            onClick = {
                searching = !searching
                if (!searching) onQueryChange("")
            }
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Buscar",
                tint = if (searching) VortexPalette.Neon else VortexPalette.TextMid
            )
        }
        IconButton(onClick = onOpenDownloads) {
            Icon(
                Icons.Filled.Download,
                contentDescription = "Descargas",
                tint = VortexPalette.Neon
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Reescanear", tint = VortexPalette.TextMid)
        }
    }
}

/**
 * Selector de pestañas con subrayado de neón. Se usa una barra bajo el rótulo en vez de
 * un fondo relleno para no introducir más superficies claras en un tema tan oscuro.
 */
@Composable
private fun TabStrip(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        LibraryTab.entries.forEach { entry ->
            val active = entry == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(entry) }
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) VortexPalette.Neon else VortexPalette.TextLow
                )
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .height(2.dp)
                        .width(if (active) 22.dp else 0.dp)
                        .background(VortexPalette.Neon)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun FolderGrid(
    folders: List<Pair<Pair<String, String>, Int>>,
    onOpen: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(folders, key = { it.first.first }) { (pathAndName, count) ->
            val (path, name) = pathAndName
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
                    .border(0.5.dp, VortexPalette.Outline, VortexShapes.medium)
                    .clickable { onOpen(path) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = VortexPalette.Neon)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = VortexPalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$count elementos",
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VortexMark(modifier = Modifier.size(96.dp), spinning = true)
        Text(
            text = "VÓRTEX NECESITA VER TUS MEDIOS",
            style = MaterialTheme.typography.labelLarge,
            color = VortexPalette.TextHigh,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            text = "Para construir tu biblioteca hace falta acceso a los vídeos y audios " +
                "del dispositivo. Nada sale del teléfono.",
            style = MaterialTheme.typography.bodyMedium,
            color = VortexPalette.TextMid,
            modifier = Modifier.padding(top = 10.dp)
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = 22.dp)) {
            Text("CONCEDER ACCESO", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VortexMark(modifier = Modifier.size(72.dp), spinning = true)
            Text(
                text = "ESCANEANDO",
                style = MaterialTheme.typography.labelMedium,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean) {
    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (hasQuery) "SIN RESULTADOS" else "SIN MEDIOS TODAVÍA",
                style = MaterialTheme.typography.labelLarge,
                color = VortexPalette.TextMid
            )
            Text(
                text = if (hasQuery) {
                    "Prueba con otro término."
                } else {
                    "Cuando descargues o grabes algo, aparecerá aquí."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Etiqueta de duración total, reutilizada por la vista de carpeta. */
internal fun folderSubtitle(entries: List<MediaEntry>): String =
    "${entries.size} · ${formatDuration(entries.sumOf { it.durationMs })}"
