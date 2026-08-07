package com.vortex.player.data.db

import androidx.room.TypeConverter
import com.vortex.player.download.AudioBitrate
import com.vortex.player.download.AudioCodec
import com.vortex.player.download.DownloadKind
import com.vortex.player.download.DownloadStatus
import com.vortex.player.download.SponsorMode
import com.vortex.player.download.VideoQuality

/**
 * Los enums se guardan por nombre, no por ordinal: reordenar una lista de calidades
 * en el futuro no debe reinterpretar las descargas ya guardadas.
 */
class Converters {

    @TypeConverter fun kindToString(value: DownloadKind): String = value.name
    @TypeConverter fun stringToKind(value: String): DownloadKind =
        runCatching { DownloadKind.valueOf(value) }.getOrDefault(DownloadKind.VIDEO)

    @TypeConverter fun qualityToString(value: VideoQuality): String = value.name
    @TypeConverter fun stringToQuality(value: String): VideoQuality =
        runCatching { VideoQuality.valueOf(value) }.getOrDefault(VideoQuality.BEST)

    @TypeConverter fun codecToString(value: AudioCodec): String = value.name
    @TypeConverter fun stringToCodec(value: String): AudioCodec =
        runCatching { AudioCodec.valueOf(value) }.getOrDefault(AudioCodec.MP3)

    @TypeConverter fun bitrateToString(value: AudioBitrate): String = value.name
    @TypeConverter fun stringToBitrate(value: String): AudioBitrate =
        runCatching { AudioBitrate.valueOf(value) }.getOrDefault(AudioBitrate.BEST)

    @TypeConverter fun sponsorModeToString(value: SponsorMode): String = value.name
    @TypeConverter fun stringToSponsorMode(value: String): SponsorMode =
        runCatching { SponsorMode.valueOf(value) }.getOrDefault(SponsorMode.OFF)

    @TypeConverter fun statusToString(value: DownloadStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.QUEUED)
}
