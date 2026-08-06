package com.vortex.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lectura de la biblioteca desde MediaStore. No indexamos nada por nuestra cuenta:
 * el índice del sistema ya está caliente y evita pedir permisos de almacenamiento amplios.
 */
class MediaScanner(private val context: Context) {

    suspend fun scanAll(): List<MediaEntry> = withContext(Dispatchers.IO) {
        val video = runCatching { queryVideos() }.getOrDefault(emptyList())
        val audio = runCatching { queryAudio() }.getOrDefault(emptyList())
        (video + audio).sortedByDescending { it.dateAddedSec }
    }

    private fun queryVideos(): List<MediaEntry> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_ADDED
        )

        val out = mutableListOf<MediaEntry>()
        context.contentResolver.query(
            collection, projection,
            // Los ficheros de 0 s suelen ser descargas a medias o miniaturas huérfanas.
            "${MediaStore.Video.Media.DURATION} > 0",
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = dataCol.takeIf { it >= 0 }?.let { cursor.getStringOrNull(it) }.orEmpty()
                val folder = folderOf(path)
                out += MediaEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getStringOrNull(titleCol) ?: cursor.getStringOrNull(nameCol).orEmpty(),
                    displayName = cursor.getStringOrNull(nameCol).orEmpty(),
                    durationMs = cursor.getLong(durCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    mimeType = cursor.getStringOrNull(mimeCol) ?: "video/*",
                    width = cursor.getInt(wCol),
                    height = cursor.getInt(hCol),
                    folderPath = folder.first,
                    folderName = folder.second,
                    dateAddedSec = cursor.getLong(dateCol),
                    isVideo = true
                )
            }
        }
        return out
    }

    private fun queryAudio(): List<MediaEntry> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )

        val out = mutableListOf<MediaEntry>()
        context.contentResolver.query(
            collection, projection,
            // IS_MUSIC filtra tonos de llamada y notificaciones, que ensucian la biblioteca.
            "${MediaStore.Audio.Media.DURATION} > 0 AND ${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = dataCol.takeIf { it >= 0 }?.let { cursor.getStringOrNull(it) }.orEmpty()
                val folder = folderOf(path)
                out += MediaEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getStringOrNull(titleCol) ?: cursor.getStringOrNull(nameCol).orEmpty(),
                    displayName = cursor.getStringOrNull(nameCol).orEmpty(),
                    durationMs = cursor.getLong(durCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    mimeType = cursor.getStringOrNull(mimeCol) ?: "audio/*",
                    width = 0,
                    height = 0,
                    folderPath = folder.first,
                    folderName = folder.second,
                    dateAddedSec = cursor.getLong(dateCol),
                    isVideo = false,
                    // MediaStore rellena los huecos con el literal "<unknown>"; mostrarlo
                    // tal cual queda peor que caer en el nombre de la carpeta.
                    artist = cursor.getStringOrNull(artistCol).takeUnless { it.isUnknown() },
                    album = cursor.getStringOrNull(albumCol).takeUnless { it.isUnknown() }
                )
            }
        }
        return out
    }

    /** Devuelve (rutaCarpeta, nombreCarpeta). Si DATA no está disponible, cae en "Otros". */
    private fun folderOf(path: String): Pair<String, String> {
        if (path.isBlank()) return "" to "Otros"
        val parent = File(path).parent ?: return "" to "Otros"
        return parent to (File(parent).name.ifBlank { "Otros" })
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun String?.isUnknown(): Boolean =
        this == null || isBlank() || equals("<unknown>", ignoreCase = true)
}
