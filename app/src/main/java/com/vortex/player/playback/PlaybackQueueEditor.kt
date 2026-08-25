package com.vortex.player.playback

/** Resultado puro de editar la cola, separado del motor para poder probar los índices. */
data class QueueEditResult<T>(
    val entries: List<T>,
    val currentIndex: Int,
    val currentRemoved: Boolean = false
)

/**
 * Operaciones de cola que conservan la identidad del elemento que está sonando.
 *
 * Media3 trabaja con índices, pero al mover o borrar elementos esos índices cambian. Esta
 * clase concentra esa aritmética para que la UI y el servicio nunca terminen apuntando a
 * otra canción por accidente.
 */
object PlaybackQueueEditor {

    /**
     * Comprueba que una edición sólo movió el elemento activo y no lo sustituyó.
     * La identidad la aporta el motor para no acoplar este cálculo puro a Media3.
     */
    fun <T> preservesCurrent(
        entries: List<T>,
        currentIndex: Int,
        updatedEntries: List<T>,
        updatedCurrentIndex: Int,
        sameIdentity: (T, T) -> Boolean
    ): Boolean {
        val current = entries.getOrNull(currentIndex) ?: return false
        val updatedCurrent = updatedEntries.getOrNull(updatedCurrentIndex) ?: return false
        return sameIdentity(current, updatedCurrent)
    }

    fun <T> move(
        entries: List<T>,
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int
    ): QueueEditResult<T> {
        if (entries.isEmpty()) return QueueEditResult(emptyList(), 0)
        val current = currentIndex.coerceIn(entries.indices)
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) {
            return QueueEditResult(entries, current)
        }

        val updated = entries.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        val nextCurrent = when {
            fromIndex == current -> toIndex
            fromIndex < current && toIndex >= current -> current - 1
            fromIndex > current && toIndex <= current -> current + 1
            else -> current
        }
        return QueueEditResult(updated, nextCurrent)
    }

    fun <T> remove(
        entries: List<T>,
        currentIndex: Int,
        rawIndices: Set<Int>
    ): QueueEditResult<T> {
        if (entries.isEmpty()) return QueueEditResult(emptyList(), 0)
        val indices = rawIndices.filterTo(sortedSetOf()) { it in entries.indices }
        val current = currentIndex.coerceIn(entries.indices)
        if (indices.isEmpty()) return QueueEditResult(entries, current)

        val updated = entries.filterIndexed { index, _ -> index !in indices }
        if (updated.isEmpty()) return QueueEditResult(emptyList(), 0, currentRemoved = true)

        val removedBefore = indices.count { it < current }
        val wasCurrentRemoved = current in indices
        val nextCurrent = if (wasCurrentRemoved) {
            // Prefiere el elemento que estaba justo después; si no existe, usa el anterior.
            (current - removedBefore).coerceIn(updated.indices)
        } else {
            (current - removedBefore).coerceIn(updated.indices)
        }
        return QueueEditResult(updated, nextCurrent, wasCurrentRemoved)
    }
}
