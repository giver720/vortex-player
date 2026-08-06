package com.vortex.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadStatus
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

    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = -1,
    /** Línea cruda de yt-dlp: velocidad, tamaño, fragmento… Se muestra tal cual en el HUD. */
    val statusLine: String = "",
    val errorMessage: String? = null,

    /** Carpeta creada para la lista, si el enlace era una playlist. */
    val playlistFolder: String? = null,
    /** Destino final ya resuelto, en forma legible para el usuario. */
    val outputLocation: String? = null,
    val fileCount: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)
