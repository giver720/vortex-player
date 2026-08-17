package com.vortex.player.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.destinationDataStore by preferencesDataStore("vortex_downloads")

/**
 * Dónde acaban las descargas.
 *
 * El usuario puede elegir cualquier carpeta con el selector del sistema (SAF), que es la
 * única forma de escribir fuera de la app en Android moderno sin pedir permisos de
 * almacenamiento total. Si no elige nada, se usa una carpeta propia dentro de Descargas.
 */
object DestinationStore {

    private val TREE_URI = stringPreferencesKey("destination_tree_uri")

    /** Nombre del fichero de prueba. Se crea y se borra en el acto. */
    private const val PROBE_NAME = ".vortex-prueba-escritura"

    fun observe(context: Context): Flow<Uri?> =
        context.destinationDataStore.data.map { prefs ->
            prefs[TREE_URI]?.let(Uri::parse)
        }

    /**
     * Guarda la carpeta elegida. Devuelve `false` si no se pudo quedar con ella.
     *
     * El permiso sobre el árbol debe persistirse o se pierde al reiniciar el móvil. Antes
     * ese intento iba dentro de un `runCatching` mudo y la carpeta se guardaba igual: la
     * app se quedaba con un destino que creía suyo y sobre el que no podía escribir, y el
     * fallo no aparecía hasta que una descarga terminaba sin dejar nada. Si no se puede
     * retener, es mejor no aceptarla y decirlo en el momento.
     */
    suspend fun set(context: Context, uri: Uri): Boolean {
        val retained = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            DownloadLog.record(context, "No se pudo retener el permiso sobre $uri", it)
        }.isSuccess

        if (!retained) return false
        context.destinationDataStore.edit { it[TREE_URI] = uri.toString() }
        return true
    }

    /**
     * Comprueba que se puede escribir en el destino **antes** de gastar ancho de banda.
     *
     * Devuelve `null` si todo está bien, o el motivo en un texto que se le pueda enseñar
     * al usuario. Se hace una escritura de prueba de verdad y no basta con preguntar: un
     * `DocumentFile` puede existir, decir que es una carpeta y afirmar que admite
     * escritura, y aun así rechazar cada fichero. Descubrirlo tras bajar doscientos megas
     * es la peor forma posible de enterarse.
     */
    suspend fun verify(context: Context, uri: Uri?): String? = withContext(Dispatchers.IO) {
        // Sin carpeta elegida se publica por la mediateca del sistema, que no puede fallar
        // por permisos revocados.
        if (uri == null) return@withContext null

        val held = runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)
        if (!held) {
            return@withContext "Vórtex ya no tiene permiso sobre la carpeta de destino. " +
                "Vuelve a elegirla en DESTINO."
        }

        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (root == null || !root.isDirectory) {
            return@withContext "La carpeta de destino ya no existe. Elige otra en DESTINO."
        }

        val probe = runCatching { root.createFile("application/octet-stream", PROBE_NAME) }
            .getOrNull()
        if (probe == null) {
            DownloadLog.record(context, "El destino no dejó crear el archivo de prueba")
            return@withContext "La carpeta de destino no admite archivos nuevos. Prueba a " +
                "elegirla otra vez, o elige otra, en DESTINO."
        }

        val wrote = runCatching {
            context.contentResolver.openOutputStream(probe.uri)?.use { it.write(0) } != null
        }.onFailure {
            DownloadLog.record(context, "Fallo escribiendo el archivo de prueba", it)
        }.getOrDefault(false)

        runCatching { probe.delete() }

        if (!wrote) {
            "No se pudo escribir en la carpeta de destino. Vuelve a elegirla en DESTINO."
        } else {
            null
        }
    }

    suspend fun clear(context: Context) {
        context.destinationDataStore.edit { it.remove(TREE_URI) }
    }

    /** Carpeta pública por defecto: Descargas/Vórtex. */
    fun defaultFolder(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Vortex"
        )

    /** Nombre legible para enseñar en la interfaz. */
    fun displayName(context: Context, uri: Uri?): String {
        if (uri == null) return "Descargas/Vortex"
        val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        return doc?.name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "Carpeta elegida"
    }

    /**
     * Zona de trabajo de yt-dlp. Siempre es una ruta real dentro de la app: el proceso de
     * Python no sabe escribir en un `content://`, así que se descarga aquí y luego se
     * traslada al destino elegido.
     */
    fun workspace(context: Context, jobId: Long): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "descargas/$jobId")
            .apply { mkdirs() }
}
