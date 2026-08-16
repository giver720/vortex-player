package com.vortex.player.download

import android.content.Context
import com.vortex.player.data.db.DownloadDao
import com.vortex.player.data.db.DownloadEntity
import com.vortex.player.data.db.VortexDatabase
import com.vortex.player.spotify.SpotifyCollection
import com.vortex.player.spotify.SpotifyJobs
import com.vortex.player.spotify.SpotifyTrack
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
            embedMetadata = request.embedMetadata,
            sponsorMode = request.sponsor.mode,
            sponsorCategories = request.sponsor.categoriesCsv
        )
    )

    /**
     * Expande una colección de Spotify en una entrada de cola por canción.
     *
     * Se hace así, y no como un único trabajo, para que el progreso se vea tema a tema y
     * para poder reintentar sólo el que falle: perder una playlist de ochenta canciones
     * porque la sesenta no tenía resultado sería inaceptable.
     */
    suspend fun enqueueSpotify(
        collection: SpotifyCollection,
        base: DownloadRequest
    ): Int = enqueueSpotifyTracks(collection.tracks, collection.folderName, base)

    /**
     * Encola un bloque de canciones. Se llama una vez por página al resolver una lista
     * larga, de modo que la primera descarga empieza mientras aún se está leyendo el
     * resto: en una playlist de trescientas, esperar a tenerlas todas serían minutos
     * de pantalla quieta.
     */
    suspend fun enqueueSpotifyTracks(
        tracks: List<SpotifyTrack>,
        folder: String?,
        base: DownloadRequest
    ): Int {
        tracks.forEach { track ->
            dao.insert(
                DownloadEntity(
                    url = base.url,
                    title = "${track.artist} - ${track.title}",
                    uploader = track.artist,
                    thumbnailUrl = track.coverUrl,
                    // Spotify siempre baja como audio: pedir vídeo aquí no tendría sentido.
                    kind = DownloadKind.AUDIO,
                    audioCodec = base.audioCodec,
                    audioBitrate = base.audioBitrate,
                    playlist = false,
                    embedThumbnail = false,
                    embedMetadata = false,
                    sourceId = track.id,
                    searchQuery = SpotifyJobs.searchQuery(track),
                    targetDurationMs = track.durationMs,
                    tagsJson = SpotifyJobs.tagsJson(track),
                    playlistFolder = folder,
                    sponsorMode = base.sponsor.mode,
                    sponsorCategories = base.sponsor.categoriesCsv
                )
            )
        }
        return tracks.size
    }

    suspend fun completedSourceIds(): Set<String> = dao.completedSourceIds().toSet()

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

    suspend fun updatePlaylistPosition(id: Long, index: Int, count: Int, items: List<String>) =
        dao.updatePlaylistPosition(id, index, count, items.joinToString("\n"))

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
                finishedAt = null,
                // Un reintento empieza la lista de cero: conservar el contador enseñaría
                // "17/24" y las pistas de la vez anterior sobre una descarga recién nacida.
                playlistIndex = 0,
                playlistCount = 0,
                playlistItems = ""
            )
        )
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun clearFinished() = dao.clearFinished()

    suspend fun clearAll() = dao.clearAll()

    suspend fun clearPending() = dao.clearPending()

    val failedCount: Flow<Int> = dao.observeFailedCount()

    /** Devuelve a la cola todo lo que falló o se canceló, conservando sus opciones. */
    suspend fun retryAllFailed(): Int {
        val failed = dao.failedJobs()
        failed.forEach { entity ->
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
        return failed.size
    }

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
        embedMetadata = embedMetadata,
        sponsor = SponsorSettings(
            mode = sponsorMode,
            categories = SponsorCategory.parse(sponsorCategories)
        )
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
