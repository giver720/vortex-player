package com.vortex.player.popup

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vortex.player.R
import com.vortex.player.VortexApp
import com.vortex.player.playback.PlaybackHub
import kotlin.math.roundToInt

/**
 * Ventana flotante real, por encima de cualquier aplicación.
 *
 * A diferencia del PiP del sistema, esta ventana es nuestra: se coloca donde el usuario
 * quiera, se redimensiona con dos dedos y —lo importante— lleva dentro el interruptor de
 * solo-audio, así que se puede colapsar el vídeo a un reproductor de sonido sin volver
 * a la app ni interrumpir lo que suena.
 */
class PopupService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var rootView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (rootView == null) addOverlay()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PopupService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, VortexApp.CHANNEL_POPUP)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Ventana flotante activa")
            .setContentText("Vórtex sigue reproduciendo por encima de otras apps")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .addAction(0, "Cerrar", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun addOverlay() {
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.62f).roundToInt()
        val height = (width * 9f / 16f).roundToInt()

        layoutParams = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE deja pasar el teclado a la app de debajo pero sigue
            // entregándonos los toques, que es justo lo que necesita el popup.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - width - 24
            y = metrics.heightPixels / 4
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PopupService)
            setViewTreeViewModelStoreOwner(this@PopupService)
            setViewTreeSavedStateRegistryOwner(this@PopupService)
            setContent {
                PopupWindowContent(
                    onMove = { dx, dy -> moveBy(dx, dy) },
                    onResize = { factor -> resizeBy(factor) },
                    onClose = { stopSelf() },
                    onExpand = { openFullPlayer() }
                )
            }
        }

        windowManager.addView(view, layoutParams)
        rootView = view
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        PlaybackHub.setPopupVisible(true)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = rootView ?: return
        layoutParams.x += dx.roundToInt()
        layoutParams.y += dy.roundToInt()
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun resizeBy(factor: Float) {
        val view = rootView ?: return
        val metrics = resources.displayMetrics
        val minWidth = (metrics.widthPixels * 0.32f).roundToInt()
        val maxWidth = metrics.widthPixels
        val newWidth = (layoutParams.width * factor).roundToInt().coerceIn(minWidth, maxWidth)
        // La altura sigue al ancho para no deformar el vídeo al redimensionar.
        layoutParams.height = (newWidth * layoutParams.height / layoutParams.width.toFloat()).roundToInt()
        layoutParams.width = newWidth
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun openFullPlayer() {
        startActivity(
            Intent(this, com.vortex.player.ui.player.PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        stopSelf()
    }

    override fun onDestroy() {
        PlaybackHub.setPopupVisible(false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_HIDE = "com.vortex.player.action.HIDE_POPUP"
        private const val NOTIFICATION_ID = 4471

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun show(context: Context) {
            val intent = Intent(context, PopupService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, PopupService::class.java).setAction(ACTION_HIDE)
            )
        }
    }
}
