package com.vortex.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.vortex.player.download.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    /** Candidatos listos; el coordinador aplica después el cupo de cada fuente. */
    @Query(
        """
        SELECT * FROM downloads
        WHERE status = 'QUEUED' AND nextAttemptAt <= :now
        ORDER BY priority DESC, createdAt ASC
        LIMIT 100
        """
    )
    suspend fun eligibleQueued(now: Long): List<DownloadEntity>

    @Query("SELECT MIN(nextAttemptAt) FROM downloads WHERE status = 'QUEUED' AND nextAttemptAt > :now")
    suspend fun nextRetryAt(now: Long): Long?

    @Query("SELECT COUNT(*) FROM downloads WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun activeCount(): Int

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    /** Una inserción Room para toda la lista evita una transacción de disco por pista. */
    @Insert
    suspend fun insertAll(entities: List<DownloadEntity>): List<Long>

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query(
        """
        UPDATE downloads
        SET status = :status, progress = :progress, etaSeconds = :eta, statusLine = :line
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        progress: Float,
        eta: Long,
        line: String
    )

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("UPDATE downloads SET priority = :priority WHERE id = :id")
    suspend fun updatePriority(id: Long, priority: Int)

    @Query("SELECT COALESCE(MAX(priority), 0) FROM downloads WHERE status = 'QUEUED'")
    suspend fun maxQueuedPriority(): Int

    @Query("SELECT COALESCE(MIN(priority), 0) FROM downloads WHERE status = 'QUEUED'")
    suspend fun minQueuedPriority(): Int

    @Query(
        "SELECT COUNT(*) FROM downloads " +
            "WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED') AND url = :url"
    )
    suspend fun existingUrlCount(url: String): Int

    /**
     * Avance dentro de la lista. Va aparte de [updateProgress] porque cambia una vez por
     * pista y no varias veces por segundo, y porque escribir la lista de nombres a ese
     * ritmo castigaría el disco sin que se notara en pantalla.
     */
    @Query(
        """
        UPDATE downloads
        SET playlistIndex = :index, playlistCount = :count, playlistItems = :items
        WHERE id = :id
        """
    )
    suspend fun updatePlaylistPosition(id: Long, index: Int, count: Int, items: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun clearFinished()

    /** Vacía la cola entera, historial incluido. */
    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    /** Quita lo que aún no ha terminado y deja intacto el historial. */
    @Query("DELETE FROM downloads WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun clearPending()

    @Query("SELECT * FROM downloads WHERE status IN ('FAILED','CANCELLED')")
    suspend fun failedJobs(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('FAILED','CANCELLED')")
    fun observeFailedCount(): Flow<Int>

    /** Identificadores de pista que ya se descargaron con éxito alguna vez. */
    @Query("SELECT sourceId FROM downloads WHERE status = 'COMPLETED' AND sourceId IS NOT NULL")
    suspend fun completedSourceIds(): List<String>

    /**
     * Si el proceso muere a mitad, las descargas quedan colgadas en un estado activo.
     * Al arrancar se rescatan devolviéndolas a la cola.
     */
    @Query(
        """
        UPDATE downloads
        SET status = 'QUEUED', statusLine = 'Preparada para reanudar', nextAttemptAt = 0
        WHERE status IN ('FETCHING','DOWNLOADING','PROCESSING','MOVING')
        """
    )
    suspend fun requeueInterrupted()
}
