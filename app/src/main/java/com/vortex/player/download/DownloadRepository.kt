package com.vortex.player.download

import android.content.Context
import com.vortex.player.data.db.DownloadDao
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.data.db.VortexDatabase
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val dao: DownloadDao) {

    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun enqueue(request: DownloadRequest): Long = dao.insert(
        DownloadEntity(
            url = request.url.trim(),
            kind = request.kind,
            videoQuality = request.videoQuality,
            audioCodec = request.audioCodec,
            audioBitrate = request.audioBitrate,
            playlist = request.playlist,
            embedThumbnail = request.embedThumbnail,
            embedSubtitles = request.embedSubtitles,
            embedMetadata = request.embedMetadata
        )
    )

    suspend fun nextQueued(): DownloadEntity? = dao.nextQueued()

    suspend fun get(id: Long): DownloadEntity? = dao.get(id)

    suspend fun update(entity: DownloadEntity) = dao.update(entity)

    suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        progress: Float,
        eta: Long,
        line: String
    ) = dao.updateProgress(id, status, progress, eta, line)

    suspend fun updateStatus(id: Long, status: DownloadStatus) = dao.updateStatus(id, status)

    suspend fun requeueInterrupted() = dao.requeueInterrupted()

    suspend fun activeCount(): Int = dao.activeCount()

    /** Reintentar es simplemente devolver el trabajo a la cola conservando sus opciones. */
    suspend fun retry(id: Long) {
        val entity = dao.get(id) ?: return
        dao.update(
            entity.copy(
                status = DownloadStatus.QUEUED,
                progress = 0f,
                etaSeconds = -1,
                statusLine = "",
                errorMessage = null,
                finishedAt = null
            )
        )
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun clearFinished() = dao.clearFinished()

    /** Convierte una fila guardada de vuelta en la petición que la originó. */
    fun DownloadEntity.toRequest(): DownloadRequest = DownloadRequest(
        url = url,
        kind = kind,
        videoQuality = videoQuality,
        audioCodec = audioCodec,
        audioBitrate = audioBitrate,
        playlist = playlist,
        embedThumbnail = embedThumbnail,
        embedSubtitles = embedSubtitles,
        embedMetadata = embedMetadata
    )

    companion object {
        @Volatile
        private var instance: DownloadRepository? = null

        fun get(context: Context): DownloadRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadRepository(
                    VortexDatabase.get(context).downloadDao()
                ).also { instance = it }
            }
    }
}
