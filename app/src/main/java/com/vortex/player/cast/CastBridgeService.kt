package com.vortex.player.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vortex.player.MainActivity
import com.vortex.player.R

/** Mantiene vivo el puente local exactamente mientras el televisor lee el archivo. */
class CastBridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Medio local" }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vortex_mark)
            .setContentTitle("Vórtex está enviando a la TV")
            .setContentText(title)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LocalCastServer.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.cast_bridge_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "vortex_cast_bridge"
        private const val NOTIFICATION_ID = 3025
        private const val EXTRA_TITLE = "title"

        fun keepAlive(context: Context, title: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CastBridgeService::class.java).putExtra(EXTRA_TITLE, title)
            )
        }

        fun stop(context: Context) {
            LocalCastServer.stop()
            context.stopService(Intent(context, CastBridgeService::class.java))
        }
    }
}
