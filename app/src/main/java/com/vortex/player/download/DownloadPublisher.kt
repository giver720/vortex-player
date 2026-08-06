package com.vortex.player.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class PublishResult(
    val location: String,
    val fileCount: Int,
    val playlistFolder: String?
)

/**
 * Traslada lo que yt-dlp dejó en su zona de trabajo hasta donde el usuario quiere verlo.
 *
 * Hay dos caminos porque Android tiene dos mundos de almacenamiento: si el usuario eligió
 * una carpeta, se escribe por SAF; si no, se publica en la mediateca del sistema, que es
 * lo que hace que el fichero aparezca solo en la biblioteca de Vórtex y en la galería.
 */
object DownloadPublisher {

    suspend fun publish(
        context: Context,
        workspace: File,
        treeUri: Uri?,
        kind: DownloadKind
    ): PublishResult = withContext(Dispatchers.IO) {
        val files = workspace.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext PublishResult("—", 0, null)

        // Si yt-dlp creó una subcarpeta, era una lista: ese nombre se conserva al destino.
        val playlistFolder = files
            .mapNotNull { it.parentFile }
            .firstOrNull { it != workspace }
            ?.name

        val location = if (treeUri != null) {
            copyToTree(context, workspace, files, treeUri)
        } else {
            copyToMediaStore(context, workspace, files, kind, playlistFolder)
        }

        workspace.deleteRecursively()
        PublishResult(location, files.size, playlistFolder)
    }

    private fun copyToTree(
        context: Context,
        workspace: File,
        files: List<File>,
        treeUri: Uri
    ): String {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return "Destino no disponible"

        files.forEach { file ->
            val relativeDir = file.parentFile
                ?.takeIf { it != workspace }
                ?.relativeTo(workspace)
                ?.path
                ?.split(File.separator)
                ?.filter { it.isNotBlank() }
                .orEmpty()

            // Reproduce la jerarquía carpeta a carpeta; SAF no tiene "mkdir -p".
            var target = root
            relativeDir.forEach { segment ->
                target = target.findFile(segment)?.takeIf { it.isDirectory }
                    ?: target.createDirectory(segment)
                    ?: return@forEach
            }

            val existing = target.findFile(file.name)
            existing?.delete()
            val created = target.createFile(mimeTypeOf(file), file.name) ?: return@forEach
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }
        return root.name ?: "Carpeta elegida"
    }

    private fun copyToMediaStore(
        context: Context,
        workspace: File,
        files: List<File>,
        kind: DownloadKind,
        playlistFolder: String?
    ): String {
        val isAudio = kind == DownloadKind.AUDIO
        val baseDir = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val relativePath = buildString {
            append(baseDir)
            append("/Vortex")
            if (!playlistFolder.isNullOrBlank()) append("/$playlistFolder")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Antes del almacenamiento aislado basta con copiar y avisar al escáner.
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), "Vortex")
                .let { if (playlistFolder.isNullOrBlank()) it else File(it, playlistFolder) }
            dir.mkdirs()
            val paths = files.map { file ->
                val target = File(dir, file.name)
                file.copyTo(target, overwrite = true)
                target.absolutePath
            }
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
            return relativePath
        }

        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        files.forEach { file ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // IS_PENDING oculta el fichero a otras apps hasta que está completo,
                // evitando que la galería muestre un vídeo a medio copiar.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return@forEach
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }
        return relativePath
    }

    private fun mimeTypeOf(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "opus" -> "audio/opus"
                "m4a" -> "audio/mp4"
                else -> "application/octet-stream"
            }
    }
}
