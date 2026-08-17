package com.vortex.player.download

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de lo que sale mal en las descargas, consultable desde la propia app.
 *
 * Hasta ahora todo iba a `Log.w`, que exige cable, ordenador y logcat: en la práctica,
 * nadie lo lee y cada fallo había que reproducirlo a ciegas. Con esto, lo que falló queda
 * escrito y se puede compartir tal cual, que es la diferencia entre diagnosticar en un
 * mensaje o en cinco rondas de preguntas.
 *
 * Es deliberadamente tonto —un fichero de texto con un tope de líneas— porque un registro
 * de errores que falle o que crezca sin límite sería peor que no tenerlo.
 */
object DownloadLog {

    private const val TAG = "DownloadLog"
    private const val FILE_NAME = "descargas-registro.txt"

    /** Tope de entradas. Al pasarse se tira la mitad más vieja, no una a una. */
    private const val MAX_ENTRIES = 300

    private val lock = Any()
    private val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * Anota una línea. Nunca lanza: un fallo escribiendo el registro no puede tumbar la
     * descarga que está intentando describir.
     */
    fun record(context: Context, message: String, error: Throwable? = null) {
        val line = buildString {
            append(stamp.format(Date()))
            append("  ")
            append(message)
            error?.let { append("  |  ").append(it.javaClass.simpleName).append(": ").append(it.message) }
        }
        Log.w(TAG, line)
        runCatching {
            synchronized(lock) {
                val target = file(context)
                target.appendText(line + "\n")
                trimIfNeeded(target)
            }
        }
    }

    private fun trimIfNeeded(target: File) {
        val lines = target.readLines()
        if (lines.size <= MAX_ENTRIES) return
        target.writeText(lines.takeLast(MAX_ENTRIES / 2).joinToString("\n", postfix = "\n"))
    }

    /** Lo registrado, lo más reciente primero, que es como se mira. */
    fun entries(context: Context): List<String> = runCatching {
        synchronized(lock) { file(context).readLines() }.filter { it.isNotBlank() }.asReversed()
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { synchronized(lock) { file(context).delete() } }
    }

    /** Texto para compartir, con la cabecera puesta: sin contexto un registro no sirve. */
    fun shareText(context: Context, appVersion: String, androidRelease: String): String =
        buildString {
            append("Vórtex $appVersion · Android $androidRelease\n")
            append("Registro de descargas\n\n")
            val lines = entries(context)
            if (lines.isEmpty()) append("Sin incidencias registradas.") else {
                lines.asReversed().forEach { append(it).append('\n') }
            }
        }
}
