package com.vortex.player.subtitle

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SubtitleDocument(
    val displayName: String,
    /** URI local que libVLC puede abrir sin depender de un proveedor `content://`. */
    val localUri: Uri,
    /** Vacío para formatos que VLC admite pero el segundo overlay no interpreta. */
    val cues: List<SubtitleCue>
)

enum class SubtitleTextSize(val label: String, val scale: Float) {
    SMALL("PEQUEÑO", 0.85f),
    MEDIUM("MEDIO", 1f),
    LARGE("GRANDE", 1.25f)
}

object SubtitleTimeline {
    fun activeText(cues: List<SubtitleCue>, positionMs: Long): String? = cues
        .asSequence()
        .filter { positionMs >= it.startMs && positionMs < it.endMs }
        .map { it.text }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { null }
}

/** Parser tolerante para SRT y WebVTT; las etiquetas de estilo se convierten a texto plano. */
object SubtitleParser {
    private val timing = Regex(
        """^\s*((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*""" +
            """((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3})(?:\s+.*)?$"""
    )
    private val tags = Regex("<[^>]+>")

    fun parse(source: String): List<SubtitleCue> {
        val normalized = source
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized.lines()
        val result = mutableListOf<SubtitleCue>()
        var index = 0

        while (index < lines.size) {
            val direct = timing.matchEntire(lines[index])
            val afterIdentifier = lines.getOrNull(index + 1)?.let(timing::matchEntire)
            val match = direct ?: afterIdentifier
            if (match == null) {
                index++
                continue
            }

            index += if (direct != null) 1 else 2
            val body = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                body += lines[index]
                index++
            }

            val start = parseTimestamp(match.groupValues[1]) ?: continue
            val end = parseTimestamp(match.groupValues[2]) ?: continue
            val text = clean(body.joinToString("\n"))
            if (end > start && text.isNotBlank()) result += SubtitleCue(start, end, text)
        }
        return result.sortedBy(SubtitleCue::startMs)
    }

    internal fun parseTimestamp(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secondsParts = parts.last().split('.')
        val seconds = secondsParts.getOrNull(0)?.toLongOrNull() ?: return null
        val millis = secondsParts.getOrNull(1)
            ?.take(3)
            ?.padEnd(3, '0')
            ?.toLongOrNull()
            ?: 0L
        if (minutes !in 0..59 || seconds !in 0..59 || millis !in 0..999) return null
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun clean(value: String): String = value
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(tags, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()
}

/** Copia el documento elegido a caché para que VLC pueda abrirlo incluso si es `content://`. */
object SubtitleDocumentLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024

    suspend fun load(context: Context, uri: Uri): SubtitleDocument = withContext(Dispatchers.IO) {
        val displayName = queryName(context, uri) ?: "subtitulo.srt"
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readLimited)
            ?: throw IllegalArgumentException("No se pudo abrir el subtítulo")
        createCachedDocument(context, displayName, bytes)
    }

    suspend fun fromBytes(
        context: Context,
        displayName: String,
        bytes: ByteArray
    ): SubtitleDocument = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_BYTES) { "El subtítulo supera 8 MB" }
        createCachedDocument(context, displayName, bytes)
    }

    private fun createCachedDocument(
        context: Context,
        displayName: String,
        bytes: ByteArray
    ): SubtitleDocument {
        val extension = displayName.substringAfterLast('.', "srt")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            ?: "srt"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(12)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val local = File(directory, "$digest.$extension")
        if (!local.exists() || local.length() != bytes.size.toLong()) local.writeBytes(bytes)

        return SubtitleDocument(
            displayName = displayName,
            localUri = Uri.fromFile(local),
            cues = if (extension == "srt" || extension == "vtt") {
                SubtitleParser.parse(decode(bytes))
            } else {
                emptyList()
            }
        )
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES) { "El subtítulo supera 8 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
}
