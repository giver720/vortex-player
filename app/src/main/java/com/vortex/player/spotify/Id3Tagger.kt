package com.vortex.player.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class TrackTags(
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int,
    val totalTracks: Int
)

/**
 * Escritor de etiquetas ID3v2.3 para los MP3 que deja yt-dlp.
 *
 * Hace falta porque el audio se baja de YouTube y hereda su título ("… (Video Oficial)
 * 4K"), su "artista" y su miniatura recortada. Aquí se sustituye todo eso por los datos
 * reales del catálogo de Spotify, que es lo que separa una biblioteca presentable de un
 * montón de ficheros con nombres de vídeo.
 *
 * Se implementa a mano en vez de traer una librería de etiquetado porque sólo se
 * necesitan cinco marcos y añadir una dependencia con binarios propios a un APK que ya
 * pesa 78 MB no compensa.
 */
object Id3Tagger {

    private const val TAG = "Id3Tagger"

    /** Sólo se toca MP3; en otros contenedores se deja lo que haya puesto yt-dlp. */
    fun canTag(file: File): Boolean = file.extension.equals("mp3", ignoreCase = true)

    suspend fun downloadCover(url: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Vortex-Player")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Reescribe la etiqueta del fichero. Devuelve `true` si se aplicó.
     *
     * La etiqueta que ya trae el fichero se descarta entera: mezclar marcos viejos de
     * YouTube con los nuevos deja reproductores mostrando el título antiguo.
     */
    suspend fun apply(file: File, tags: TrackTags, cover: ByteArray?): Boolean =
        withContext(Dispatchers.IO) {
            if (!canTag(file) || !file.isFile) return@withContext false
            runCatching {
                val audioOffset = existingTagLength(file)
                val tagBytes = buildTag(tags, cover)

                val temp = File(file.parentFile, file.name + ".tagging")
                temp.outputStream().use { output ->
                    output.write(tagBytes)
                    file.inputStream().use { input ->
                        input.skip(audioOffset)
                        input.copyTo(output, 128 * 1024)
                    }
                }
                if (!file.delete() || !temp.renameTo(file)) {
                    temp.delete()
                    return@runCatching false
                }
                true
            }.getOrElse {
                Log.w(TAG, "No se pudieron escribir las etiquetas de ${file.name}", it)
                false
            }
        }

    /** Longitud de la etiqueta ID3v2 existente, o 0 si el fichero empieza por audio. */
    private fun existingTagLength(file: File): Long {
        file.inputStream().use { input ->
            val header = ByteArray(10)
            if (input.read(header) != 10) return 0
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return 0
            }
            val size = syncSafeToInt(header, 6)
            // El bit 0x10 de los flags indica que además hay un pie de 10 bytes.
            val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
            return (10 + size + footer).toLong()
        }
    }

    private fun buildTag(tags: TrackTags, cover: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()

        textFrame("TIT2", tags.title)?.let(frames::write)
        textFrame("TPE1", tags.artist)?.let(frames::write)
        textFrame("TALB", tags.album)?.let(frames::write)
        if (tags.trackNumber > 0) {
            val value = if (tags.totalTracks > 0) {
                "${tags.trackNumber}/${tags.totalTracks}"
            } else {
                tags.trackNumber.toString()
            }
            textFrame("TRCK", value)?.let(frames::write)
        }
        cover?.takeIf { it.isNotEmpty() }?.let { frames.write(pictureFrame(it)) }

        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0))          // versión 2.3.0
        out.write(0)                           // sin flags
        out.write(intToSyncSafe(body.size))    // el tamaño del encabezado sí es syncsafe
        out.write(body)
        return out.toByteArray()
    }

    /**
     * Marco de texto en UTF-16 con BOM (codificación 0x01). Se evita ISO-8859-1 porque
     * destrozaría cualquier título con tildes, japonés o emoji.
     */
    private fun textFrame(id: String, value: String): ByteArray? {
        if (value.isBlank()) return null
        val content = ByteArrayOutputStream().apply {
            write(0x01)
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write(value.toByteArray(Charsets.UTF_16LE))
            write(byteArrayOf(0, 0))
        }.toByteArray()
        return frame(id, content)
    }

    private fun pictureFrame(image: ByteArray): ByteArray {
        val content = ByteArrayOutputStream().apply {
            write(0x00)                                            // descripción en Latin-1
            write(mimeTypeOf(image).toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(0x03)                                            // portada frontal
            write(0)                                               // descripción vacía
            write(image)
        }.toByteArray()
        return frame("APIC", content)
    }

    private fun mimeTypeOf(image: ByteArray): String =
        if (image.size > 8 &&
            image[0] == 0x89.toByte() &&
            image[1] == 'P'.code.toByte()
        ) {
            "image/png"
        } else {
            "image/jpeg"
        }

    /** Cabecera de marco de ID3v2.3: id, tamaño en 32 bits normales y dos bytes de flags. */
    private fun frame(id: String, content: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(intToBigEndian(content.size))
            write(byteArrayOf(0, 0))
            write(content)
        }.toByteArray()

    private fun intToBigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    /** Enteros de 28 bits repartidos en 4 bytes de 7 bits, como exige la cabecera ID3. */
    private fun intToSyncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun syncSafeToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
}
