package com.vortex.player.data

import android.net.Uri
import com.vortex.player.data.db.PlaylistItemDraft
import com.vortex.player.data.db.PlaylistItemEntity
import java.net.URI

enum class PlaylistSortMode { TITLE, ARTIST, ALBUM, DURATION, TYPE }

enum class SmartPlaylistRule(val label: String, val description: String) {
    RECENT("Añadidos recientemente", "Medios añadidos durante los últimos 30 días"),
    UNPLAYED("Nunca reproducidos", "Medios que todavía no se han iniciado"),
    CONTINUE("En progreso", "Medios empezados y todavía no terminados"),
    AUDIO("Todo el audio", "Todos los archivos de audio de la biblioteca"),
    VIDEO("Todos los vídeos", "Todos los vídeos de la biblioteca"),
    LONG_FORM("Contenido largo", "Medios con una duración mínima de 45 minutos");

    fun select(
        library: LibraryState,
        nowSeconds: Long = System.currentTimeMillis() / 1_000L
    ): List<MediaEntry> = when (this) {
        RECENT -> library.entries
            .filter { it.dateAddedSec >= nowSeconds - 30L * 24 * 60 * 60 }
            .sortedByDescending { it.dateAddedSec }
        UNPLAYED -> library.entries.filter { entry ->
            val state = library.stateFor(entry)
            state == null || state.positionMs < 10_000L
        }
        CONTINUE -> library.continueWatching.map { it.first }
        AUDIO -> library.audios
        VIDEO -> library.videos
        LONG_FORM -> library.entries.filter { it.durationMs >= 45L * 60 * 1_000 }
    }

    companion object {
        fun parse(value: String?): SmartPlaylistRule? =
            value?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

data class PlaylistResolvedItem(
    val stored: PlaylistItemEntity,
    val media: MediaEntry?
) {
    val available: Boolean get() = media != null
    val title: String get() = media?.title?.ifBlank { media.displayName } ?: stored.title
    val artist: String? get() = media?.artist ?: stored.artist
    val album: String? get() = media?.album ?: stored.album
    val durationMs: Long get() = media?.durationMs?.takeIf { it > 0L } ?: stored.durationMs
    val isVideo: Boolean get() = media?.isVideo ?: stored.isVideo
}

data class PlaylistStats(
    val count: Int,
    val available: Int,
    val missing: Int,
    val durationMs: Long,
    val videos: Int,
    val audios: Int
)

object PlaylistOrganizer {
    fun resolve(
        playlist: PlaylistWithItems,
        library: LibraryState
    ): List<PlaylistResolvedItem> {
        val smart = SmartPlaylistRule.parse(playlist.playlist.smartRule)
        if (smart == null) {
            val allowRemote = playlist.playlist.source != "SPOTIFY"
            return playlist.items.sortedBy { it.position }.map { stored ->
                PlaylistResolvedItem(
                    stored,
                    library.entryFor(stored.uri) ?: stored.remoteEntry().takeIf { allowRemote }
                )
            }
        }
        return smart.select(library).mapIndexed { index, entry ->
            PlaylistResolvedItem(
                stored = PlaylistItemEntity(
                    id = smartItemId(entry.uri.toString(), index),
                    playlistId = playlist.playlist.id,
                    uri = entry.uri.toString(),
                    title = entry.title.ifBlank { entry.displayName },
                    artist = entry.artist,
                    album = entry.album,
                    durationMs = entry.durationMs,
                    isVideo = entry.isVideo,
                    position = index
                ),
                media = entry
            )
        }
    }

    fun resolve(items: List<PlaylistItemEntity>, library: LibraryState): List<PlaylistResolvedItem> =
        items.sortedBy { it.position }.map { PlaylistResolvedItem(it, library.entryFor(it.uri)) }

    fun stats(items: List<PlaylistResolvedItem>): PlaylistStats = PlaylistStats(
        count = items.size,
        available = items.count { it.available },
        missing = items.count { !it.available },
        durationMs = items.sumOf { it.durationMs.coerceAtLeast(0L) },
        videos = items.count { it.isVideo },
        audios = items.count { !it.isVideo }
    )

    fun sortedIds(items: List<PlaylistResolvedItem>, mode: PlaylistSortMode): List<Long> {
        val sorted = when (mode) {
            PlaylistSortMode.TITLE -> items.sortedBy { it.title.lowercase() }
            PlaylistSortMode.ARTIST -> items.sortedWith(
                compareBy<PlaylistResolvedItem> { it.artist.orEmpty().lowercase() }
                    .thenBy { it.title.lowercase() }
            )
            PlaylistSortMode.ALBUM -> items.sortedWith(
                compareBy<PlaylistResolvedItem> { it.album.orEmpty().lowercase() }
                    .thenBy { it.title.lowercase() }
            )
            PlaylistSortMode.DURATION -> items.sortedBy { it.durationMs }
            PlaylistSortMode.TYPE -> items.sortedWith(
                compareBy<PlaylistResolvedItem> { it.isVideo }.thenBy { it.title.lowercase() }
            )
        }
        return sorted.map { it.stored.id }
    }

    fun move(ids: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun smartItemId(uri: String, index: Int): Long {
        val raw = (uri.hashCode().toLong() shl 32) xor index.toLong()
        val absolute = if (raw == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(raw)
        return -absolute - 1L
    }

    private fun PlaylistItemEntity.remoteEntry(): MediaEntry? {
        val scheme = runCatching { URI(uri).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return null
        val extension = uri.substringBefore('?').substringAfterLast('.', "").lowercase()
        val mime = when (extension) {
            "m3u8" -> "application/x-mpegurl"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> if (isVideo) "video/webm" else "audio/webm"
            else -> if (isVideo) "video/mp4" else "audio/mpeg"
        }
        return MediaEntry(
            id = id,
            uri = Uri.parse(uri),
            title = title,
            displayName = title,
            durationMs = durationMs,
            sizeBytes = 0L,
            mimeType = mime,
            width = 0,
            height = 0,
            folderPath = uri.substringBeforeLast('/', ""),
            folderName = "M3U remoto",
            dateAddedSec = 0L,
            isVideo = isVideo,
            artist = artist,
            album = album,
            persistable = false
        )
    }
}

data class M3uEntry(val uri: String, val title: String? = null, val durationSeconds: Long? = null)
data class M3uDocument(val name: String? = null, val entries: List<M3uEntry>)

/** Códec pequeño y tolerante para importar/exportar M3U y M3U8 sin dependencias externas. */
object M3uCodec {
    fun decode(text: String): M3uDocument {
        var playlistName: String? = null
        var pendingTitle: String? = null
        var pendingDuration: Long? = null
        val entries = mutableListOf<M3uEntry>()
        text.lineSequence().take(MAX_LINES).forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                    playlistName = line.substringAfter(':').trim().takeIf(String::isNotBlank)
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(':')
                    pendingDuration = info.substringBefore(',').trim().toLongOrNull()?.takeIf { it >= 0L }
                    pendingTitle = info.substringAfter(',', "").trim().takeIf(String::isNotBlank)
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    entries += M3uEntry(line, pendingTitle, pendingDuration)
                    pendingTitle = null
                    pendingDuration = null
                }
            }
        }
        return M3uDocument(playlistName, entries.distinctBy { it.uri })
    }

    fun encode(name: String, items: List<PlaylistResolvedItem>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:${sanitize(name)}")
        items.forEach { item ->
            val seconds = (item.durationMs / 1_000L).coerceAtLeast(0L)
            val label = listOfNotNull(item.artist?.takeIf(String::isNotBlank), item.title)
                .joinToString(" - ")
            appendLine("#EXTINF:$seconds,${sanitize(label)}")
            appendLine(item.stored.uri)
        }
    }

    fun toDrafts(document: M3uDocument, library: LibraryState): List<PlaylistItemDraft> {
        val byPath = library.entries.mapNotNull { entry ->
            normalizedPath(entry.uri.toString())?.let { it to entry }
        }.toMap()
        val byName = library.entries.groupBy { it.displayName.lowercase() }
        return document.entries.map { imported ->
            val exact = library.entryFor(imported.uri)
            val path = normalizedPath(imported.uri)?.let(byPath::get)
            val fileName = imported.uri.substringAfterLast('/').substringBefore('?').lowercase()
            val named = byName[fileName]?.singleOrNull()
            val local = exact ?: path ?: named
            local?.toPlaylistDraft() ?: PlaylistItemDraft(
                uri = imported.uri,
                title = imported.title?.substringAfterLast(" - ")
                    ?.takeIf(String::isNotBlank)
                    ?: fileName.ifBlank { imported.uri },
                artist = imported.title?.substringBefore(" - ", "")?.takeIf(String::isNotBlank),
                durationMs = imported.durationSeconds?.times(1_000L) ?: 0L,
                isVideo = imported.uri.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS
            )
        }
    }

    private fun normalizedPath(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme.equals("file", true)) uri.path?.replace('\\', '/')?.lowercase() else null
    }.getOrNull()

    private fun sanitize(value: String): String = value.replace('\r', ' ').replace('\n', ' ').trim()

    private const val MAX_LINES = 100_000
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
}
