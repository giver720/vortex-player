package com.vortex.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadStatus
import com.vortex.player.download.SponsorMode
import com.vortex.player.download.VideoQuality

/**
 * Una descarga, viva o histórica. La petición se guarda desarmada en columnas en vez de
 * serializada, para poder reintentar un trabajo antiguo sin volver a preguntar nada.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val uploader: String = "",
    val thumbnailUrl: String? = null,

    val kind: DownloadKind = DownloadKind.VIDEO,
    val videoQuality: VideoQuality = VideoQuality.BEST,
    val audioCodec: AudioCodec = AudioCodec.MP3,
    val audioBitrate: AudioBitrate = AudioBitrate.BEST,
    val playlist: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedSubtitles: Boolean = false,
    val embedMetadata: Boolean = true,

    /**
     * Política de SponsorBlock con la que se lanzó el trabajo. Se guarda en la fila y no
     * se lee de los ajustes al reintentar: si el usuario cambia sus preferencias, una
     * descarga que reintente debe repetir lo que se pidió entonces, no lo de ahora.
     */
    val sponsorMode: SponsorMode = SponsorMode.OFF,
    val sponsorCategories: String = "",

    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = -1,
    /** Línea cruda de yt-dlp: velocidad, tamaño, fragmento… Se muestra tal cual en el HUD. */
    val statusLine: String = "",
    val errorMessage: String? = null,

    /**
     * Consulta que se pasa a yt-dlp en lugar de [url]. La usan las canciones venidas de
     * Spotify: el enlace original identifica la pista en el catálogo, pero lo que hay
     * que descargar es el resultado de buscarla en YouTube Music.
     */
    val searchQuery: String? = null,

    /**
     * Identificador de la pista en su catálogo de origen. Es lo que permite reconocer que
     * una canción ya se bajó al volver a resolver la misma lista, aunque el fichero se
     * haya renombrado o movido de sitio.
     */
    val sourceId: String? = null,

    /** Duración esperada, para descartar directos y versiones alteradas. 0 = sin filtro. */
    val targetDurationMs: Long = 0,

    /** Etiquetas a escribir al terminar, serializadas en JSON. `null` = dejar las de yt-dlp. */
    val tagsJson: String? = null,

    /** Carpeta creada para la lista, si el enlace era una playlist. */
    val playlistFolder: String? = null,

    /**
     * Avance dentro de la lista, cuando el enlace resultó serlo. Una lista se descarga en
     * un único proceso de yt-dlp, así que sin esto la fila sólo podía enseñar el
     * porcentaje del fichero en curso, que vuelve a cero en cada pista.
     *
     * [playlistCount] es 0 mientras no se sepa, y sigue en 0 para un enlace suelto: es lo
     * que distingue "una lista de un elemento" de "esto no era una lista".
     */
    val playlistCount: Int = 0,
    val playlistIndex: Int = 0,

    /** Nombres de lo ya descargado de la lista, uno por línea y en orden. */
    val playlistItems: String = "",
    /** Destino final ya resuelto, en forma legible para el usuario. */
    val outputLocation: String? = null,
    val fileCount: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)
