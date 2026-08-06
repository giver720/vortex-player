package com.vortex.player.data

import android.net.Uri

/**
 * Una pista de la biblioteca. Vídeo y audio comparten modelo a propósito: en Vórtex
 * un MP4 puede vivir en la cola junto a un MP3 y sonar igual, así que distinguirlos
 * a nivel de tipo sólo estorbaría.
 */
data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val folderPath: String,
    val folderName: String,
    val dateAddedSec: Long,
    val isVideo: Boolean,
    val artist: String? = null,
    val album: String? = null
) {
    val hasVideoTrack: Boolean get() = isVideo && width > 0 && height > 0

    /** Etiqueta corta de resolución para el HUD: 4K, 1080p, 720p… */
    val resolutionLabel: String?
        get() {
            if (!isVideo) return null
            val shortSide = minOf(width, height).takeIf { it > 0 } ?: return null
            return when {
                shortSide >= 2160 -> "4K"
                shortSide >= 1440 -> "1440p"
                shortSide >= 1080 -> "1080p"
                shortSide >= 720 -> "720p"
                shortSide >= 480 -> "480p"
                else -> "${shortSide}p"
            }
        }
}

/** Agrupación por carpeta del sistema de archivos, que es como la gente recuerda dónde dejó las cosas. */
data class MediaFolder(
    val path: String,
    val name: String,
    val entries: List<MediaEntry>
) {
    val totalDurationMs: Long get() = entries.sumOf { it.durationMs }
}
