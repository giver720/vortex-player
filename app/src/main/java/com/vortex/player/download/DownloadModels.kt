package com.vortex.player.download

/** Qué se quiere obtener del enlace: la película o sólo la banda sonora. */
enum class DownloadKind(val label: String) {
    VIDEO("VÍDEO"),
    AUDIO("AUDIO")
}

/**
 * Calidad máxima de vídeo. Se expresa como selector de yt-dlp y no como número suelto
 * porque hay que combinar el mejor vídeo bajo cierta altura con el mejor audio suelto,
 * y caer con elegancia a un formato ya mezclado si la fuente no ofrece pistas separadas.
 */
enum class VideoQuality(val label: String, private val maxHeight: Int?) {
    BEST("MÁXIMA", null),
    UHD("2160p", 2160),
    QHD("1440p", 1440),
    FHD("1080p", 1080),
    HD("720p", 720),
    SD("480p", 480),
    LOW("360p", 360);

    fun formatSelector(): String {
        val cap = maxHeight?.let { "[height<=$it]" } ?: ""
        return "bestvideo$cap+bestaudio/best$cap/best"
    }
}

enum class AudioCodec(val label: String, val ytdlpName: String, val extension: String) {
    MP3("MP3", "mp3", "mp3"),
    M4A("M4A", "m4a", "m4a"),
    OPUS("OPUS", "opus", "opus"),
    FLAC("FLAC", "flac", "flac"),
    WAV("WAV", "wav", "wav")
}

enum class AudioBitrate(val label: String, val value: String?) {
    BEST("MEJOR", "0"),
    K320("320k", "320K"),
    K256("256k", "256K"),
    K192("192k", "192K"),
    K128("128k", "128K")
}

/** Lo que el usuario pide. Se traduce a argumentos de yt-dlp en [YtDlpEngine]. */
data class DownloadRequest(
    val url: String,
    val kind: DownloadKind = DownloadKind.VIDEO,
    val videoQuality: VideoQuality = VideoQuality.BEST,
    val audioCodec: AudioCodec = AudioCodec.MP3,
    val audioBitrate: AudioBitrate = AudioBitrate.BEST,
    /**
     * Si el enlace apunta a una lista, se descarga entera y cada lista genera su propia
     * carpeta en el destino. Desactivarlo baja sólo el vídeo concreto del enlace.
     */
    val playlist: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedSubtitles: Boolean = false,
    val embedMetadata: Boolean = true
)

enum class DownloadStatus {
    QUEUED,
    FETCHING,
    DOWNLOADING,
    PROCESSING,
    MOVING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val label: String
        get() = when (this) {
            QUEUED -> "EN COLA"
            FETCHING -> "CONSULTANDO"
            DOWNLOADING -> "DESCARGANDO"
            PROCESSING -> "CONVIRTIENDO"
            MOVING -> "GUARDANDO"
            COMPLETED -> "LISTO"
            FAILED -> "ERROR"
            CANCELLED -> "CANCELADO"
        }
}
