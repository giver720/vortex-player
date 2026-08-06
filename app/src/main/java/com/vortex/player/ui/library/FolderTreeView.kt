package com.vortex.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.player.data.FolderNode
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.theme.VortexPalette

/**
 * Árbol de carpetas con ramas plegables.
 *
 * El chevron y el cuerpo de la fila hacen cosas distintas a propósito: el chevron abre la
 * rama sin salir del árbol, y el resto de la fila entra en la carpeta. Mezclar ambas
 * acciones en un solo toque es lo que hace incómodos a la mayoría de exploradores.
 */
@Composable
fun FolderTreeView(
    root: FolderNode,
    expanded: Set<String>,
    onToggleBranch: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val rows = remember(root, expanded) { flattenTree(root, expanded) }

    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "NO HAY CARPETAS",
                style = MaterialTheme.typography.labelMedium,
                color = VortexPalette.TextLow
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        items(rows, key = { it.path }) { node ->
            FolderBranchRow(
                node = node,
                isExpanded = node.path in expanded,
                onToggleBranch = { onToggleBranch(node.path) },
                onOpen = { onOpenFolder(node.path) }
            )
        }
    }
}

@Composable
private fun FolderBranchRow(
    node: FolderNode,
    isExpanded: Boolean,
    onToggleBranch: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            // La sangría es la única señal de jerarquía, así que se mantiene generosa
            // pero con tope: a partir del sexto nivel dejaría de caber el nombre.
            .padding(start = (10 + minOf(node.depth - 1, 5) * 16).dp, end = 12.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(
                    if (node.children.isNotEmpty()) {
                        Modifier.clickable(onClick = onToggleBranch)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (node.children.isNotEmpty()) {
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (isExpanded) "Cerrar rama" else "Abrir rama",
                    tint = VortexPalette.TextMid,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                // Punto de rama terminal: mantiene alineadas las filas sin hijos.
                Box(
                    Modifier
                        .size(4.dp)
                        .background(VortexPalette.Outline, androidx.compose.foundation.shape.CircleShape)
                )
            }
        }

        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (isExpanded) VortexPalette.Neon else VortexPalette.NeonDim,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(19.dp)
        )

        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.titleMedium,
                color = VortexPalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val direct = node.files.size
            val nested = node.totalFiles - direct
            Text(
                text = buildString {
                    append("$direct aquí")
                    if (nested > 0) append(" · $nested en subcarpetas")
                    val duration = node.files.sumOf { it.durationMs }
                    if (duration > 0) append(" · ${formatDuration(duration)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = VortexPalette.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = node.totalFiles.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.TextMid
        )
    }
}

/** Migas de pan de la carpeta abierta, desplazables cuando la ruta es larga. */
@Composable
fun FolderBreadcrumbs(
    trail: List<FolderNode>,
    onNavigate: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        trail.forEachIndexed { index, node ->
            val isLast = index == trail.lastIndex
            Text(
                text = node.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isLast) VortexPalette.Neon else VortexPalette.TextLow,
                maxLines = 1,
                modifier = Modifier
                    .clickable {
                        // El primer nivel es la raíz sintética: volver ahí es volver al árbol.
                        onNavigate(if (index == 0) null else node.path)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
            if (!isLast) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = VortexPalette.Outline,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Aplana el árbol a las filas realmente visibles según las ramas abiertas. */
private fun flattenTree(root: FolderNode, expanded: Set<String>): List<FolderNode> {
    val out = mutableListOf<FolderNode>()
    fun walk(node: FolderNode) {
        node.children.forEach { child ->
            out += child
            if (child.path in expanded) walk(child)
        }
    }
    walk(root)
    return out
}
