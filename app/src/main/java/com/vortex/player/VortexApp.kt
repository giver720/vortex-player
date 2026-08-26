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
import com.vortex.player.cast.CastCoordinator
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackEventLog
import com.vortex.player.playback.PlaybackSessionStore
import com.vortex.player.playback.toQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VortexApp : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PlaybackEventLog.install(this)
        createNotificationChannels()
        CastCoordinator.initialize(this)
        restorePlaybackSession()
    }

    /** Hace que el dock reaparezca aunque Android haya terminado el proceso anterior. */
    private fun restorePlaybackSession() {
        applicationScope.launch {
            PlaybackSessionStore(this@VortexApp).load()?.let { snapshot ->
                val value = snapshot.normalized()
                PlaybackHub.setQueue(
                    value.entries.mapIndexed { index, entry -> entry.toQueueItem(index) },
                    value.currentIndex,
                    value.positionMs
                )
                PlaybackHub.setAudioOnly(value.audioOnly)
                PlaybackHub.setRepeat(value.repeat)
                PlaybackHub.setShuffle(value.shuffle)
            }
        }
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
