package com.vortex.player.cast

import java.net.URI

enum class CastDelivery { DIRECT_HTTP, LOCAL_BRIDGE, UNSUPPORTED }
enum class CastStreamKind { BUFFERED, LIVE }

data class CastMediaPlan(
    val delivery: CastDelivery,
    val contentType: String,
    val streamKind: CastStreamKind,
    val reason: String? = null
)

/** Decisiones puras del emisor para que compatibilidad y errores puedan probarse en JVM. */
object CastMediaPolicy {
    fun plan(
        uri: String,
        mimeType: String?,
        isVideo: Boolean,
        durationMs: Long
    ): CastMediaPlan {
        val parsed = runCatching { URI(uri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val delivery = when (scheme) {
            "http", "https" -> CastDelivery.DIRECT_HTTP
            "content", "file" -> CastDelivery.LOCAL_BRIDGE
            else -> CastDelivery.UNSUPPORTED
        }
        val contentType = concreteMime(mimeType, parsed?.path, isVideo)
        val hls = contentType.contains("mpegurl", ignoreCase = true) ||
            parsed?.path.orEmpty().lowercase().endsWith(".m3u8")
        return CastMediaPlan(
            delivery = delivery,
            contentType = contentType,
            streamKind = if (hls && durationMs <= 0L) CastStreamKind.LIVE else CastStreamKind.BUFFERED,
            reason = when (delivery) {
                CastDelivery.UNSUPPORTED ->
                    "El receptor Cast necesita HTTP, HTTPS o un archivo local compartible; " +
                        "${scheme.ifBlank { "esta fuente" }.uppercase()} no es compatible."
                else -> null
            }
        )
    }

    private fun concreteMime(mimeType: String?, path: String?, isVideo: Boolean): String {
        val declared = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        if (declared.isNotBlank() && !declared.endsWith("/*")) return declared
        return when (path.orEmpty().substringAfterLast('.', "").lowercase()) {
            "m3u8" -> "application/x-mpegurl"
            "mp4", "m4v" -> if (isVideo) "video/mp4" else "audio/mp4"
            "webm" -> if (isVideo) "video/webm" else "audio/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            else -> if (isVideo) "video/mp4" else "audio/mpeg"
        }
    }
}

data class HttpByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

sealed interface HttpRangeResult {
    data object Full : HttpRangeResult
    data class Partial(val range: HttpByteRange) : HttpRangeResult
    data object Invalid : HttpRangeResult
}

/** Parser estricto de un único rango HTTP, suficiente para seeks de receptores multimedia. */
object HttpRangePolicy {
    fun parse(header: String?, totalLength: Long): HttpRangeResult {
        if (header.isNullOrBlank()) return HttpRangeResult.Full
        if (totalLength <= 0L || !header.startsWith("bytes=", ignoreCase = true)) {
            return HttpRangeResult.Invalid
        }
        val value = header.substringAfter('=').trim()
        if (',' in value) return HttpRangeResult.Invalid
        val startText = value.substringBefore('-', missingDelimiterValue = "")
        val endText = value.substringAfter('-', missingDelimiterValue = "")
        if (startText.isBlank()) {
            val suffix = endText.toLongOrNull()?.takeIf { it > 0L } ?: return HttpRangeResult.Invalid
            val length = suffix.coerceAtMost(totalLength)
            return HttpRangeResult.Partial(HttpByteRange(totalLength - length, totalLength - 1))
        }
        val start = startText.toLongOrNull()?.takeIf { it >= 0L } ?: return HttpRangeResult.Invalid
        if (start >= totalLength) return HttpRangeResult.Invalid
        val end = if (endText.isBlank()) {
            totalLength - 1
        } else {
            endText.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: return HttpRangeResult.Invalid
        }
        if (end < start) return HttpRangeResult.Invalid
        return HttpRangeResult.Partial(HttpByteRange(start, end))
    }
}
