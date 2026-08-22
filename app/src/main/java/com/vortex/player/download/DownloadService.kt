package com.vortex.player.download

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vortex.player.MainActivity
import com.vortex.player.R
import com.vortex.player.VortexApp
import com.vortex.player.data.MediaRepository
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.spotify.Id3Tagger
import com.vortex.player.spotify.SpotifyJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Procesa la cola en primer plano respetando el límite de simultaneidad elegido.
 * Cada trabajo conserva un processId propio, por lo que se puede cancelar sin afectar a
 * las demás descargas que estén activas.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val desiredParallelism = MutableStateFlow(DownloadConcurrency.DEFAULT)
    private val desiredPolicy = MutableStateFlow(DownloadPolicy())
    private val activeProcesses = ConcurrentHashMap<Long, String>()

    private lateinit var repository: DownloadRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.get(this)
        instance = this
        scope.launch {
            EnginePreferences.concurrentDownloads(this@DownloadService).collect { value ->
                desiredParallelism.value = DownloadConcurrency.clamp(value)
            }
        }
        scope.launch {
            EnginePreferences.downloadPolicy(this@DownloadService).collect { value ->
                desiredPolicy.value = value
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_JOB -> {
                cancelJob(intent.getLongExtra(EXTRA_JOB_ID, -1L))
                if (activeProcesses.isEmpty()) stopSelf()
                return if (activeProcesses.isEmpty()) START_NOT_STICKY else START_STICKY
            }
            ACTION_CANCEL_ALL -> {
                cancelAll()
                if (activeProcesses.isEmpty()) stopSelf()
                return if (activeProcesses.isEmpty()) START_NOT_STICKY else START_STICKY
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

            // Antes de bajar nada: YouTube cambia su cifrado de firmas cada pocas semanas
            // y un yt-dlp de hace un mes empieza a fallar. Actualizar aquí, con la cola ya
            // en marcha, evita que el usuario descubra el problema como una descarga rota.
            if (EnginePreferences.shouldAutoUpdate(this@DownloadService)) {
                notify("Actualizando yt-dlp", "Sólo la primera vez del día", 0f)
                val result = YtDlpEngine.updateBinary(this@DownloadService)
                EnginePreferences.record(this@DownloadService, result)
            }

            desiredParallelism.value = EnginePreferences.concurrentDownloads(
                this@DownloadService
            ).first()
            desiredPolicy.value = EnginePreferences.downloadPolicy(this@DownloadService).first()
            drainQueue()
            stopSelf()
        }
    }

    private suspend fun drainQueue() = supervisorScope {
        data class Running(val entity: DownloadEntity, val task: Job)
        val running = mutableMapOf<Long, Running>()
        var deviceSnapshot = DownloadDeviceConditions.snapshot(this@DownloadService)
        var snapshotAt = 0L

        while (true) {
            running.entries.removeAll { it.value.task.isCompleted }

            if (queuePaused.value) {
                // Si se reanuda antes de que terminen las activas, este mismo coordinador
                // continúa. Así la cola no queda varada esperando otro startService.
                if (running.isEmpty()) break
                delay(COORDINATOR_TICK_MS)
                continue
            }

            if (running.isEmpty() &&
                repository.eligibleQueued().isEmpty() &&
                repository.nextRetryAt() == null
            ) {
                break
            }

            val now = System.currentTimeMillis()
            if (now - snapshotAt >= DEVICE_SNAPSHOT_INTERVAL_MS) {
                deviceSnapshot = DownloadDeviceConditions.snapshot(this@DownloadService)
                snapshotAt = now
            }
            val policy = desiredPolicy.value
            val blocked = DownloadDeviceConditions.blockReason(policy, deviceSnapshot)
            blockReason.value = blocked
            if (blocked != null) {
                effectiveSlots.value = 0
                if (running.isEmpty()) notify("Descargas en espera", blocked, 0f)
                delay(CONDITION_TICK_MS)
                continue
            }

            val effectiveLimit = DownloadDeviceConditions.adaptiveLimit(
                desiredParallelism.value,
                policy.adaptiveConcurrency,
                deviceSnapshot
            )
            effectiveSlots.value = effectiveLimit

            while (running.size < effectiveLimit) {
                val sourceCounts = running.values.groupingBy {
                    DownloadSource.from(it.entity)
                }.eachCount()
                val queued = repository.eligibleQueued().firstOrNull { candidate ->
                    val source = DownloadSource.from(candidate)
                    (sourceCounts[source] ?: 0) < policy.sourceLimit(source)
                } ?: break

                // La selección de la fila y su marcado ocurren en este único coordinador.
                // Así dos ranuras nunca pueden reclamar el mismo trabajo de Room.
                repository.updateProgress(
                    queued.id,
                    DownloadStatus.FETCHING,
                    0f,
                    -1,
                    "Preparando descarga…"
                )
                val perJobRate = policy.bandwidthLimitKbps
                    .takeIf { it > 0 }
                    ?.div(effectiveLimit.coerceAtLeast(1))
                    ?.coerceAtLeast(64)
                    ?: 0
                running[queued.id] = Running(
                    entity = queued,
                    task = launch { runJob(queued, perJobRate) }
                )
            }

            if (running.isEmpty() && repository.eligibleQueued().isEmpty()) {
                val retryAt = repository.nextRetryAt()
                if (retryAt == null) break
                delay((retryAt - System.currentTimeMillis()).coerceIn(500L, CONDITION_TICK_MS))
                continue
            }
            delay(COORDINATOR_TICK_MS)
        }
        blockReason.value = null
        effectiveSlots.value = 0
    }

    private suspend fun runJob(job: DownloadEntity, rateLimitKbps: Int) {
        val processId = "vortex-${job.id}"
        activeProcesses[job.id] = processId
        publishActiveJobs()

        val workspace = DestinationStore.workspace(this, job.id)
        // Nunca se borra al empezar: los `.part` son precisamente el punto de reanudación.
        workspace.mkdirs()

        // Avance dentro de la lista, si el enlace resulta serlo. Se va leyendo de la salida
        // de yt-dlp, que anuncia posición y total, así que enterarse no cuesta ni una
        // llamada extra ni espera antes de empezar a bajar.
        //
        // Vive fuera del `try` porque los guardados finales —el de éxito y el de error—
        // reescriben la fila entera a partir de la que se leyó al empezar: si estas tres
        // variables no llegaran hasta allí, el contador y la lista de pistas se borrarían
        // justo al terminar, que es cuando más se quieren ver.
        var playlistIndex = job.playlistIndex
        var playlistCount = job.playlistCount
        val playlistItems = job.playlistItems.lineSequence().filter { it.isNotBlank() }.toMutableList()
        var latest = job

        // Las líneas de error que escupe yt-dlp. Traen el motivo de verdad —formato no
        // disponible, vídeo privado, bloqueo por sospecha de bot— y hasta ahora se
        // perdían: la fila guardaba el mensaje de la excepción, que casi siempre es un
        // "process exited with code 1" que no dice nada.
        val engineErrors = mutableListOf<String>()

        try {
            // Comprobar el destino antes de gastar ancho de banda. Fallar aquí cuesta dos
            // segundos; fallar al final cuesta la descarga entera y no deja ni el fichero.
            DestinationStore.verify(this, DestinationStore.observe(this).first())?.let { why ->
                throw IllegalStateException(why)
            }

            val free = DestinationStore.freeSpaceBytes(this)
            if (free < DestinationStore.MIN_FREE_BYTES) {
                throw IllegalStateException(
                    "Quedan ${free / (1024 * 1024)} MB libres en el móvil y hacen falta al " +
                        "menos ${DestinationStore.MIN_FREE_BYTES / (1024 * 1024)} MB para " +
                        "descargar con margen. Libera espacio y reintenta."
                )
            }

            val spotify = SpotifyJobs.readTags(job.tagsJson)

            // Una canción de Spotify ya viene con título y artista del catálogo, así que
            // consultar metadatos sería gastar una llamada de red para saber lo que ya
            // sabemos —y encima la respondería el vídeo de YouTube, con peores datos.
            val named = if (spotify != null || (job.outputName != null && job.title.isNotBlank())) {
                job.copy(
                    status = DownloadStatus.DOWNLOADING,
                    estimatedBytes = DownloadEstimator.estimateBytes(job)
                )
            } else {
                repository.updateProgress(
                    job.id, DownloadStatus.FETCHING, 0f, -1, "Consultando fuente…"
                )
                notify(job.id, job.url, "Consultando fuente…", 0f)
                val summary = YtDlpEngine.fetchInfo(job.url, flatPlaylist = job.playlist)
                // La consulta ligera no expone processId en la librería. Si se canceló
                // mientras respondía, no debe arrancar después la descarga pesada.
                throwIfCancelled(job.id)
                job.copy(
                    title = summary?.title?.takeIf { it.isNotBlank() } ?: job.url,
                    uploader = summary?.uploader.orEmpty(),
                    thumbnailUrl = summary?.thumbnail,
                    status = DownloadStatus.DOWNLOADING,
                    estimatedBytes = DownloadEstimator.estimateBytes(
                        job,
                        (summary?.durationSeconds ?: 0) * 1_000
                    )
                )
            }
            latest = named
            repository.update(named)

            if (named.estimatedBytes > 0 && free < named.estimatedBytes + DestinationStore.MIN_FREE_BYTES) {
                throw IllegalStateException(
                    "Se estiman ${named.estimatedBytes / (1024 * 1024)} MB y sólo quedan " +
                        "${free / (1024 * 1024)} MB con el margen de seguridad."
                )
            }

            val request = with(repository) { named.toRequest() }
            var lastPersisted = -1f
            var lastPersistedAt = 0L

            // yt-dlp puede terminar con código distinto de cero por motivos benignos
            // (alcanzar `--max-downloads`, descartar candidatos por el filtro). Lo que
            // decide si hubo éxito es si quedó un fichero, no el código de salida.
            runCatching {
                YtDlpEngine.download(
                request = request,
                destination = workspace,
                processId = processId,
                sourceOverride = job.searchQuery,
                targetDurationMs = job.targetDurationMs,
                outputName = job.outputName ?: spotify?.let {
                    SpotifyJobs.outputNameFrom(it.first, job.playlistFolder)
                },
                rateLimitKbps = rateLimitKbps
            ) { progress, eta, line ->
                val clamped = (progress / 100f).coerceIn(0f, 1f)
                val now = System.currentTimeMillis()

                val trimmed = line.trim()
                if (trimmed.startsWith("ERROR", ignoreCase = true) && trimmed !in engineErrors) {
                    // Un puñado basta: en una lista larga el mismo fallo se repite por pista.
                    if (engineErrors.size < 5) engineErrors += trimmed
                }

                var positionChanged = false
                PlaylistProgress.position(line)?.let { (index, total) ->
                    if (index != playlistIndex || total != playlistCount) {
                        playlistIndex = index
                        playlistCount = total
                        positionChanged = true
                    }
                }
                // Sólo se lleva la cuenta de nombres si esto es una lista. yt-dlp anuncia la
                // posición antes que el fichero, así que para cuando llega el primer nombre
                // ya se sabe; en una descarga suelta se ahorra la escritura en disco.
                PlaylistProgress.itemName(line)?.takeIf { playlistCount > 1 }?.let { name ->
                    // Un vídeo con pistas separadas anuncia varios ficheros seguidos; sólo
                    // interesa el primer nombre nuevo de cada elemento.
                    if (playlistItems.lastOrNull() != name) {
                        playlistItems += name
                        positionChanged = true
                    }
                }
                if (positionChanged) {
                    val snapshot = playlistItems.toList()
                    val index = playlistIndex
                    val total = playlistCount
                    scope.launch {
                        repository.updatePlaylistPosition(job.id, index, total, snapshot)
                    }
                }
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
                    // En la notificación manda el avance de la lista entera: una barra que
                    // vuelve a cero cada dos minutos no dice nada de lo que queda.
                    notify(
                        job.id,
                        named.title,
                        buildString {
                            if (playlistCount > 1) append("$playlistIndex/$playlistCount · ")
                            append(line.trim())
                        },
                        PlaylistProgress.overall(playlistIndex, playlistCount, clamped)
                    )
                }
                }
            }.onFailure { error ->
                // Sólo cuenta el medio. Antes valía cualquier fichero, y como la miniatura
                // se baja antes que el vídeo, bastaba con que existiera para dar el trabajo
                // por productivo: el error real de yt-dlp —que traía el motivo— se
                // descartaba aquí, y más adelante se reportaba otro genérico en su lugar.
                val produced = workspace.walkTopDown()
                    .any { it.isFile && it.length() > 0 && DownloadPublisher.isMedia(it) }
                if (job.id in cancelledJobs || !produced) throw error
            }

            throwIfCancelled(job.id)

            // yt-dlp también puede terminar con éxito sin bajar nada: pasa cuando el
            // filtro de duración descarta los cinco candidatos. Sin esta comprobación se
            // marcaría como completada una descarga que dejó la carpeta vacía.
            if (workspace.walkTopDown()
                    .none { it.isFile && it.length() > 0 && DownloadPublisher.isMedia(it) }
            ) {
                throw IllegalStateException(
                    if (job.targetDurationMs > 0) {
                        "Ningún resultado de YouTube coincide con la duración de la canción"
                    } else {
                        "La descarga no produjo ningún archivo"
                    }
                )
            }

            // El audio viene de YouTube y hereda su título y su miniatura; aquí se
            // sustituyen por los datos reales del catálogo antes de moverlo al destino.
            spotify?.let { (tags, coverUrl) ->
                repository.updateProgress(
                    job.id, DownloadStatus.PROCESSING, 1f, 0, "Etiquetando…"
                )
                notify(job.id, named.title, "Etiquetando…", 1f)
                val audio = workspace.walkTopDown()
                    .firstOrNull { it.isFile && Id3Tagger.canTag(it) }
                if (audio != null) {
                    Id3Tagger.apply(audio, tags, Id3Tagger.downloadCover(coverUrl))
                }
            }

            repository.updateProgress(job.id, DownloadStatus.MOVING, 1f, 0, "Guardando en destino…")
            notify(job.id, named.title, "Guardando en destino…", 1f)
            throwIfCancelled(job.id)

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
                    finishedAt = System.currentTimeMillis(),
                    playlistIndex = playlistIndex,
                    playlistCount = playlistCount,
                    playlistItems = playlistItems.joinToString("\n")
                )
            )
            // La biblioteca se refresca sola para que lo recién bajado aparezca ya.
            MediaRepository.get(this).refresh()
            workspace.deleteRecursively()
        } catch (e: Exception) {
            val cancelled = cancelledJobs.remove(job.id)
            // Lo que dijo el motor manda sobre el mensaje de la excepción: "exited with
            // code 1" no le sirve a nadie, y el motivo real ya venía escrito en la salida.
            val detail = engineErrors.joinToString(SEPARATOR).takeIf { it.isNotBlank() }
            val message = detail ?: e.message ?: e.toString()
            // Room contiene el último porcentaje persistido por el callback. Partir de esa
            // fila evita que un error al 80 % vuelva a mostrar 0 % aunque el `.part` siga ahí.
            val stored = repository.get(job.id) ?: latest
            val nextAttempt = stored.attemptCount + 1
            val policy = desiredPolicy.value
            val retry = !cancelled &&
                nextAttempt <= policy.maxAutomaticRetries &&
                DownloadRetryPolicy.isRetryable(message)
            val useFallback = retry &&
                !stored.fallbackApplied &&
                stored.kind == DownloadKind.VIDEO &&
                DownloadRetryPolicy.isFormatFailure(message)

            if (!cancelled && !retry) {
                DownloadLog.record(
                    this,
                    "Falló «${stored.title.ifBlank { stored.url }}»" +
                        detail?.let { SEPARATOR + it }.orEmpty(),
                    e
                )
            }
            repository.update(
                stored.copy(
                    status = when {
                        cancelled -> DownloadStatus.CANCELLED
                        retry -> DownloadStatus.QUEUED
                        else -> DownloadStatus.FAILED
                    },
                    videoContainer = if (useFallback) VideoContainer.ORIGINAL else
                        stored.videoContainer,
                    fallbackApplied = stored.fallbackApplied || useFallback,
                    attemptCount = if (cancelled) stored.attemptCount else nextAttempt,
                    nextAttemptAt = if (retry) {
                        System.currentTimeMillis() + DownloadRetryPolicy.delayMs(nextAttempt)
                    } else {
                        0
                    },
                    statusLine = if (retry) {
                        "Reintento $nextAttempt/${policy.maxAutomaticRetries} programado" +
                            if (useFallback) " · formato compatible" else ""
                    } else {
                        stored.statusLine
                    },
                    errorMessage = if (cancelled) null else message,
                    finishedAt = if (retry) null else System.currentTimeMillis(),
                    // Se conserva por dónde iba: en una lista larga, saber que falló en la
                    // pista 31 de 40 es la mitad del diagnóstico.
                    playlistIndex = playlistIndex,
                    playlistCount = playlistCount,
                    playlistItems = playlistItems.joinToString("\n")
                )
            )
            // Cancelar significa descartar. En error se conserva el `.part` para que el
            // reintento automático o manual continúe desde el último byte confirmado.
            if (cancelled) workspace.deleteRecursively()
        } finally {
            activeProcesses.remove(job.id)
            publishActiveJobs()
        }
    }

    private fun cancelJob(jobId: Long) {
        val processId = activeProcesses[jobId] ?: return
        cancelledJobs.add(jobId)
        YtDlpEngine.cancel(processId)
    }

    private fun cancelAll() {
        activeProcesses.keys.toList().forEach(::cancelJob)
    }

    private fun throwIfCancelled(jobId: Long) {
        if (jobId in cancelledJobs) throw IllegalStateException("Descarga cancelada")
    }

    private fun publishActiveJobs() {
        activeIds.value = activeProcesses.keys.toSet()
    }

    // --------------------------------------------------------- notificación

    private fun buildNotification(
        title: String,
        text: String?,
        progress: Float,
        jobId: Long? = null
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            jobId?.hashCode() ?: 2,
            Intent(this, DownloadService::class.java)
                .setAction(if (jobId == null) ACTION_CANCEL_ALL else ACTION_CANCEL_JOB)
                .putExtra(EXTRA_JOB_ID, jobId ?: -1L),
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

    private fun notify(jobId: Long, title: String, text: String?, progress: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(title, text, progress, jobId))
        }
    }

    private fun notify(title: String, text: String?, progress: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(title, text, progress))
        }
    }

    override fun onDestroy() {
        instance = null
        worker?.cancel()
        scope.cancel()
        activeProcesses.clear()
        publishActiveJobs()
        blockReason.value = null
        effectiveSlots.value = 0
        super.onDestroy()
    }

    companion object {
        /** Separador de las líneas de error del motor. */
        private const val SEPARATOR = "\n"

        private const val ACTION_CANCEL_JOB = "com.vortex.player.action.CANCEL_DOWNLOAD"
        private const val ACTION_CANCEL_ALL = "com.vortex.player.action.CANCEL_ALL_DOWNLOADS"
        private const val EXTRA_JOB_ID = "job_id"
        private const val NOTIFICATION_ID = 7781
        private const val COORDINATOR_TICK_MS = 200L
        private const val CONDITION_TICK_MS = 15_000L
        private const val DEVICE_SNAPSHOT_INTERVAL_MS = 5_000L

        @Volatile
        private var instance: DownloadService? = null

        private val cancelledJobs = ConcurrentHashMap.newKeySet<Long>()
        private val activeIds = MutableStateFlow<Set<Long>>(emptySet())
        private val paused = MutableStateFlow(false)
        private val blockReason = MutableStateFlow<String?>(null)
        private val effectiveSlots = MutableStateFlow(0)

        /** Trabajos que tienen un proceso yt-dlp propio en este momento. */
        val activeJobIds: StateFlow<Set<Long>> = activeIds.asStateFlow()
        val policyBlockReason: StateFlow<String?> = blockReason.asStateFlow()
        val effectiveConcurrency: StateFlow<Int> = effectiveSlots.asStateFlow()

        /**
         * Pausa cooperativa: la transferencia actual termina y no se inicia la siguiente.
         * Evita fingir una pausa de red que yt-dlp Android no puede garantizar.
         */
        val queuePaused: StateFlow<Boolean> = paused.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(jobId: Long) {
            instance?.cancelJob(jobId)
        }

        fun cancelAll() {
            instance?.cancelAll()
        }

        fun setQueuePaused(context: Context, value: Boolean) {
            paused.value = value
            if (!value) start(context)
        }
    }
}
