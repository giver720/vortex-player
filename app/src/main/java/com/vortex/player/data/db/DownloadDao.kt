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

    /** Siguiente trabajo pendiente, en orden de llegada. */
    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun activeCount(): Int

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

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
        UPDATE downloads SET status = 'QUEUED', progress = 0, statusLine = ''
        WHERE status IN ('FETCHING','DOWNLOADING','PROCESSING','MOVING')
        """
    )
    suspend fun requeueInterrupted()
}
