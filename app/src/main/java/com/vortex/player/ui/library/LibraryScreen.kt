package com.vortex.player.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaEntry
import com.vortex.player.data.SortField
import com.vortex.player.data.ViewMode
import com.vortex.player.ui.theme.VortexMark
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenPlayer: () -> Unit,
    onRequestPopup: () -> Unit,
    onOpenDownloads: () -> Unit,
    appVersion: String = "",
    onCheckUpdates: () -> Unit = {},
    /** Aviso de actualización, si lo hay. Se pinta bajo la cabecera. */
    updateBanner: @Composable () -> Unit = {}
) {
    val permissions = rememberMultiplePermissionsState(mediaPermissions())
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val openFolder by viewModel.openFolder.collectAsStateWithLifecycle()
    val openPlaylist by viewModel.openPlaylist.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteRequest.collectAsStateWithLifecycle()

    var showPlaylistDialog by remember { mutableStateOf(false) }

    // El borrado lo confirma el sistema con su propio diálogo; aquí sólo se lanza y se
    // espera el resultado para refrescar la biblioteca.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { viewModel.onDeleteResolved() }

    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { sender ->
            viewModel.consumeDeleteRequest()
            runCatching {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2_400)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(permissions.allPermissionsGranted) {
        if (permissions.allPermissionsGranted) viewModel.refresh()
    }

    val visible = viewModel.visibleEntries(library)

    BackHandler(
        enabled = selection.isNotEmpty() || openFolder != null || openPlaylist != null || searchOpen
    ) {
        when {
            searchOpen -> viewModel.closeSearch()
            selection.isNotEmpty() -> viewModel.clearSelection()
            openFolder != null -> viewModel.openFolder(null)
            openPlaylist != null -> viewModel.openPlaylist(null)
        }
    }

    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        Column(Modifier.fillMaxSize()) {

            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = { viewModel.selectAll(visible) },
                    onQueue = viewModel::queueSelection,
                    onFavorite = viewModel::favoriteSelection,
                    onAddToPlaylist = { showPlaylistDialog = true },
                    onShare = viewModel::shareSelection,
                    onDelete = viewModel::deleteSelection
                )
            } else {
                VortexHeader(
                    onSearch = viewModel::openSearch,
                    onRefresh = viewModel::refresh,
                    onOpenDownloads = onOpenDownloads,
                    appVersion = appVersion,
                    onCheckUpdates = onCheckUpdates
                )
                updateBanner()
            }

            if (!permissions.allPermissionsGranted) {
                PermissionGate(onGrant = { permissions.launchMultiplePermissionRequest() })
                return@Column
            }

            TabStrip(selected = tab, onSelect = viewModel::selectTab)

            val showingTree = tab == LibraryTab.FOLDERS && openFolder == null
            val showingPlaylistIndex = tab == LibraryTab.PLAYLISTS && openPlaylist == null

            if (!showingTree && !showingPlaylistIndex) {
                SortAndViewBar(
                    sortField = prefs.sortField,
                    descending = prefs.descending,
                    viewMode = prefs.viewMode,
                    sortEnabled = !viewModel.playlistKeepsOrder(),
                    count = visible.size,
                    onSort = viewModel::setSort,
                    onToggleView = viewModel::toggleViewMode
                )
            }

            val bottomPadding = 108.dp +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            when {
                library.loading -> LoadingState()

                showingTree -> FolderTreeView(
                    root = library.folderTree,
                    expanded = expanded,
                    onToggleBranch = viewModel::toggleBranch,
                    onOpenFolder = { viewModel.openFolder(it) },
                    contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
                )

                showingPlaylistIndex -> PlaylistsIndex(
                    playlists = playlists,
                    favoritesCount = library.favorites.size,
                    onOpen = viewModel::openPlaylist,
                    onDelete = viewModel::deletePlaylist,
                    onCreate = { showPlaylistDialog = true },
                    contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
                )

                else -> Column(Modifier.fillMaxSize()) {
                    if (tab == LibraryTab.FOLDERS && openFolder != null) {
                        FolderBreadcrumbs(
                            trail = library.folderTree.trailTo(openFolder!!),
                            onNavigate = viewModel::openFolder
                        )
                    }
                    if (tab == LibraryTab.PLAYLISTS && openPlaylist != null) {
                        PlaylistHeader(
                            name = if (openPlaylist == LibraryViewModel.FAVORITES_ID) {
                                "Favoritos"
                            } else {
                                playlists.firstOrNull { it.playlist.id == openPlaylist }
                                    ?.playlist?.name.orEmpty()
                            },
                            count = visible.size,
                            onBack = { viewModel.openPlaylist(null) },
                            onPlayAll = {
                                visible.firstOrNull()?.let { viewModel.play(it, visible) }
                            }
                        )
                    }

                    if (visible.isEmpty()) {
                        EmptyState()
                    } else {
                        MediaCollection(
                            entries = visible,
                            library = library,
                            viewMode = prefs.viewMode,
                            selection = selection,
                            selectionActive = selection.isNotEmpty(),
                            showContinueRow = tab == LibraryTab.ALL,
                            bottomPadding = bottomPadding,
                            onOpen = { entry ->
                                viewModel.play(entry, visible)
                                onOpenPlayer()
                            },
                            onToggleSelect = viewModel::toggleSelection
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

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = VortexPalette.Graphite,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .background(VortexPalette.Neon, VortexShapes.small)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }

    if (searchOpen) {
        SearchOverlay(
            query = query,
            results = results,
            stateFor = { library.stateFor(it) },
            onQueryChange = viewModel::setQuery,
            onClose = viewModel::closeSearch,
            onOpenFolder = viewModel::revealFolder,
            onPlay = { entry, list ->
                viewModel.play(entry, list)
                viewModel.closeSearch()
                onOpenPlayer()
            }
        )
    }

    if (showPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showPlaylistDialog = false },
            onPick = { id ->
                viewModel.addSelectionToPlaylist(id)
                showPlaylistDialog = false
            },
            onCreate = { name ->
                viewModel.createPlaylist(name, withSelection = selection.isNotEmpty())
                showPlaylistDialog = false
            }
        )
    }
}

/**
 * Rejilla o lista según la preferencia, con el carrusel de "continuar" encima cuando
 * procede. Ambas vistas comparten el mismo tratamiento de selección.
 */
@Composable
private fun MediaCollection(
    entries: List<MediaEntry>,
    library: LibraryState,
    viewMode: ViewMode,
    selection: Set<String>,
    selectionActive: Boolean,
    showContinueRow: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: (MediaEntry) -> Unit,
    onToggleSelect: (MediaEntry) -> Unit
) {
    val continueList = if (showContinueRow) library.continueWatching else emptyList()

    // Con la selección activa, un toque simple selecciona en vez de reproducir: si no,
    // sería facilísimo lanzar un vídeo por error mientras marcas cosas.
    val handleClick: (MediaEntry) -> Unit = { entry ->
        if (selectionActive) onToggleSelect(entry) else onOpen(entry)
    }

    if (viewMode == ViewMode.LIST) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
        ) {
            items(entries, key = { it.uri.toString() }) { entry ->
                MediaRow(
                    entry = entry,
                    state = library.stateFor(entry),
                    selected = entry.uri.toString() in selection,
                    onClick = { handleClick(entry) },
                    onLongClick = { onToggleSelect(entry) }
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 4.dp,
            bottom = bottomPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (continueList.isNotEmpty() && !selectionActive) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("CONTINUAR") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(continueList, key = { it.first.uri.toString() }) { (entry, state) ->
                        ContinueCard(
                            entry = entry,
                            state = state,
                            onClick = { onOpen(entry) },
                            modifier = Modifier.width(230.dp)
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("BIBLIOTECA · ${entries.size}")
            }
        }

        items(entries, key = { it.uri.toString() }) { entry ->
            MediaCard(
                entry = entry,
                state = library.stateFor(entry),
                selected = entry.uri.toString() in selection,
                onClick = { handleClick(entry) },
                onLongClick = { onToggleSelect(entry) }
            )
        }
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
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDownloads: () -> Unit,
    appVersion: String,
    onCheckUpdates: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VortexMark(modifier = Modifier.size(30.dp), spinning = false, strokeWidth = 2.5.dp)
        Text(
            text = "VÓRTEX",
            style = MaterialTheme.typography.labelLarge,
            color = VortexPalette.TextHigh,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Buscar", tint = VortexPalette.TextMid)
        }
        IconButton(onClick = onOpenDownloads) {
            Icon(Icons.Filled.Download, contentDescription = "Descargas", tint = VortexPalette.Neon)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Reescanear", tint = VortexPalette.TextMid)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Más opciones",
                    tint = VortexPalette.TextMid
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = VortexPalette.GraphiteRaised
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Buscar actualizaciones",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VortexPalette.TextHigh
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onCheckUpdates()
                    }
                )
                DropdownMenuItem(
                    enabled = false,
                    text = {
                        Text(
                            "Versión $appVersion",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow
                        )
                    },
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun TabStrip(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
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

/** Criterio de orden y conmutador rejilla/lista. */
@Composable
private fun SortAndViewBar(
    sortField: SortField,
    descending: Boolean,
    viewMode: ViewMode,
    sortEnabled: Boolean,
    count: Int,
    onSort: (SortField) -> Unit,
    onToggleView: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.weight(1f)
        )

        if (sortEnabled) {
            Box {
                Row(
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = "Ordenar",
                        tint = VortexPalette.TextMid,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = sortField.label + if (descending) " ↓" else " ↑",
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = VortexPalette.GraphiteRaised
                ) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = field.label +
                                        if (field == sortField) {
                                            if (descending) "  ↓" else "  ↑"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (field == sortField) {
                                        VortexPalette.Neon
                                    } else {
                                        VortexPalette.TextMid
                                    }
                                )
                            },
                            onClick = {
                                onSort(field)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
        }

        IconButton(onClick = onToggleView) {
            Icon(
                imageVector = if (viewMode == ViewMode.GRID) {
                    Icons.AutoMirrored.Filled.List
                } else {
                    Icons.Filled.GridView
                },
                contentDescription = "Cambiar vista",
                tint = VortexPalette.TextMid
            )
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
private fun EmptyState() {
    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AQUÍ NO HAY NADA",
                style = MaterialTheme.typography.labelLarge,
                color = VortexPalette.TextMid
            )
            Text(
                text = "Cuando descargues o grabes algo, aparecerá aquí.",
                style = MaterialTheme.typography.bodyMedium,
                color = VortexPalette.TextLow,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
