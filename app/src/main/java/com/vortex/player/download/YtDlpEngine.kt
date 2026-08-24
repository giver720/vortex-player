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
import org.json.JSONObject
import java.io.File

/**
 * Envoltorio de yt-dlp. La librería trae un Python real más ffmpeg, así que la
 * inicialización descomprime unas decenas de megas la primera vez: se hace una sola vez,
 * bajo cerrojo y fuera del hilo principal.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"

    /**
     * Carpeta señuelo para los enlaces que resultan no ser una lista.
     *
     * yt-dlp decide si hay lista mientras descarga, no antes, así que con "lista completa"
     * activa un vídeo suelto pasa igualmente por la plantilla de lista y necesita algún
     * valor para `playlist_title`. En vez de un nombre visible —que acababa creando una
     * carpeta "Sin lista" en el destino— se usa esta marca, que [DownloadPublisher]
     * reconoce y deshace antes de publicar nada.
     */
    const val NO_PLAYLIST_FOLDER = "__vortex_sin_lista__"

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
    suspend fun fetchInfo(context: Context, url: String, flatPlaylist: Boolean): VideoSummary? =
        withContext(Dispatchers.IO) {
            try {
                fun read(publicFallback: Boolean): VideoSummary {
                    val request = YoutubeDLRequest(url).apply {
                        applyYoutubeAutomation(context, publicFallback)
                        addOption("--no-warnings")
                        if (flatPlaylist) addOption("--flat-playlist")
                    }
                    val info = YoutubeDL.getInstance().getInfo(request)
                    return VideoSummary(
                        title = info.title.orEmpty(),
                        uploader = info.uploader.orEmpty(),
                        thumbnail = info.thumbnail,
                        durationSeconds = info.duration.toLong()
                    )
                }
                runCatching { read(publicFallback = false) }.getOrElse { first ->
                    if (!YoutubeAutomation.isBotChallenge(first.message.orEmpty())) throw first
                    read(publicFallback = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer la información de $url: ${e.message}")
                null
            }
        }

    /**
     * Lee una lista completa en modo plano. No resuelve formatos ni toca los medios, por
     * lo que incluso una colección grande cuesta una sola ejecución ligera de yt-dlp.
     * Cada elemento se convertirá después en un trabajo independiente de la cola.
     */
    suspend fun analyzePlaylist(
        context: Context,
        url: String
    ): PlaylistAnalysis? = withContext(Dispatchers.IO) {
        try {
            fun analyze(publicFallback: Boolean): YoutubeDLResponse {
                val request = YoutubeDLRequest(url).apply {
                    applyYoutubeAutomation(context, publicFallback)
                    addOption("--flat-playlist")
                    addOption("--dump-single-json")
                    addOption("--skip-download")
                    addOption("--no-warnings")
                }
                return YoutubeDL.getInstance().execute(
                    request,
                    "vortex-analyze-${System.nanoTime()}"
                )
            }
            val response = runCatching { analyze(publicFallback = false) }.getOrElse { first ->
                if (!YoutubeAutomation.isBotChallenge(first.message.orEmpty())) throw first
                analyze(publicFallback = true)
            }
            val root = JSONObject(response.out.trim())
            val rawEntries = root.optJSONArray("entries")
            val entries = buildList {
                if (rawEntries != null) {
                    for (position in 0 until rawEntries.length()) {
                        val item = rawEntries.optJSONObject(position) ?: continue
                        parsePlaylistEntry(item, position + 1)?.let(::add)
                    }
                } else {
                    parsePlaylistEntry(root, 1)?.let(::add)
                }
            }
            if (entries.isEmpty()) return@withContext null
            PlaylistAnalysis(
                name = root.optString("title").ifBlank { entries.first().title },
                uploader = root.optString("uploader").ifBlank {
                    root.optString("channel")
                },
                coverUrl = root.optString("thumbnail").takeIf { it.isNotBlank() }
                    ?: entries.firstNotNullOfOrNull { it.thumbnailUrl },
                entries = entries,
                isPlaylist = rawEntries != null
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo analizar la lista $url: ${e.message}")
            null
        }
    }

    private fun parsePlaylistEntry(json: JSONObject, fallbackIndex: Int): PlaylistEntry? {
        val rawId = json.optString("id").takeIf { it.isNotBlank() }
        val extractor = json.optString("extractor_key").ifBlank {
            json.optString("extractor")
        }
        val rawUrl = json.optString("webpage_url").takeIf { it.startsWith("http") }
            ?: json.optString("url").takeIf { it.startsWith("http") }
            ?: if (rawId != null && extractor.contains("youtube", ignoreCase = true)) {
                "https://www.youtube.com/watch?v=$rawId"
            } else {
                null
            }
        val title = json.optString("title").ifBlank { "Elemento $fallbackIndex" }
        val sourceId = buildString {
            append(extractor.ifBlank { "source" }.lowercase())
            append(':')
            append(rawId ?: rawUrl ?: return null)
        }
        return PlaylistEntry(
            id = sourceId,
            url = rawUrl ?: return null,
            title = title,
            uploader = json.optString("uploader").ifBlank { json.optString("channel") },
            thumbnailUrl = json.optString("thumbnail").takeIf { it.isNotBlank() },
            durationSeconds = json.optLong("duration").coerceAtLeast(0),
            index = json.optInt("playlist_index", fallbackIndex).coerceAtLeast(1)
        )
    }

    /**
     * Ejecuta la descarga. [onProgress] recibe el porcentaje (0..100), los segundos
     * restantes estimados y la línea cruda de yt-dlp.
     */
    suspend fun download(
        context: Context,
        request: DownloadRequest,
        destination: File,
        processId: String,
        /**
         * Qué descargar, si no es [DownloadRequest.url]. Las canciones de Spotify pasan
         * aquí una búsqueda tipo `ytsearch5:Artista - Título`: el enlace original sólo
         * identifica la pista en el catálogo, no un audio descargable.
         */
        sourceOverride: String? = null,
        /** Duración esperada en ms; descarta resultados que se alejen. 0 = sin filtro. */
        targetDurationMs: Long = 0,
        /** Nombre de fichero sin extensión. Se usa para no heredar el título del vídeo. */
        outputName: String? = null,
        /** Límite por proceso en Kbit/s. 0 deja que la fuente use todo el ancho disponible. */
        rateLimitKbps: Int = 0,
        onProgress: (Float, Long, String) -> Unit
    ): YoutubeDLResponse = withContext(Dispatchers.IO) {
        fun buildRequest(publicFallback: Boolean) =
            YoutubeDLRequest(sourceOverride ?: request.url).apply {
            applyYoutubeAutomation(context, publicFallback)
            if (targetDurationMs > 0) {
                // ±15 s: suficiente para tolerar intros y silencios finales, y estrecho
                // para descartar directos, popurrís y bucles de una hora.
                val seconds = targetDurationMs / 1000
                val low = (seconds - 15).coerceAtLeast(1)
                val high = seconds + 15
                addOption("--match-filter", "duration > $low & duration < $high")
                // Que un vídeo descartado por el filtro no aborte la búsqueda entera lo
                // cubre ya el `--ignore-errors` de más abajo, que es más amplio. Poner
                // aquí `--no-abort-on-error` sería una bandera muerta: comparten destino
                // y ganaría la última, con lo que parecería hacer algo sin hacerlo.
                // La búsqueda devuelve varios candidatos y todos comparten nombre de
                // salida: sin este tope se pisarían unos a otros y acabaríamos con el
                // quinto resultado en vez del mejor. yt-dlp termina con código 101 al
                // alcanzarlo, así que el éxito se decide viendo si hay fichero, no por
                // el código de salida.
                addOption("--max-downloads", "1")
            }
            addOption("--no-mtime")
            // El workspace sobrevive a cierres y reintentos. yt-dlp continúa el `.part`
            // en vez de volver a transferir desde cero.
            addOption("--continue")
            addOption("--part")
            addOption("--retries", "3")
            addOption("--fragment-retries", "5")
            if (rateLimitKbps > 0) addOption("--limit-rate", "${rateLimitKbps}K")

            // Sin esto, un fallo de postprocesado se lleva por delante la pista entera.
            // El valor por defecto de yt-dlp es "only_download", que sólo perdona los
            // errores de descarga: los de postprocesado se relanzan (YoutubeDL.py, al
            // ejecutar cada PP). Con la incrustación de miniaturas fallando —que es lo que
            // dejaba los .webp sueltos— una lista de cinco acababa con dos o tres pistas,
            // distintas en cada intento según qué imagen trajera cada vídeo.
            addOption("--ignore-errors")
            addOption("-o", outputTemplate(request, destination, outputName))

            if (request.playlist) addOption("--yes-playlist") else addOption("--no-playlist")

            when (request.kind) {
                DownloadKind.AUDIO -> {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", request.audioCodec.ytdlpName)
                    request.audioBitrate.value?.let { addOption("--audio-quality", it) }
                }
                DownloadKind.VIDEO -> {
                    addOption("-f", request.videoQuality.formatSelector(request.videoContainer))
                    request.videoContainer.ytdlpName?.let { container ->
                        // Remuxar (no recodificar) mantiene la calidad intacta y es casi
                        // instantáneo; recodificar tardaría más que la propia descarga.
                        addOption("--merge-output-format", container)
                        // `--merge-output-format` sólo interviene cuando hay dos pistas que
                        // unir. Si la fuente entrega un único fichero ya mezclado no se
                        // mezcla nada y el envase llegaba intacto al destino; esto lo
                        // reenvasa, también sin recodificar, y no toca lo que ya venga bien.
                        addOption("--remux-video", container)
                    }
                }
            }

            applySponsorBlock(this, request)

            if (request.embedThumbnail) {
                addOption("--embed-thumbnail")
                // Ataca la causa en vez del síntoma: YouTube sirve las miniaturas en webp
                // y meter un webp en un mp4 obliga a convertirlo sobre la marcha, que es
                // el paso que reventaba. Convertida a jpg de antemano, la incrustación es
                // trivial y además es el formato que espera la carátula de un mp4.
                addOption("--convert-thumbnails", "jpg")
            }
            if (request.embedMetadata) addOption("--embed-metadata")
            if (request.embedSubtitles && request.kind == DownloadKind.VIDEO) {
                addOption("--write-auto-subs")
                addOption("--embed-subs")
                addOption("--sub-langs", "es.*,en.*")
            }
        }

        var botChallenge = false
        val first = runCatching {
            YoutubeDL.getInstance().execute(buildRequest(publicFallback = false), processId) {
                    progress, eta, line ->
                if (YoutubeAutomation.isBotChallenge(line)) {
                    botChallenge = true
                    onProgress(progress, eta, YoutubeAutomation.RECOVERY_STATUS)
                } else {
                    onProgress(progress, eta, line)
                }
            }
        }
        if (!botChallenge) return@withContext first.getOrThrow()

        val producedMedia = destination.walkTopDown()
            .any { it.isFile && it.length() > 0 && DownloadPublisher.isMedia(it) }
        if (producedMedia) return@withContext first.getOrThrow()

        onProgress(0f, -1L, YoutubeAutomation.RECOVERY_STATUS)
        YoutubeDL.getInstance().execute(buildRequest(publicFallback = true), processId) {
                progress, eta, line ->
            onProgress(
                progress,
                eta,
                if (YoutubeAutomation.isBotChallenge(line)) {
                    YoutubeAutomation.RECOVERY_FAILED
                } else {
                    line
                }
            )
        }
    }

    private fun YoutubeDLRequest.applyYoutubeAutomation(
        context: Context,
        publicFallback: Boolean
    ) {
        if (YoutubeAutomation.supportsEjs(versionOrUnknown(context))) {
            YoutubeAutomation.quickJsExecutable(context.applicationInfo.nativeLibraryDir)
                ?.let { path -> addOption("--js-runtimes", "quickjs:$path") }
            addOption("--remote-components", YoutubeAutomation.EJS_COMPONENT)
        }
        if (publicFallback) {
            addOption("--extractor-args", YoutubeAutomation.PUBLIC_CLIENT_ARGUMENT)
        }
    }

    /**
     * Traduce la política de SponsorBlock a opciones de yt-dlp, que consulta la API por
     * su cuenta. Sólo funciona con YouTube: en cualquier otra fuente estas opciones se
     * ignoran sin dar error, así que no hace falta condicionarlas por sitio.
     */
    private fun applySponsorBlock(request: YoutubeDLRequest, download: DownloadRequest) {
        val sponsor = download.sponsor
        if (!sponsor.isActive) return

        when (sponsor.mode) {
            SponsorMode.REMOVE -> {
                request.addOption("--sponsorblock-remove", sponsor.categoriesCsv)
                // Sin esto los cortes caen en el fotograma clave más cercano y se cuelan
                // uno o dos segundos del segmento. Obliga a recodificar el tramo afectado,
                // pero recortar mal es peor que tardar algo más.
                if (download.kind == DownloadKind.VIDEO) {
                    request.addOption("--force-keyframes-at-cuts")
                }
            }
            SponsorMode.MARK -> request.addOption("--sponsorblock-mark", sponsor.categoriesCsv)
            SponsorMode.OFF -> Unit
        }
    }

    /**
     * Plantilla de nombres. Cuando el enlace es una lista, el propio yt-dlp crea la
     * subcarpeta con el nombre de la playlist y numera las pistas, que es exactamente
     * el comportamiento pedido y evita tener que reorganizar ficheros después.
     *
     * Si no había lista, la subcarpeta y el índice caen en [NO_PLAYLIST_FOLDER] y en un
     * índice cero, y [DownloadPublisher] los retira: al destino llega un fichero suelto.
     */
    private fun outputTemplate(
        request: DownloadRequest,
        destination: File,
        outputName: String?
    ): String =
        if (outputName != null) {
            // Nombre impuesto: viene del catálogo de Spotify, no del título del vídeo.
            File(destination, "$outputName.%(ext)s").absolutePath
        } else if (request.playlist) {
            File(
                destination,
                "%(playlist_title|$NO_PLAYLIST_FOLDER)s/%(playlist_index|0)03d - %(title)s.%(ext)s"
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
