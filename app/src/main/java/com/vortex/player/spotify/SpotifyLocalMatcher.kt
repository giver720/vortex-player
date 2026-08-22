package com.vortex.player.spotify

import com.vortex.player.data.db.SpotifyTrackEntity
import java.text.Normalizer
import kotlin.math.abs

data class LocalAudioCandidate(
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long
)

enum class LocalMatchQuality(val label: String) {
    EXACT("EN EL MÓVIL"),
    LIKELY("COINCIDENCIA PROBABLE")
}

data class SpotifyLocalMatch(
    val local: LocalAudioCandidate,
    val quality: LocalMatchQuality,
    val confidence: Float
)

/** Coincidencia conservadora: ante duda no afirma que una canción esté en el dispositivo. */
object SpotifyLocalMatcher {
    fun best(
        track: SpotifyTrackEntity,
        candidates: List<LocalAudioCandidate>
    ): SpotifyLocalMatch? {
        val ranked = candidates.asSequence().mapNotNull { candidate ->
            score(track, candidate)?.let { candidate to it }
        }.sortedByDescending { it.second }.take(2).toList()
        val best = ranked.firstOrNull() ?: return null
        if (best.second < MINIMUM_SCORE) return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        // Dos archivos casi iguales (versiones live/remaster) no se deciden automáticamente.
        if (second >= MINIMUM_SCORE && best.second - second < AMBIGUITY_MARGIN) return null
        return SpotifyLocalMatch(
            local = best.first,
            quality = if (best.second >= EXACT_SCORE) {
                LocalMatchQuality.EXACT
            } else {
                LocalMatchQuality.LIKELY
            },
            confidence = best.second
        )
    }

    private fun score(track: SpotifyTrackEntity, local: LocalAudioCandidate): Float? {
        val title = similarity(track.title, local.title)
        if (title < 0.62f) return null
        val artist = similarity(track.artist, local.artist.orEmpty())
        val album = similarity(track.album, local.album.orEmpty())
        val durationDifference = if (track.durationMs > 0 && local.durationMs > 0) {
            abs(track.durationMs - local.durationMs)
        } else {
            Long.MAX_VALUE
        }
        val duration = when {
            durationDifference <= 2_000 -> 1f
            durationDifference <= 5_000 -> 0.8f
            durationDifference <= 10_000 -> 0.35f
            durationDifference == Long.MAX_VALUE -> 0f
            else -> 0f
        }
        if (artist < 0.42f && duration < 0.8f) return null
        return (title * 0.55f + artist * 0.25f + album * 0.05f + duration * 0.15f)
            .coerceIn(0f, 1f)
    }

    internal fun similarity(left: String, right: String): Float {
        val a = tokens(left)
        val b = tokens(right)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val intersection = a.intersect(b).size.toFloat()
        return intersection / a.union(b).size.toFloat()
    }

    private fun tokens(value: String): Set<String> = Normalizer.normalize(
        value.lowercase(),
        Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.length > 1 && it !in NOISE }
        .toSet()

    private val NOISE = setOf(
        "the", "feat", "featuring", "ft", "official", "audio", "video", "remastered"
    )
    private const val MINIMUM_SCORE = 0.76f
    private const val EXACT_SCORE = 0.92f
    private const val AMBIGUITY_MARGIN = 0.06f
}
