package com.vortex.player.ui.cast

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.vortex.player.R
import com.vortex.player.cast.CastCoordinator
import com.vortex.player.cast.CastUiState
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

@Composable
fun CastRouteButton(modifier: Modifier = Modifier) {
    val state by CastCoordinator.state.collectAsStateWithLifecycle()
    if (!state.sdkAvailable) return
    AndroidView(
        factory = { context ->
            FrameLayout(context).also { container ->
                runCatching {
                    val themedContext = ContextThemeWrapper(context, R.style.Theme_Vortex_Cast)
                    MediaRouteButton(themedContext).also { button ->
                        CastButtonFactory.setUpMediaRouteButton(
                            themedContext,
                            ContextCompat.getMainExecutor(context),
                            button
                        )
                        button.contentDescription = "Enviar a pantalla"
                        container.addView(
                            button,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                }.onFailure { error ->
                    Log.e(CAST_UI_TAG, "No se pudo crear el selector de Cast", error)
                    // El selector es opcional: un fallo del SDK nunca debe cerrar VLC.
                    container.post(CastCoordinator::reportUiFailure)
                }
            }
        },
        modifier = modifier.size(48.dp)
    )
}

private const val CAST_UI_TAG = "VortexCastUi"

@Composable
fun RemoteCastOverlay(state: CastUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().background(VortexPalette.Graphite),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    state.mediaTitle ?: "REPRODUCCIÓN REMOTA",
                    style = MaterialTheme.typography.titleMedium,
                    color = VortexPalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.deviceName ?: "GOOGLE CAST",
                    style = MaterialTheme.typography.labelSmall,
                    color = VortexPalette.Cyan
                )
            }
            CastRouteButton()
        }

        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(VortexPalette.GraphiteHigh, VortexShapes.large)
                    .border(1.dp, VortexPalette.Cyan.copy(alpha = 0.6f), VortexShapes.large),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CastConnected,
                    contentDescription = null,
                    tint = VortexPalette.Neon,
                    modifier = Modifier.size(48.dp)
                )
            }
            Text(
                if (state.loading) "ENVIANDO…" else "REPRODUCIENDO EN ${state.deviceName.orEmpty().uppercase()}",
                style = MaterialTheme.typography.labelLarge,
                color = if (state.loading) VortexPalette.Amber else VortexPalette.Neon
            )
            Text(
                state.mediaTitle.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = VortexPalette.TextHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.durationMs > 0L) {
                Text(
                    "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexPalette.TextLow
                )
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexPalette.Amber
                )
            }
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = VortexPalette.Neon,
                    trackColor = VortexPalette.GraphiteHigh
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = CastCoordinator::toggleRemotePlayback,
                enabled = !state.loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VortexPalette.Neon,
                    contentColor = VortexPalette.Graphite
                )
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Text(if (state.isPlaying) "PAUSAR EN TV" else "REPRODUCIR EN TV")
            }
            OutlinedButton(onClick = { CastCoordinator.openExpandedControls(context) }) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null)
                Text("CONTROLES COMPLETOS")
            }
            TextButton(onClick = CastCoordinator::stopCasting) {
                Icon(Icons.Filled.StopCircle, contentDescription = null, tint = VortexPalette.Magenta)
                Text("DETENER ENVÍO", color = VortexPalette.Magenta)
            }
        }
    }
}
