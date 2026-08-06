package com.vortex.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga el APK de la release y lo entrega al instalador del sistema.
 *
 * No se instala en silencio a propósito: eso exigiría ser propietario del dispositivo o
 * firmar con la clave de plataforma. El usuario ve el diálogo del sistema y confirma,
 * que es lo mismo que hace F-Droid.
 */
object UpdateInstaller {

    /** El sistema sólo deja instalar si esta app está autorizada como origen. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Descarga [asset] informando del progreso en 0..1. Devuelve el fichero, o `null`
     * si falló. Se descarga a un `.part` y se renombra al terminar, para que una descarga
     * interrumpida no quede como un APK aparentemente válido.
     */
    suspend fun download(
        context: Context,
        asset: ReleaseAsset,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() }

        val target = File(folder, asset.name)
        val partial = File(folder, asset.name + ".part")

        runCatching {
            val connection = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Vortex-Player")
            }
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReported = 0f
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val progress = (copied.toFloat() / total).coerceIn(0f, 1f)
                                // Avisar en cada bloque saturaría la interfaz sin que se note.
                                if (progress - lastReported >= 0.01f) {
                                    lastReported = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            partial.renameTo(target)
            onProgress(1f)
            target
        }.getOrElse {
            partial.delete()
            null
        }
    }

    /** Lanza el instalador del sistema para el APK descargado. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
