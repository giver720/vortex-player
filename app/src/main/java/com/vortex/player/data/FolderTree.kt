package com.vortex.player.data

/**
 * Una rama del árbol de carpetas.
 *
 * [files] son los medios que cuelgan directamente de esta carpeta; [totalFiles] incluye
 * además los de toda su descendencia, que es el número que la gente espera ver al lado
 * de una carpeta cerrada.
 */
data class FolderNode(
    val path: String,
    val name: String,
    val children: List<FolderNode>,
    val files: List<MediaEntry>,
    val totalFiles: Int,
    val depth: Int
) {
    val isLeaf: Boolean get() = children.isEmpty()

    /** Recorre el árbol buscando la rama de una ruta concreta. */
    fun find(target: String): FolderNode? {
        if (path == target) return this
        children.forEach { child ->
            if (target.startsWith(child.path)) child.find(target)?.let { return it }
        }
        return null
    }

    /** Cadena de ancestros hasta [target], incluido, para pintar las migas de pan. */
    fun trailTo(target: String): List<FolderNode> {
        if (path == target) return listOf(this)
        children.forEach { child ->
            if (target.startsWith(child.path)) {
                val rest = child.trailTo(target)
                if (rest.isNotEmpty()) return listOf(this) + rest
            }
        }
        return emptyList()
    }
}

private class MutableNode(val path: String, val name: String) {
    val children = linkedMapOf<String, MutableNode>()
    val files = mutableListOf<MediaEntry>()
}

/**
 * Construye la jerarquía a partir de las rutas planas que devuelve MediaStore.
 *
 * El detalle que hace la diferencia es el colapso de cadenas: en Android casi todo cuelga
 * de `/storage/emulated/0`, así que sin colapsar habría que dar cuatro toques antes de ver
 * nada. Las ramas con un solo hijo y ningún fichero propio se funden con su hijo, de modo
 * que el árbol empieza donde de verdad se bifurca.
 */
fun buildFolderTree(entries: List<MediaEntry>): FolderNode {
    val root = MutableNode("", "Almacenamiento")

    entries.forEach { entry ->
        if (entry.folderPath.isBlank()) {
            root.files += entry
            return@forEach
        }
        val segments = entry.folderPath.split('/').filter { it.isNotBlank() }
        var current = root
        var accumulated = StringBuilder()
        segments.forEach { segment ->
            accumulated.append('/').append(segment)
            val childPath = accumulated.toString()
            current = current.children.getOrPut(childPath) { MutableNode(childPath, segment) }
        }
        current.files += entry
    }

    return root.freeze(depth = 0).let { collapse(it) }
}

private fun MutableNode.freeze(depth: Int): FolderNode {
    val frozenChildren = children.values
        .map { it.freeze(depth + 1) }
        .sortedWith(compareByDescending<FolderNode> { it.totalFiles }.thenBy { it.name.lowercase() })
    return FolderNode(
        path = path,
        name = name,
        children = frozenChildren,
        files = files.sortedBy { it.displayName.lowercase() },
        totalFiles = files.size + frozenChildren.sumOf { it.totalFiles },
        depth = depth
    )
}

/**
 * Funde las ramas intermedias que no aportan bifurcación. `/storage/emulated/0/DCIM` con
 * un único hijo `Camera` y sin ficheros propios se muestra como una sola entrada.
 */
private fun collapse(node: FolderNode): FolderNode {
    var current = node
    while (current.children.size == 1 && current.files.isEmpty()) {
        val only = current.children.first()
        current = only.copy(
            name = if (current.path.isEmpty()) only.name else "${current.name}/${only.name}",
            depth = current.depth
        )
    }
    return current.copy(children = current.children.map { collapse(it) }.map { it.reDepth(current.depth + 1) })
}

/** Reasigna la profundidad tras un colapso, para que la sangría del árbol siga cuadrando. */
private fun FolderNode.reDepth(newDepth: Int): FolderNode = copy(
    depth = newDepth,
    children = children.map { it.reDepth(newDepth + 1) }
)
