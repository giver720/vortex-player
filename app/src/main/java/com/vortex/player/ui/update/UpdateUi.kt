package com.vortex.player.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.player.ui.common.formatSize
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/** Aviso discreto en la cabecera cuando hay una versión nueva publicada. */
@Composable
fun UpdateBanner(
    versionName: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .border(0.5.dp, VortexPalette.Neon.copy(alpha = 0.45f), VortexShapes.small)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.SystemUpdateAlt,
            contentDescription = null,
            tint = VortexPalette.Neon,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Vórtex $versionName disponible",
            style = MaterialTheme.typography.titleMedium,
            color = VortexPalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "VER",
            style = MaterialTheme.typography.labelLarge,
            color = VortexPalette.Neon,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Box(
            Modifier
                .size(32.dp)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Descartar",
                tint = VortexPalette.TextLow,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Diálogo de actualización. Cubre los cuatro estados por los que pasa el proceso sin
 * cambiar de superficie, para que el usuario no pierda de vista lo que está ocurriendo.
 */
@Composable
fun UpdateDialog(
    stage: UpdateStage,
    currentVersion: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    if (stage is UpdateStage.Idle) return

    val downloading = stage is UpdateStage.Downloading

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        containerColor = VortexPalette.GraphiteRaised,
        titleContentColor = VortexPalette.TextHigh,
        textContentColor = VortexPalette.TextMid,
        title = {
            Text(
                text = when (stage) {
                    is UpdateStage.Checking -> "BUSCANDO ACTUALIZACIONES"
                    is UpdateStage.Available -> "VÓRTEX ${stage.release.versionName}"
                    is UpdateStage.Downloading -> "DESCARGANDO"
                    is UpdateStage.ReadyToInstall -> "LISTO PARA INSTALAR"
                    is UpdateStage.UpToDate -> "ESTÁS AL DÍA"
                    is UpdateStage.Failed -> "NO SE PUDO ACTUALIZAR"
                    UpdateStage.Idle -> ""
                },
                style = MaterialTheme.typography.labelLarge
            )
        },
        text = {
            when (stage) {
                is UpdateStage.Checking -> Text(
                    "Consultando las publicaciones del repositorio…",
                    style = MaterialTheme.typography.bodyMedium
                )

                is UpdateStage.Available -> Column {
                    val asset = stage.release.assetForThisDevice()
                    Text(
                        text = "Tienes la $currentVersion. " +
                            (asset?.let { "Se descargarán ${formatSize(it.sizeBytes)} (${it.abi})." }
                                ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPalette.TextLow
                    )
                    if (stage.release.notes.isNotBlank()) {
                        Text(
                            text = stage.release.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                is UpdateStage.Downloading -> Column {
                    Text(
                        text = "${(stage.progress * 100).toInt()} %",
                        style = MaterialTheme.typography.headlineMedium,
                        color = VortexPalette.Neon
                    )
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(VortexPalette.Outline)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(stage.progress)
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(VortexPalette.Cyan, VortexPalette.Neon)
                                    )
                                )
                        )
                    }
                }

                is UpdateStage.ReadyToInstall -> Text(
                    text = "El sistema pedirá confirmación para instalar. Si es la primera " +
                        "vez, tendrás que autorizar a Vórtex como origen de instalación.",
                    style = MaterialTheme.typography.bodyMedium
                )

                is UpdateStage.UpToDate -> Text(
                    text = "La ${stage.version} es la última publicada.",
                    style = MaterialTheme.typography.bodyMedium
                )

                is UpdateStage.Failed -> Text(
                    text = stage.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexPalette.Magenta
                )

                UpdateStage.Idle -> Unit
            }
        },
        confirmButton = {
            when (stage) {
                is UpdateStage.Available -> TextButton(onClick = onDownload) {
                    Text("DESCARGAR", color = VortexPalette.Neon)
                }
                is UpdateStage.ReadyToInstall -> TextButton(onClick = onInstall) {
                    Text("INSTALAR", color = VortexPalette.Neon)
                }
                else -> if (!downloading) {
                    TextButton(onClick = onDismiss) {
                        Text("CERRAR", color = VortexPalette.TextMid)
                    }
                }
            }
        },
        dismissButton = {
            when (stage) {
                is UpdateStage.Available -> TextButton(onClick = onSkip) {
                    Text("OMITIR ESTA", color = VortexPalette.TextLow)
                }
                is UpdateStage.ReadyToInstall -> TextButton(onClick = onDismiss) {
                    Text("LUEGO", color = VortexPalette.TextLow)
                }
                else -> Unit
            }
        }
    )
}
