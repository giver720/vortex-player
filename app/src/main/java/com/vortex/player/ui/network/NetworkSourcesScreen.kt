package com.vortex.player.ui.network

import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.data.MediaEntry
import com.vortex.player.network.NetworkMediaKind
import com.vortex.player.network.NetworkSource
import com.vortex.player.network.NetworkSourceParseResult
import com.vortex.player.ui.theme.HudPanel
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

@Composable
fun NetworkSourcesScreen(
    viewModel: NetworkSourcesViewModel,
    onBack: () -> Unit,
    onPlay: (MediaEntry) -> Unit
) {
    val url by viewModel.url.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3_000)
            viewModel.clearMessage()
        }
    }

    val favorites = sources.filter { it.favorite }.sortedBy { it.title.lowercase() }
    val recent = sources.filterNot { it.favorite }.sortedByDescending { it.lastOpenedAtMs }
    val playTyped = {
        viewModel.playInput()?.let(onPlay)
        Unit
    }

    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                NetworkHeader(
                    connection = connection,
                    onBack = onBack
                )
            }

            message?.let { text ->
                item {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (text.startsWith("No ") || text.contains("válid")) {
                            VortexPalette.Magenta
                        } else {
                            VortexPalette.Cyan
                        },
                        modifier = Modifier
                            .padding(horizontal = 14.dp)
                            .fillMaxWidth()
                            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
                            .clickable(onClick = viewModel::clearMessage)
                            .padding(10.dp)
                    )
                }
            }

            item {
                HudPanel(
                    modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
                    accent = VortexPalette.Neon,
                    accentBorder = true,
                    contentPadding = 14.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "ABRIR UNA FUENTE",
                            style = MaterialTheme.typography.labelLarge,
                            color = VortexPalette.Neon
                        )
                        Text(
                            "Pega una transmisión, cámara IP, radio o archivo remoto. " +
                                "Vórtex ajustará el búfer y la reconexión automáticamente.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VortexPalette.TextMid
                        )
                        OutlinedTextField(
                            value = url,
                            onValueChange = viewModel::setUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL de red") },
                            placeholder = { Text("https://… · rtsp://…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { playTyped() }),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        clipboard.getText()?.text?.takeIf { it.isNotBlank() }
                                            ?.let(viewModel::setUrl)
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentPaste,
                                        contentDescription = "Pegar URL",
                                        tint = VortexPalette.Cyan
                                    )
                                }
                            },
                            colors = networkTextFieldColors()
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = viewModel::setTitle,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nombre opcional") },
                            placeholder = { Text("Cámara, canal o radio") },
                            singleLine = true,
                            colors = networkTextFieldColors()
                        )

                        when (val parsed = analysis) {
                            is NetworkSourceParseResult.Valid -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    NetworkBadge(parsed.draft.protocol.label, VortexPalette.Neon)
                                    NetworkBadge(parsed.draft.mediaKind.label, VortexPalette.Cyan)
                                    if (parsed.draft.protocol.liveByDefault) {
                                        NetworkBadge("EN VIVO", VortexPalette.Magenta)
                                    }
                                }
                                if (!parsed.draft.canPersist) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Security,
                                            contentDescription = null,
                                            tint = VortexPalette.Amber,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Text(
                                            "  Enlace privado: se reproducirá sin guardarlo.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VortexPalette.Amber
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text(
                                        "TIPO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VortexPalette.TextLow
                                    )
                                    NetworkKindButton(
                                        kind = NetworkMediaKind.VIDEO,
                                        selected = parsed.draft.mediaKind == NetworkMediaKind.VIDEO,
                                        onClick = { viewModel.setMediaKind(NetworkMediaKind.VIDEO) }
                                    )
                                    NetworkKindButton(
                                        kind = NetworkMediaKind.AUDIO,
                                        selected = parsed.draft.mediaKind == NetworkMediaKind.AUDIO,
                                        onClick = { viewModel.setMediaKind(NetworkMediaKind.AUDIO) }
                                    )
                                }
                            }
                            is NetworkSourceParseResult.Invalid -> Text(
                                parsed.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = VortexPalette.Magenta
                            )
                            null -> Unit
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Button(
                                onClick = playTyped,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VortexPalette.Neon,
                                    contentColor = VortexPalette.Graphite
                                )
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("REPRODUCIR")
                            }
                            OutlinedButton(
                                onClick = viewModel::saveInputAsFavorite,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.StarBorder, contentDescription = null)
                                Text("GUARDAR")
                            }
                        }
                        Text(
                            "COMPATIBLE · HTTP(S) · HLS/M3U8 · RTSP · RTMP · MMS · UDP · TCP",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.TextLow
                        )
                    }
                }
            }

            if (favorites.isNotEmpty()) {
                item { NetworkSectionTitle("FAVORITOS · ${favorites.size}") }
                items(favorites, key = { "favorite-${it.url}" }) { source ->
                    NetworkSourceCard(
                        source = source,
                        onPlay = { viewModel.play(source)?.let(onPlay) },
                        onFavorite = { viewModel.toggleFavorite(source) },
                        onDelete = { viewModel.remove(source) }
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "RECIENTES · ${recent.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = VortexPalette.TextLow,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearRecent) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = "Limpiar recientes",
                                tint = VortexPalette.Magenta
                            )
                        }
                    }
                }
                items(recent, key = { "recent-${it.url}" }) { source ->
                    NetworkSourceCard(
                        source = source,
                        onPlay = { viewModel.play(source)?.let(onPlay) },
                        onFavorite = { viewModel.toggleFavorite(source) },
                        onDelete = { viewModel.remove(source) }
                    )
                }
            }

            if (sources.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Wifi,
                            contentDescription = null,
                            tint = VortexPalette.TextLow,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            "Aún no hay fuentes guardadas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VortexPalette.TextLow,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkHeader(connection: NetworkConnectionUi, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
                "FUENTES DE RED",
                style = MaterialTheme.typography.titleLarge,
                color = VortexPalette.TextHigh
            )
            Text(
                if (connection.metered) "${connection.transport} · RED MEDIDA" else connection.transport,
                style = MaterialTheme.typography.labelSmall,
                color = if (connection.connected) VortexPalette.Cyan else VortexPalette.Magenta
            )
        }
        Icon(
            if (connection.connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            contentDescription = null,
            tint = if (connection.connected) VortexPalette.Neon else VortexPalette.Magenta,
            modifier = Modifier.padding(end = 14.dp).size(26.dp)
        )
    }
}

@Composable
private fun NetworkSourceCard(
    source: NetworkSource,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    HudPanel(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        contentPadding = 10.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(VortexPalette.GraphiteHigh, VortexShapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Reproducir",
                    tint = VortexPalette.Neon
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = VortexPalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    source.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexPalette.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NetworkBadge(source.protocol.label, VortexPalette.Cyan)
                    NetworkBadge(source.mediaKind.label, VortexPalette.TextMid)
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            source.lastOpenedAtMs,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexPalette.TextLow,
                        maxLines = 1
                    )
                }
            }
            Column {
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (source.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (source.favorite) "Quitar favorito" else "Favorito",
                        tint = if (source.favorite) VortexPalette.Amber else VortexPalette.TextLow
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Eliminar",
                        tint = VortexPalette.TextLow
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(0.5.dp, color.copy(alpha = 0.65f), VortexShapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun NetworkKindButton(
    kind: NetworkMediaKind,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = kind.label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) VortexPalette.Graphite else VortexPalette.TextMid,
        modifier = Modifier
            .background(
                if (selected) VortexPalette.Cyan else VortexPalette.GraphiteHigh,
                VortexShapes.small
            )
            .border(
                0.5.dp,
                if (selected) VortexPalette.Cyan else VortexPalette.Outline,
                VortexShapes.small
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun NetworkSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = VortexPalette.TextLow,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
    )
}

@Composable
private fun networkTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = VortexPalette.Graphite,
    unfocusedContainerColor = VortexPalette.Graphite,
    focusedTextColor = VortexPalette.TextHigh,
    unfocusedTextColor = VortexPalette.TextHigh,
    focusedIndicatorColor = VortexPalette.Neon,
    unfocusedIndicatorColor = VortexPalette.Outline,
    focusedLabelColor = VortexPalette.Neon,
    unfocusedLabelColor = VortexPalette.TextLow,
    cursorColor = VortexPalette.Neon
)
