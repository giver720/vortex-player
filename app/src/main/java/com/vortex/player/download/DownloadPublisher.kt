package com.vortex.player.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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

    private const val TAG = "DownloadPublisher"

    suspend fun publish(
        context: Context,
        workspace: File,
        treeUri: Uri?,
        kind: DownloadKind
    ): PublishResult = withContext(Dispatchers.IO) {
        undoPlaceholderFolder(workspace)

        val files = workspace.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext PublishResult("—", 0, null)

        // Si a estas alturas queda una subcarpeta, era una lista de verdad: ese nombre se
        // conserva al destino.
        val playlistFolder = files
            .mapNotNull { it.parentFile }
            .firstOrNull { it != workspace }
            ?.name

        val outcome = if (treeUri != null) {
            copyToTree(context, workspace, files, treeUri)
        } else {
            copyToMediaStore(context, workspace, files, kind, playlistFolder)
        }

        // Antes se daba por copiado lo que había que copiar, no lo copiado: cualquier
        // fallo al escribir se tragaba en silencio y el trabajo se marcaba LISTO con el
        // recuento completo. El resultado era una descarga "terminada" y una carpeta de
        // destino vacía, que es la peor forma de fallar: sin error que investigar.
        if (outcome.written == 0) {
            throw IllegalStateException(
                "No se pudo escribir nada en la carpeta de destino. Comprueba que siga " +
                    "existiendo y que Vórtex conserve permiso sobre ella; volver a " +
                    "elegirla en DESTINO suele bastar."
            )
        }
        if (outcome.written < files.size) {
            Log.w(TAG, "Sólo se escribieron ${outcome.written} de ${files.size} archivos")
        }

        workspace.deleteRecursively()
        PublishResult(outcome.location, outcome.written, playlistFolder)
    }

    /** Dónde acabó todo y cuántos ficheros se escribieron de verdad. */
    private data class CopyOutcome(val location: String, val written: Int)

    /**
     * Deshace la carpeta señuelo que yt-dlp crea cuando el enlace no era una lista.
     *
     * Los ficheros suben a la raíz de la zona de trabajo y pierden el índice de relleno
     * ("000 - "), de modo que a partir de aquí todo el camino de publicación ve
     * exactamente lo mismo que en una descarga individual: ni carpeta, ni numeración, ni
     * lista inventada en la biblioteca.
     */
    private fun undoPlaceholderFolder(workspace: File) {
        val placeholder = File(workspace, YtDlpEngine.NO_PLAYLIST_FOLDER)
        if (!placeholder.isDirectory) return

        placeholder.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = File(workspace, file.name.replace(PLACEHOLDER_INDEX, ""))
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        }
        placeholder.deleteRecursively()
    }

    /**
     * El prefijo numérico que la plantilla de lista antepone.
     *
     * Acepta cualquier cantidad de cifras, no tres: yt-dlp **no aplica el relleno de ceros
     * al valor por defecto**, así que cuando no hay índice —que es justo el caso de esta
     * carpeta— escribe "0 - " y no "000 - ". Exigiendo tres dígitos, el prefijo sobrevivía
     * al aplanado y el fichero llegaba al destino llamándose "0 - Título".
     *
     * Sólo se aplica dentro de la carpeta señuelo, donde el nombre lo hemos compuesto
     * nosotros, así que no hay riesgo de mutilar un título que empiece por cifras.
     */
    private val PLACEHOLDER_INDEX = Regex("^\\d+ - ")

    private fun copyToTree(
        context: Context,
        workspace: File,
        files: List<File>,
        treeUri: Uri
    ): CopyOutcome {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return CopyOutcome("Destino no disponible", 0)

        var written = 0
        files.forEach { file ->
            val relativeDir = runCatching {
                file.parentFile
                    ?.takeIf { it != workspace }
                    ?.relativeTo(workspace)
                    ?.path
                    ?.split(File.separator)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            }.getOrDefault(emptyList())

            // Reproduce la jerarquía carpeta a carpeta; SAF no tiene "mkdir -p".
            //
            // Con un bucle y no con `forEach`: al fallar hay que abandonar este fichero,
            // y `return@forEach` sólo saltaba al siguiente tramo de la ruta, dejando el
            // archivo suelto en la raíz del destino en vez de dentro de su carpeta.
            var target: DocumentFile? = root
            for (segment in relativeDir) {
                val next = target?.findFile(segment)?.takeIf { it.isDirectory }
                    ?: target?.createDirectory(segment)
                if (next == null) {
                    Log.w(TAG, "No se pudo crear la carpeta «$segment» en el destino")
                    target = null
                    break
                }
                target = next
            }
            val parent = target ?: return@forEach

            runCatching {
                parent.findFile(file.name)?.delete()
                val created = parent.createFile(mimeTypeOf(file), file.name)
                    ?: error("el destino no dejó crear el archivo")
                val stream = context.contentResolver.openOutputStream(created.uri)
                    ?: error("no se pudo abrir el archivo para escribir")
                stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                written++
            }.onFailure {
                Log.w(TAG, "No se pudo escribir «${file.name}» en el destino", it)
            }
        }
        return CopyOutcome(root.name ?: "Carpeta elegida", written)
    }

    private fun copyToMediaStore(
        context: Context,
        workspace: File,
        files: List<File>,
        kind: DownloadKind,
        playlistFolder: String?
    ): CopyOutcome {
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
            val paths = files.mapNotNull { file ->
                runCatching {
                    val target = File(dir, file.name)
                    file.copyTo(target, overwrite = true)
                    target.absolutePath
                }.onFailure {
                    Log.w(TAG, "No se pudo copiar «${file.name}» a la mediateca", it)
                }.getOrNull()
            }
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
            return CopyOutcome(relativePath, paths.size)
        }

        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        var written = 0
        files.forEach { file ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // IS_PENDING oculta el fichero a otras apps hasta que está completo,
                // evitando que la galería muestre un vídeo a medio copiar.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            runCatching {
                val uri = context.contentResolver.insert(collection, values)
                    ?: error("la mediateca no aceptó el archivo")
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("no se pudo abrir el archivo para escribir")
                stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                written++
            }.onFailure {
                Log.w(TAG, "No se pudo publicar «${file.name}» en la mediateca", it)
            }
        }
        return CopyOutcome(relativePath, written)
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
