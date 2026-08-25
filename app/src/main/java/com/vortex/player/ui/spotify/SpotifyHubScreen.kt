package com.vortex.player.ui.spotify

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vortex.player.data.db.SpotifyPlaylistEntity
import com.vortex.player.spotify.LocalMatchQuality
import com.vortex.player.spotify.SpotifyAccountState
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

@Composable
fun SpotifyHubScreen(
    viewModel: SpotifyHubViewModel,
    onBack: () -> Unit,
    onOpenSpotify: (String) -> Unit,
    onPlayLocal: (String) -> Unit
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPlaylist.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(account) {
        if (account is SpotifyAccountState.Connected && playlists.isEmpty()) {
            viewModel.refreshPlaylists()
        }
    }
    BackHandler(enabled = selected != null) { viewModel.closePlaylist() }

    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            )
        ) {
            item {
                SpotifyHeader(
                    title = selected?.name ?: "TU SPOTIFY",
                    subtitle = when {
                        selected != null -> "${selected?.itemCount ?: tracks.size} canciones"
                        account is SpotifyAccountState.Connected ->
                            (account as SpotifyAccountState.Connected).displayName
                        else -> "Cuenta sin conectar"
                    },
                    refreshing = refreshing,
                    onBack = if (selected != null) viewModel::closePlaylist else onBack,
                    onRefresh = if (selected != null) {
                        viewModel::refreshSelectedPlaylist
                    } else {
                        viewModel::refreshPlaylists
                    }
                )
            }

            error?.let { message ->
                item {
                    Text(
                        text = "$message · TOCA PARA CERRAR",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPalette.Magenta,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .background(VortexPalette.GraphiteRaised, VortexShapes.small)
                            .clickable(onClick = viewModel::consumeError)
                            .padding(10.dp)
                    )
                }
            }

            message?.let { notice ->
                item {
                    Text(
                        text = "$notice · TOCA PARA CERRAR",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPalette.Graphite,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                            .background(VortexPalette.Neon, VortexShapes.small)
                            .clickable(onClick = viewModel::consumeMessage).padding(10.dp)
                    )
                }
            }

            item {
                Text(
                    text = "DATOS PROPORCIONADOS POR SPOTIFY · SOLO LECTURA",
                    style = MaterialTheme.typography.labelSmall,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            if (selected == null) {
                if (playlists.isEmpty()) {
                    item {
                        SpotifyEmptyState(
                            if (refreshing) "Sincronizando tus playlists…" else
                                "No hay playlists en la caché. Pulsa actualizar para sincronizar."
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "${playlists.size} PLAYLISTS · CACHÉ DISPONIBLE SIN CONEXIÓN",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        SpotifyPlaylistRow(
                            playlist = playlist,
                            onOpen = { viewModel.openPlaylist(playlist.id) },
                            onOpenSpotify = { playlist.spotifyUrl?.let(onOpenSpotify) }
                        )
                    }
                }
            } else {
                item {
                    val exact = tracks.count {
                        it.localMatch?.quality == LocalMatchQuality.EXACT
                    }
                    val likely = tracks.count {
                        it.localMatch?.quality == LocalMatchQuality.LIKELY
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(15.dp)
                    ) {
                        HubMetric("PISTAS", tracks.size)
                        HubMetric("EN EL MÓVIL", exact, VortexPalette.Neon)
                        HubMetric("PROBABLES", likely, VortexPalette.Cyan)
                    }
                }
                item {
                    val matched = tracks.count { it.localMatch != null }
                    Button(
                        onClick = viewModel::importCurrentToVortex,
                        enabled = tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VortexPalette.Neon,
                            contentColor = VortexPalette.Graphite
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                        Text("IMPORTAR A VÓRTEX · $matched LOCALES · ${tracks.size - matched} FALTAN")
                    }
                }
                if (tracks.isEmpty()) {
                    item {
                        SpotifyEmptyState(
                            if (refreshing) "Leyendo canciones…" else
                                "No hay canciones en la caché de esta playlist."
                        )
                    }
                } else {
                    items(
                        tracks,
                        key = { "${it.track.position}-${it.track.spotifyId.orEmpty()}" }
                    ) { item ->
                        SpotifyTrackRow(
                            item = item,
                            onPlayLocal = { item.localMatch?.local?.uri?.let(onPlayLocal) },
                            onOpenSpotify = { item.track.spotifyUrl?.let(onOpenSpotify) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyHeader(
    title: String,
    subtitle: String,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = VortexPalette.TextHigh
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = VortexPalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Sincronizar",
                tint = if (refreshing) VortexPalette.TextLow else VortexPalette.Neon
            )
        }
    }
}

@Composable
private fun SpotifyPlaylistRow(
    playlist: SpotifyPlaylistEntity,
    onOpen: () -> Unit,
    onOpenSpotify: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.medium)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpotifyArtwork(playlist.imageUrl, playlist.name)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = VortexPalette.TextHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("${playlist.itemCount} canciones")
                    if (playlist.owner.isNotBlank()) append(" · ${playlist.owner}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (playlist.spotifyUrl != null) {
            IconButton(onClick = onOpenSpotify) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Abrir en Spotify",
                    tint = VortexPalette.Cyan
                )
            }
        }
    }
}

@Composable
private fun SpotifyTrackRow(
    item: SpotifyTrackUi,
    onPlayLocal: () -> Unit,
    onOpenSpotify: () -> Unit
) {
    val match = item.localMatch
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = match != null, onClick = onPlayLocal)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (item.track.position + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.width(27.dp)
        )
        SpotifyArtwork(item.track.imageUrl, item.track.title, 45)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                text = item.track.title,
                style = MaterialTheme.typography.titleMedium,
                color = VortexPalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(item.track.artist)
                    if (item.track.durationMs > 0) {
                        append(" · ${formatDuration(item.track.durationMs)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            match?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = if (it.quality == LocalMatchQuality.EXACT) {
                            VortexPalette.Neon
                        } else {
                            VortexPalette.Cyan
                        },
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = it.quality.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.quality == LocalMatchQuality.EXACT) {
                            VortexPalette.Neon
                        } else {
                            VortexPalette.Cyan
                        }
                    )
                }
            }
        }
        if (item.track.spotifyUrl != null) {
            IconButton(onClick = onOpenSpotify) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Abrir en Spotify",
                    tint = VortexPalette.TextLow,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SpotifyArtwork(url: String?, description: String, size: Int = 54) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = description,
            // Spotify exige conservar la portada completa, sin recortarla ni superponerla.
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size.dp).clip(VortexShapes.small)
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp)
                .background(VortexPalette.GraphiteHigh, VortexShapes.small),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = VortexPalette.TextLow
            )
        }
    }
}

@Composable
private fun HubMetric(label: String, value: Int, color: androidx.compose.ui.graphics.Color = VortexPalette.TextMid) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = VortexPalette.TextLow)
    }
}

@Composable
private fun SpotifyEmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp)
    )
}
