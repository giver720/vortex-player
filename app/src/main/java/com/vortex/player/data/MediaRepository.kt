package com.vortex.player.data

import android.content.Context
import com.vortex.player.data.db.MediaStateDao
import com.vortex.player.data.db.MediaStateEntity
import com.vortex.player.data.db.VortexDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Instantánea completa de la biblioteca ya cruzada con el historial local. */
data class LibraryState(
    val loading: Boolean = true,
    val entries: List<MediaEntry> = emptyList(),
    val states: Map<String, MediaStateEntity> = emptyMap()
) {
    val videos: List<MediaEntry> get() = entries.filter { it.isVideo }
    val audios: List<MediaEntry> get() = entries.filter { !it.isVideo }

    val folders: List<MediaFolder>
        get() = entries
            .groupBy { it.folderPath }
            .map { (path, items) ->
                MediaFolder(path, items.first().folderName, items)
            }
            .sortedByDescending { it.entries.size }

    /**
     * "Continuar viendo": lo empezado y no terminado, lo más reciente primero.
     * Es la lista que más se usa en un reproductor y la que VLC esconde.
     */
    val continueWatching: List<Pair<MediaEntry, MediaStateEntity>>
        get() = entries.mapNotNull { entry ->
            val state = states[entry.uri.toString()] ?: return@mapNotNull null
            if (state.positionMs <= 10_000 || state.isFinished) null else entry to state
        }.sortedByDescending { it.second.lastPlayedAt }

    val favorites: List<MediaEntry>
        get() = entries.filter { states[it.uri.toString()]?.isFavorite == true }

    fun stateFor(entry: MediaEntry): MediaStateEntity? = states[entry.uri.toString()]
}

class MediaRepository(
    private val context: Context,
    private val dao: MediaStateDao,
    private val scanner: MediaScanner = MediaScanner(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanned = MutableStateFlow<List<MediaEntry>?>(null)

    val library: StateFlow<LibraryState> =
        combine(scanned, dao.observeAll()) { entries, states ->
            LibraryState(
                loading = entries == null,
                entries = entries.orEmpty(),
                states = states.associateBy { it.uri }
            )
        }.stateIn(scope, SharingStarted.Eagerly, LibraryState())

    fun refresh() {
        scope.launch { scanned.value = scanner.scanAll() }
    }

    fun savePosition(uri: String, positionMs: Long, durationMs: Long) {
        scope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.get(uri)
            if (existing == null) {
                dao.upsert(
                    MediaStateEntity(
                        uri = uri,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        lastPlayedAt = now,
                        playCount = 1
                    )
                )
            } else {
                dao.updatePosition(uri, positionMs, durationMs, now)
            }
        }
    }

    suspend fun stateOf(uri: String): MediaStateEntity? = dao.get(uri)

    fun toggleFavorite(uri: String) {
        scope.launch {
            if (dao.get(uri) == null) {
                dao.upsert(MediaStateEntity(uri = uri, isFavorite = true))
            } else {
                dao.toggleFavorite(uri)
            }
        }
    }

    fun savePreferences(
        uri: String,
        speed: Float? = null,
        audioOnly: Boolean? = null,
        audioTrack: String? = null,
        subtitleTrack: String? = null
    ) {
        scope.launch {
            val current = dao.get(uri) ?: MediaStateEntity(uri = uri)
            dao.upsert(
                current.copy(
                    preferredSpeed = speed ?: current.preferredSpeed,
                    audioOnly = audioOnly ?: current.audioOnly,
                    preferredAudioTrack = audioTrack ?: current.preferredAudioTrack,
                    preferredSubtitleTrack = subtitleTrack ?: current.preferredSubtitleTrack
                )
            )
        }
    }

    fun clearHistory() {
        scope.launch { dao.clearHistory() }
    }

    companion object {
        @Volatile
        private var instance: MediaRepository? = null

        fun get(context: Context): MediaRepository =
            instance ?: synchronized(this) {
                instance ?: MediaRepository(
                    context.applicationContext,
                    VortexDatabase.get(context).mediaStateDao()
                ).also { instance = it }
            }
    }
}
