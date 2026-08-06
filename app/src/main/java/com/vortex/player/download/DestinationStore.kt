package com.vortex.player.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun observe(context: Context): Flow<Uri?> =
        context.destinationDataStore.data.map { prefs ->
            prefs[TREE_URI]?.let(Uri::parse)
        }

    suspend fun set(context: Context, uri: Uri) {
        // El permiso sobre el árbol debe persistirse o se pierde al reiniciar el móvil.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        context.destinationDataStore.edit { it[TREE_URI] = uri.toString() }
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
