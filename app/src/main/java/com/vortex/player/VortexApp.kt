package com.vortex.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class VortexApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /**
     * Sin [VideoFrameDecoder] las tarjetas de vídeo saldrían vacías: Coil no sabe extraer
     * un fotograma de un MP4 por defecto. La caché en disco evita volver a decodificar
     * el primer fotograma en cada scroll, que es lo que hace lenta a la competencia.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(120L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYBACK,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_POPUP,
                getString(R.string.popup_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.downloads_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(true) }
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "vortex.playback"
        const val CHANNEL_POPUP = "vortex.popup"
        const val CHANNEL_DOWNLOADS = "vortex.downloads"
    }
}
