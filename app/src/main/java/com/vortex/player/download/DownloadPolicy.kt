package com.vortex.player.download

import com.vortex.player.data.db.DownloadEntity

enum class DownloadSchedule(val label: String) {
    ANYTIME("CUALQUIER HORA"),
    NIGHT("22:00–07:00"),
    MIDNIGHT("00:00–07:00");

    fun allows(minuteOfDay: Int): Boolean = when (this) {
        ANYTIME -> true
        NIGHT -> minuteOfDay >= 22 * 60 || minuteOfDay < 7 * 60
        MIDNIGHT -> minuteOfDay < 7 * 60
    }
}

enum class DownloadSource {
    YOUTUBE,
    OTHER;

    companion object {
        fun from(job: DownloadEntity): DownloadSource {
            val target = (job.searchQuery ?: job.url).lowercase()
            return if (
                target.startsWith("ytsearch") ||
                "youtube.com" in target ||
                "youtu.be" in target
            ) YOUTUBE else OTHER
        }
    }
}

data class DownloadPolicy(
    val adaptiveConcurrency: Boolean = true,
    val youtubeLimit: Int = 2,
    val otherLimit: Int = 4,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val schedule: DownloadSchedule = DownloadSchedule.ANYTIME,
    /** Límite global aproximado. 0 significa sin límite. */
    val bandwidthLimitKbps: Int = 0,
    val maxAutomaticRetries: Int = 3
) {
    fun sourceLimit(source: DownloadSource): Int = DownloadConcurrency.clamp(
        when (source) {
            DownloadSource.YOUTUBE -> youtubeLimit
            DownloadSource.OTHER -> otherLimit
        }
    )
}

object DownloadRetryPolicy {
    private val delaysMs = longArrayOf(15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

    fun delayMs(attempt: Int): Long = delaysMs[(attempt - 1).coerceIn(delaysMs.indices)]

    fun isRetryable(message: String): Boolean {
        val value = message.lowercase()
        val permanent = listOf(
            "private video", "video unavailable", "not available in your country",
            "copyright", "members-only", "age-restricted", "unsupported url",
            "libera espacio", "sólo quedan", "margen de seguridad", "destino no",
            "confirm you're not a bot", "confirm you’re not a bot", "use --cookies",
            "modo automático sin cuenta", "known to use drm protection", "drm protected",
            "vuelve a conectar tu cuenta de youtube"
        )
        return permanent.none(value::contains)
    }

    fun isFormatFailure(message: String): Boolean {
        val value = message.lowercase()
        return "requested format is not available" in value ||
            "unable to merge" in value || "not compatible with" in value
    }
}

/** Conserva sólo las líneas que explican por qué no quedó un archivo reproducible. */
object DownloadDiagnostics {
    private val warningClues = listOf(
        "javascript", "challenge", "format", "ffmpeg", "postprocess",
        "thumbnail", "drm", "sign in", "requested", "unable to"
    )

    fun relevantLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()
        // Es una consecuencia del bloqueo, no su causa. Mostrarla primero ocultaba el
        // ERROR real por el límite de tres líneas de la tarjeta.
        if ("no title found in player responses" in lower) return null
        if (trimmed.startsWith("ERROR", ignoreCase = true)) return trimmed
        val usefulWarning = trimmed.startsWith("WARNING", ignoreCase = true) &&
            warningClues.any(lower::contains)
        val conversionFailure = "conversion failed" in lower
        return trimmed.takeIf { usefulWarning || conversionFailure }
    }

    /** El fallo terminal siempre tiene prioridad sobre advertencias de contexto. */
    fun finalMessage(lines: List<String>, fallback: String): String {
        val distinct = (lines + fallback).map(String::trim).filter(String::isNotBlank).distinct()
        val errors = distinct.filter { it.startsWith("ERROR", ignoreCase = true) }
        return (errors.ifEmpty { distinct }).joinToString("\n—\n").ifBlank { fallback }
    }
}

object DownloadEstimator {
    fun estimateBytes(job: DownloadEntity, durationMs: Long = job.targetDurationMs): Long {
        if (durationMs <= 0) return 0
        val bitsPerSecond = when (job.kind) {
            DownloadKind.AUDIO -> when (job.audioBitrate) {
                AudioBitrate.K128 -> 128_000L
                AudioBitrate.K192 -> 192_000L
                AudioBitrate.K256 -> 256_000L
                AudioBitrate.K320 -> 320_000L
                AudioBitrate.BEST -> 256_000L
            }
            DownloadKind.VIDEO -> when (job.videoQuality) {
                VideoQuality.UHD -> 25_000_000L
                VideoQuality.QHD -> 12_000_000L
                VideoQuality.FHD -> 8_000_000L
                VideoQuality.HD -> 5_000_000L
                VideoQuality.SD -> 2_500_000L
                VideoQuality.LOW -> 1_500_000L
                VideoQuality.BEST -> 12_000_000L
            }
        }
        // 12 % de margen para contenedor, audio y variación del bitrate.
        return ((durationMs / 1_000.0) * bitsPerSecond / 8.0 * 1.12).toLong()
    }
}
