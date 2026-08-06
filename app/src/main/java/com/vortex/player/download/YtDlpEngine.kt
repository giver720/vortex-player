package com.vortex.player.download

import android.content.Context
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Envoltorio de yt-dlp. La librería trae un Python real más ffmpeg, así que la
 * inicialización descomprime unas decenas de megas la primera vez: se hace una sola vez,
 * bajo cerrojo y fuera del hilo principal.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"

    private val initLock = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    var initError: String? = null
        private set

    suspend fun ensureInitialized(context: Context): Boolean = initLock.withLock {
        if (initialized) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                // aria2c acelera mucho las fuentes fragmentadas (HLS/DASH), pero no es
                // imprescindible: si falta, yt-dlp usa su descargador interno.
                runCatching { Aria2c.getInstance().init(context) }
                    .onFailure { Log.w(TAG, "aria2c no disponible: ${it.message}") }
                initialized = true
                initError = null
                true
            } catch (t: Throwable) {
                // Se captura `Throwable` y no `Exception` a propósito: si el paquete de
                // Python viene incompleto, la librería revienta con ExceptionInInitializerError,
                // que es un Error. Dejarlo escapar tumbaría toda la app por una función
                // que el usuario quizá ni esté usando.
                Log.e(TAG, "No se pudo inicializar yt-dlp", t)
                initError = t.message ?: t.toString()
                false
            }
        }
    }

    /** Actualiza el propio yt-dlp; sin esto, YouTube deja de funcionar en cuestión de semanas. */
    suspend fun updateBinary(context: Context): String = withContext(Dispatchers.IO) {
        if (!ensureInitialized(context)) return@withContext "yt-dlp no inicializado"
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Actualizado a " + versionOrUnknown(context)
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Ya estaba al día (" +
                    versionOrUnknown(context) + ")"
                else -> "Sin cambios"
            }
        } catch (e: Exception) {
            "Error al actualizar: ${e.message}"
        }
    }

    fun versionOrUnknown(context: Context): String =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() ?: "desconocida"

    /**
     * Consulta metadatos sin descargar. Para listas se pide `--flat-playlist`, que evita
     * resolver cada elemento y devuelve el título de la lista en un par de segundos.
     */
    suspend fun fetchInfo(url: String, flatPlaylist: Boolean): VideoSummary? =
        withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-warnings")
                    if (flatPlaylist) addOption("--flat-playlist")
                }
                val info = YoutubeDL.getInstance().getInfo(request)
                VideoSummary(
                    title = info.title.orEmpty(),
                    uploader = info.uploader.orEmpty(),
                    thumbnail = info.thumbnail,
                    durationSeconds = info.duration.toLong()
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer la información de $url: ${e.message}")
                null
            }
        }

    /**
     * Ejecuta la descarga. [onProgress] recibe el porcentaje (0..100), los segundos
     * restantes estimados y la línea cruda de yt-dlp.
     */
    suspend fun download(
        request: DownloadRequest,
        destination: File,
        processId: String,
        onProgress: (Float, Long, String) -> Unit
    ): YoutubeDLResponse = withContext(Dispatchers.IO) {
        val ytdlp = YoutubeDLRequest(request.url).apply {
            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("--restrict-filenames")
            addOption("-o", outputTemplate(request, destination))

            if (request.playlist) addOption("--yes-playlist") else addOption("--no-playlist")

            when (request.kind) {
                DownloadKind.AUDIO -> {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", request.audioCodec.ytdlpName)
                    request.audioBitrate.value?.let { addOption("--audio-quality", it) }
                }
                DownloadKind.VIDEO -> {
                    addOption("-f", request.videoQuality.formatSelector())
                    // Remuxar (no recodificar) a MP4 mantiene la calidad intacta y es
                    // casi instantáneo; recodificar tardaría más que la propia descarga.
                    addOption("--merge-output-format", "mp4")
                }
            }

            if (request.embedThumbnail) addOption("--embed-thumbnail")
            if (request.embedMetadata) addOption("--embed-metadata")
            if (request.embedSubtitles && request.kind == DownloadKind.VIDEO) {
                addOption("--write-auto-subs")
                addOption("--embed-subs")
                addOption("--sub-langs", "es.*,en.*")
            }
        }

        YoutubeDL.getInstance().execute(ytdlp, processId) { progress, eta, line ->
            onProgress(progress, eta, line)
        }
    }

    /**
     * Plantilla de nombres. Cuando el enlace es una lista, el propio yt-dlp crea la
     * subcarpeta con el nombre de la playlist y numera las pistas, que es exactamente
     * el comportamiento pedido y evita tener que reorganizar ficheros después.
     */
    private fun outputTemplate(request: DownloadRequest, destination: File): String =
        if (request.playlist) {
            File(
                destination,
                "%(playlist_title|Sin lista)s/%(playlist_index|0)03d - %(title)s.%(ext)s"
            ).absolutePath
        } else {
            File(destination, "%(title)s.%(ext)s").absolutePath
        }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}

data class VideoSummary(
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val durationSeconds: Long
)
