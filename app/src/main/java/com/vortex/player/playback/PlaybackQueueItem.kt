package com.vortex.player.playback

import com.vortex.player.data.LibraryState
import com.vortex.player.data.MediaEntry
import java.util.UUID

/** Procedencia visible de una instancia concreta dentro de la fila. */
enum class QueueOrigin { MANUAL, AUTOPLAY }

/**
 * Una entrada de la fila. [queueId] identifica la instancia, no el archivo: así el mismo
 * medio puede aparecer dos veces y seguir siendo posible mover o eliminar sólo una copia.
 */
data class PlaybackQueueItem(
    val queueId: String,
    val media: MediaEntry,
    val origin: QueueOrigin = QueueOrigin.MANUAL,
    val addedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun manual(media: MediaEntry): PlaybackQueueItem = create(media, QueueOrigin.MANUAL)
        fun autoplay(media: MediaEntry): PlaybackQueueItem = create(media, QueueOrigin.AUTOPLAY)

        private fun create(media: MediaEntry, origin: QueueOrigin) = PlaybackQueueItem(
            queueId = UUID.randomUUID().toString(),
            media = media,
            origin = origin
        )
    }
}

enum class QueueSortMode { TITLE, ARTIST, ALBUM, DURATION, RECENTLY_ADDED }

data class QueueDuplicateSplit(
    val newEntries: List<MediaEntry>,
    val duplicates: List<MediaEntry>
)

fun splitQueueDuplicates(
    entries: List<MediaEntry>,
    queued: List<PlaybackQueueItem>
): QueueDuplicateSplit {
    val queuedUris = queued.mapTo(hashSetOf()) { it.media.uri.toString() }
    val newEntries = ArrayList<MediaEntry>(entries.size)
    val duplicates = ArrayList<MediaEntry>()
    entries.forEach { entry ->
        if (entry.uri.toString() in queuedUris) duplicates += entry else newEntries += entry
    }
    return QueueDuplicateSplit(newEntries, duplicates)
}

/** Recomendador deliberadamente local y determinista; nunca consulta ni envía datos. */
object QueueAutoplayEngine {
    fun recommend(
        library: LibraryState,
        queue: List<PlaybackQueueItem>,
        currentIndex: Int,
        limit: Int = 5,
        nowMs: Long = System.currentTimeMillis()
    ): List<MediaEntry> {
        if (limit <= 0) return emptyList()
        val current = queue.getOrNull(currentIndex)?.media
        val excluded = queue.mapTo(hashSetOf()) { it.media.uri.toString() }
        val recentWindow = nowMs - RECENT_WINDOW_MS

        return library.entries.asSequence()
            .filter { it.uri.toString() !in excluded }
            .map { candidate ->
                val state = library.stateFor(candidate)
                val score = buildScore(current, candidate) + when {
                    state == null || state.playCount == 0 -> 22
                    state.lastPlayedAt < recentWindow -> 8
                    else -> 0
                } + if (state?.isFavorite == true) 18 else 0
                candidate to score
            }
            .sortedWith(
                compareByDescending<Pair<MediaEntry, Int>> { it.second }
                    .thenByDescending { it.first.dateAddedSec }
                    .thenBy { it.first.title.lowercase() }
                    .thenBy { it.first.uri.toString() }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun buildScore(current: MediaEntry?, candidate: MediaEntry): Int {
        if (current == null) return if (candidate.isVideo) 0 else 2
        var score = if (candidate.isVideo == current.isVideo) 12 else 0
        if (sameText(candidate.artist, current.artist)) score += 50
        if (sameText(candidate.album, current.album)) score += 35
        if (candidate.folderPath.isNotBlank() && candidate.folderPath == current.folderPath) score += 25
        return score
    }

    private fun sameText(first: String?, second: String?): Boolean =
        !first.isNullOrBlank() && !second.isNullOrBlank() && first.equals(second, ignoreCase = true)

    private const val RECENT_WINDOW_MS = 12 * 60 * 60 * 1_000L
}
