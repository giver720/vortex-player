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

    /**
     * Selector de yt-dlp para la altura pedida, adaptado al contenedor.
     *
     * La pista de audio se pide compatible con el envase de entrada ([VideoContainer.
     * preferredAudio]): el AAC no entra en webm ni el Opus en mp4 sin pelearse con ffmpeg,
     * y cuando eso pasaba yt-dlp renunciaba al contenedor pedido y devolvía otro.
     *
     * Al vídeo, en cambio, no se le exige extensión a propósito. Por encima de 1080p
     * YouTube sólo publica VP9 y AV1, de modo que filtrar por extensión haría que "el mejor
     * mp4 de hasta 2160p" fuera en realidad el H.264 de 1080p, y pedir 4K devolvería 1080p
     * sin decir nada.
     */
    fun formatSelector(container: VideoContainer = VideoContainer.MP4): String {
        val cap = maxHeight?.let { "[height<=$it]" } ?: ""
        val preferred = container.preferredAudio
        return buildString {
            if (preferred != null) append("bestvideo$cap+$preferred/")
            append("bestvideo$cap+bestaudio/")
            container.ytdlpName?.let { append("best$cap[ext=$it]/") }
            append("best$cap/best")
        }
    }
}

/**
 * Contenedor del vídeo final.
 *
 * MP4 por defecto porque es el único que reproduce cualquier cosa: galería de Android,
 * WhatsApp, televisores, editores. MKV se ofrece porque acepta cualquier códec sin
 * reconvertir, que es lo que hace falta cuando la fuente trae AV1 u Opus y no se quiere
 * perder calidad. ORIGINAL deja lo que venga, sin reenvasar nada.
 */
enum class VideoContainer(val label: String, val ytdlpName: String?) {
    MP4("MP4", "mp4"),
    MKV("MKV", "mkv"),
    WEBM("WEBM", "webm"),
    ORIGINAL("ORIGINAL", null);

    /**
     * Preferencia de pista de audio para este contenedor.
     *
     * No es un capricho: el AAC no entra en un webm ni el Opus en un mp4 sin que ffmpeg
     * proteste. Pedir de entrada la pista compatible evita que yt-dlp renuncie al
     * contenedor pedido y devuelva otro.
     */
    val preferredAudio: String?
        get() = when (this) {
            MP4 -> "bestaudio[ext=m4a]"
            WEBM -> "bestaudio[ext=webm]"
            MKV, ORIGINAL -> null
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
    /** Envase del vídeo final. MP4 por defecto: es el que reproduce cualquier cosa. */
    val videoContainer: VideoContainer = VideoContainer.MP4,
    val audioCodec: AudioCodec = AudioCodec.MP3,
    val audioBitrate: AudioBitrate = AudioBitrate.BEST,
    /**
     * Si el enlace apunta a una lista, se descarga entera y cada lista genera su propia
     * carpeta en el destino. Desactivarlo baja sólo el vídeo concreto del enlace.
     */
    val playlist: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedSubtitles: Boolean = false,
    val embedMetadata: Boolean = true,
    val sponsor: SponsorSettings = SponsorSettings()
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
