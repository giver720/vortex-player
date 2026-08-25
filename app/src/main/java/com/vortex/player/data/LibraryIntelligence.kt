package com.vortex.player.data

import java.util.Locale

enum class ResolutionFilter(val label: String) {
    ANY("RESOLUCIÓN"),
    SD("SD"),
    HD("720p"),
    FULL_HD("1080p"),
    QHD("1440p"),
    UHD("4K+")
}

enum class DurationFilter(val label: String) {
    ANY("DURACIÓN"),
    SHORT("< 10 MIN"),
    MEDIUM("10–60 MIN"),
    LONG("> 60 MIN")
}

enum class SizeFilter(val label: String) {
    ANY("TAMAÑO"),
    SMALL("< 100 MB"),
    MEDIUM("100 MB–1 GB"),
    LARGE("> 1 GB")
}

enum class ContainerFilter(val label: String) {
    ANY("FORMATO"),
    MP4("MP4"),
    MKV("MKV"),
    WEBM("WEBM"),
    MP3("MP3"),
    FLAC("FLAC"),
    OTHER("OTROS")
}

data class LibraryFilter(
    val resolution: ResolutionFilter = ResolutionFilter.ANY,
    val duration: DurationFilter = DurationFilter.ANY,
    val size: SizeFilter = SizeFilter.ANY,
    val container: ContainerFilter = ContainerFilter.ANY
) {
    val isActive: Boolean
        get() = resolution != ResolutionFilter.ANY ||
            duration != DurationFilter.ANY ||
            size != SizeFilter.ANY ||
            container != ContainerFilter.ANY
}

data class DuplicateGroup(
    val key: String,
    val entries: List<MediaEntry>
) {
    /** Conserva el más reciente y propone revisar el resto; nunca borra automáticamente. */
    val keeper: MediaEntry get() = entries.maxBy { it.dateModifiedSec }
    val copiesToReview: List<MediaEntry> get() = entries.filterNot { it.uri == keeper.uri }
    val reclaimableBytes: Long get() = copiesToReview.sumOf { it.sizeBytes }
}

data class SeriesEpisode(
    val entry: MediaEntry,
    val season: Int,
    val episode: Int
)

internal data class ParsedEpisodeLabel(
    val title: String,
    val season: Int,
    val episode: Int
)

data class SmartSeries(
    val title: String,
    val episodes: List<SeriesEpisode>
) {
    val seasonCount: Int get() = episodes.map { it.season }.distinct().size
}

data class LibraryIntelligence(
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val series: List<SmartSeries> = emptyList(),
    val potentialReclaimableBytes: Long = 0L
)

object LibraryIntelligenceEngine {
    private val sxe = Regex("(?i)^(.+?)[ ._\\-]+s(\\d{1,2})e(\\d{1,3})(?:e\\d{1,3})?.*$")
    private val x = Regex("(?i)^(.+?)[ ._\\-]+(\\d{1,2})x(\\d{1,3}).*$")
    private val words = Regex(
        "(?i)^(.+?)[ ._\\-]+(?:season|temporada)[ ._\\-]*(\\d{1,2})" +
            "[ ._\\-]+(?:episode|episodio|ep)[ ._\\-]*(\\d{1,3}).*$"
    )

    fun analyze(entries: List<MediaEntry>): LibraryIntelligence {
        val duplicateGroups = entries
            .asSequence()
            .filter { it.sizeBytes > 0L && it.durationMs > 0L }
            .groupBy(::duplicateKey)
            .filterValues { it.size > 1 }
            .map { (key, matches) ->
                DuplicateGroup(key, matches.sortedByDescending { it.dateModifiedSec })
            }
            .sortedByDescending { it.reclaimableBytes }

        val series = entries
            .asSequence()
            .filter(MediaEntry::isVideo)
            .mapNotNull(::parseEpisode)
            .groupBy { normalizeSeriesKey(it.first) }
            .map { (_, matches) ->
                SmartSeries(
                    title = matches.first().first,
                    episodes = matches.map { it.second }
                        .sortedWith(compareBy(SeriesEpisode::season, SeriesEpisode::episode))
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        return LibraryIntelligence(
            duplicateGroups = duplicateGroups,
            series = series,
            potentialReclaimableBytes = duplicateGroups.sumOf { it.reclaimableBytes }
        )
    }

    fun filter(entries: List<MediaEntry>, filter: LibraryFilter): List<MediaEntry> = entries.filter { entry ->
        matchesResolution(entry, filter.resolution) &&
            matchesDuration(entry, filter.duration) &&
            matchesSize(entry, filter.size) &&
            matchesContainer(entry, filter.container)
    }

    internal fun parseEpisode(entry: MediaEntry): Pair<String, SeriesEpisode>? {
        val source = entry.displayName.substringBeforeLast('.').ifBlank { entry.title }
        val parsed = parseEpisodeLabel(source) ?: return null
        return parsed.title to SeriesEpisode(entry, parsed.season, parsed.episode)
    }

    internal fun parseEpisodeLabel(source: String): ParsedEpisodeLabel? {
        val match = sxe.matchEntire(source) ?: x.matchEntire(source) ?: words.matchEntire(source)
            ?: return null
        val title = cleanTitle(match.groupValues[1])
        val season = match.groupValues[2].toIntOrNull() ?: return null
        val episode = match.groupValues[3].toIntOrNull() ?: return null
        if (title.isBlank() || season <= 0 || episode <= 0) return null
        return ParsedEpisodeLabel(title, season, episode)
    }

    private fun duplicateKey(entry: MediaEntry): String =
        duplicateKey(entry.isVideo, entry.sizeBytes, entry.durationMs)

    internal fun duplicateKey(isVideo: Boolean, sizeBytes: Long, durationMs: Long): String = buildString {
        append(if (isVideo) 'v' else 'a')
        append('|').append(sizeBytes)
        // MediaStore puede redondear la duración unos milisegundos según el contenedor.
        append('|').append((durationMs + 500L) / 1_000L)
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("[._]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '_', '.')

    private fun normalizeSeriesKey(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun matchesResolution(entry: MediaEntry, filter: ResolutionFilter): Boolean {
        return matchesResolution(entry.isVideo, entry.width, entry.height, filter)
    }

    internal fun matchesResolution(
        isVideo: Boolean,
        width: Int,
        height: Int,
        filter: ResolutionFilter
    ): Boolean {
        if (filter == ResolutionFilter.ANY) return true
        if (!isVideo) return false
        val shortSide = minOf(width, height)
        return when (filter) {
            ResolutionFilter.ANY -> true
            ResolutionFilter.SD -> shortSide in 1..719
            ResolutionFilter.HD -> shortSide in 720..1079
            ResolutionFilter.FULL_HD -> shortSide in 1080..1439
            ResolutionFilter.QHD -> shortSide in 1440..2159
            ResolutionFilter.UHD -> shortSide >= 2160
        }
    }

    private fun matchesDuration(entry: MediaEntry, filter: DurationFilter): Boolean =
        matchesDuration(entry.durationMs, filter)

    internal fun matchesDuration(durationMs: Long, filter: DurationFilter): Boolean = when (filter) {
        DurationFilter.ANY -> true
        DurationFilter.SHORT -> durationMs < 10 * 60_000L
        DurationFilter.MEDIUM -> durationMs in 10 * 60_000L..60 * 60_000L
        DurationFilter.LONG -> durationMs > 60 * 60_000L
    }

    private fun matchesSize(entry: MediaEntry, filter: SizeFilter): Boolean =
        matchesSize(entry.sizeBytes, filter)

    internal fun matchesSize(sizeBytes: Long, filter: SizeFilter): Boolean = when (filter) {
        SizeFilter.ANY -> true
        SizeFilter.SMALL -> sizeBytes < 100L * MB
        SizeFilter.MEDIUM -> sizeBytes in 100L * MB..GB
        SizeFilter.LARGE -> sizeBytes > GB
    }

    private fun matchesContainer(entry: MediaEntry, filter: ContainerFilter): Boolean {
        if (filter == ContainerFilter.ANY) return true
        return containerFor(entry.displayName) == filter
    }

    internal fun containerFor(displayName: String): ContainerFilter {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "mp4", "m4v", "m4a" -> ContainerFilter.MP4
            "mkv" -> ContainerFilter.MKV
            "webm" -> ContainerFilter.WEBM
            "mp3" -> ContainerFilter.MP3
            "flac" -> ContainerFilter.FLAC
            else -> ContainerFilter.OTHER
        }
    }

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB
}
