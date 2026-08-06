package com.vortex.player.data

import android.content.Context
import com.vortex.player.data.db.MediaStateDao
import com.vortex.player.data.db.MediaStateEntity
import com.vortex.player.data.db.PlaylistDao
import com.vortex.player.data.db.PlaylistEntity
import com.vortex.player.data.db.PlaylistItemEntity
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
     * Jerarquía de carpetas. Se calcula una sola vez por instantánea de biblioteca:
     * recorrer miles de rutas en cada recomposición se notaría al desplazar la lista.
     */
    val folderTree: FolderNode by lazy { buildFolderTree(entries) }

    private val byUri: Map<String, MediaEntry> by lazy { entries.associateBy { it.uri.toString() } }

    fun entryFor(uri: String): MediaEntry? = byUri[uri]

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

/** Una lista de reproducción junto a su contenido, que es como la consume la interfaz. */
data class PlaylistWithItems(
    val playlist: PlaylistEntity,
    val items: List<PlaylistItemEntity>
)

class MediaRepository(
    private val context: Context,
    private val dao: MediaStateDao,
    private val playlistDao: PlaylistDao,
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

    val playlists: StateFlow<List<PlaylistWithItems>> =
        combine(
            playlistDao.observePlaylists(),
            playlistDao.observeAllItems()
        ) { lists, items ->
            val grouped = items.groupBy { it.playlistId }
            lists.map { list ->
                PlaylistWithItems(list, grouped[list.id].orEmpty().sortedBy { it.position })
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        scope.launch { scanned.value = scanner.scanAll() }
    }

    // ------------------------------------------------------------ listas

    fun createPlaylist(name: String, initial: List<MediaEntry> = emptyList()) {
        scope.launch {
            val id = playlistDao.insertPlaylist(PlaylistEntity(name = name.trim()))
            if (initial.isNotEmpty()) {
                playlistDao.append(id, initial.map { it.uri.toString() to it.title })
            }
        }
    }

    fun addToPlaylist(playlistId: Long, entries: List<MediaEntry>) {
        scope.launch {
            playlistDao.append(playlistId, entries.map { it.uri.toString() to it.title })
        }
    }

    fun removeFromPlaylist(playlistId: Long, uri: String) {
        scope.launch { playlistDao.removeItem(playlistId, uri) }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch { playlistDao.deletePlaylist(playlistId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        scope.launch { playlistDao.rename(playlistId, name.trim()) }
    }

    fun reorderPlaylist(playlistId: Long, orderedUris: List<String>) {
        scope.launch { playlistDao.reorder(playlistId, orderedUris) }
    }

    fun setFavorite(uri: String, favorite: Boolean) {
        scope.launch {
            val current = dao.get(uri)
            if (current == null) {
                dao.upsert(MediaStateEntity(uri = uri, isFavorite = favorite))
            } else if (current.isFavorite != favorite) {
                dao.upsert(current.copy(isFavorite = favorite))
            }
        }
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
                    VortexDatabase.get(context).mediaStateDao(),
                    VortexDatabase.get(context).playlistDao()
                ).also { instance = it }
            }
    }
}
