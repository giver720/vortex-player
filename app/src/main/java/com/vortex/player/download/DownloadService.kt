package com.vortex.player.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vortex.player.MainActivity
import com.vortex.player.R
import com.vortex.player.VortexApp
import com.vortex.player.data.MediaRepository
import com.vortex.player.data.db.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Procesa la cola de descargas de una en una, en primer plano.
 *
 * Secuencial y no en paralelo a propósito: yt-dlp lanza un proceso de Python por trabajo
 * y varios a la vez saturan la CPU de un móvil y disparan los bloqueos de las fuentes.
 * El ancho de banda se aprovecha igual porque cada descarga ya usa varias conexiones.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    private lateinit var repository: DownloadRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.get(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_CURRENT -> {
                cancelCurrent()
                return START_STICKY
            }
        }
        val notification = buildNotification("Preparando descargas", null, 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startWorkerIfIdle()
        return START_STICKY
    }

    private fun startWorkerIfIdle() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            repository.requeueInterrupted()
            if (!YtDlpEngine.ensureInitialized(this@DownloadService)) {
                notify("No se pudo iniciar yt-dlp", YtDlpEngine.initError, 0f)
                stopSelf()
                return@launch
            }
            drainQueue()
            stopSelf()
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val job = repository.nextQueued() ?: break
            runJob(job)
        }
    }

    private suspend fun runJob(job: DownloadEntity) {
        val processId = "vortex-${job.id}"
        currentProcessId.value = processId
        currentJobId.value = job.id

        val workspace = DestinationStore.workspace(this, job.id)
        workspace.deleteRecursively()
        workspace.mkdirs()

        try {
            repository.updateProgress(job.id, DownloadStatus.FETCHING, 0f, -1, "Consultando fuente…")
            notify(job.url, "Consultando fuente…", 0f)

            val summary = YtDlpEngine.fetchInfo(job.url, flatPlaylist = job.playlist)
            val named = job.copy(
                title = summary?.title?.takeIf { it.isNotBlank() } ?: job.url,
                uploader = summary?.uploader.orEmpty(),
                thumbnailUrl = summary?.thumbnail,
                status = DownloadStatus.DOWNLOADING
            )
            repository.update(named)

            val request = with(repository) { job.toRequest() }
            var lastPersisted = -1f
            var lastPersistedAt = 0L

            YtDlpEngine.download(
                request = request,
                destination = workspace,
                processId = processId
            ) { progress, eta, line ->
                val clamped = (progress / 100f).coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                // yt-dlp emite varias líneas por segundo. Escribir cada una castigaría el
                // disco sin que se note en pantalla, así que sólo se persiste cuando el
                // porcentaje avanza medio punto o cuando pasa medio segundo.
                val worthPersisting = clamped - lastPersisted >= 0.005f ||
                    now - lastPersistedAt >= 500L ||
                    clamped >= 1f
                if (worthPersisting) {
                    lastPersisted = clamped
                    lastPersistedAt = now
                    scope.launch {
                        repository.updateProgress(
                            job.id,
                            if (clamped >= 1f) {
                                DownloadStatus.PROCESSING
                            } else {
                                DownloadStatus.DOWNLOADING
                            },
                            clamped,
                            eta,
                            line.trim()
                        )
                    }
                    notify(named.title, line.trim(), clamped)
                }
            }

            repository.updateProgress(job.id, DownloadStatus.MOVING, 1f, 0, "Guardando en destino…")
            notify(named.title, "Guardando en destino…", 1f)

            val treeUri = DestinationStore.observe(this).first()
            val result = DownloadPublisher.publish(this, workspace, treeUri, job.kind)

            repository.update(
                named.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    etaSeconds = 0,
                    statusLine = "",
                    outputLocation = result.location,
                    playlistFolder = result.playlistFolder,
                    fileCount = result.fileCount,
                    finishedAt = System.currentTimeMillis()
                )
            )
            // La biblioteca se refresca sola para que lo recién bajado aparezca ya.
            MediaRepository.get(this).refresh()
        } catch (e: Exception) {
            val cancelled = cancelledJobs.remove(job.id)
            repository.update(
                job.copy(
                    status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                    errorMessage = if (cancelled) null else e.message ?: e.toString(),
                    finishedAt = System.currentTimeMillis()
                )
            )
            workspace.deleteRecursively()
        } finally {
            currentProcessId.value = null
            currentJobId.value = null
        }
    }

    private fun cancelCurrent() {
        val processId = currentProcessId.value ?: return
        currentJobId.value?.let { cancelledJobs.add(it) }
        YtDlpEngine.cancel(processId)
    }

    // --------------------------------------------------------- notificación

    private fun buildNotification(title: String, text: String?, progress: Float): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_CURRENT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, VortexApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title.take(60))
            .setContentText(text?.take(80))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun notify(title: String, text: String?, progress: Float) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(title, text, progress))
        }
    }

    override fun onDestroy() {
        instance = null
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CANCEL_CURRENT = "com.vortex.player.action.CANCEL_DOWNLOAD"
        private const val NOTIFICATION_ID = 7781

        @Volatile
        private var instance: DownloadService? = null

        private val cancelledJobs = mutableSetOf<Long>()

        private val currentProcessId = MutableStateFlow<String?>(null)
        private val currentJobId = MutableStateFlow<Long?>(null)

        /** Id del trabajo que se está descargando ahora mismo, para resaltarlo en la lista. */
        val activeJobId: StateFlow<Long?> = currentJobId.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelCurrent(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_CURRENT)
            )
        }
    }
}
