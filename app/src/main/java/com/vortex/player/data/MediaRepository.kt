package com.vortex.player.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.vortex.player.data.db.MediaStateDao
import com.vortex.player.data.db.MediaStateEntity
import com.vortex.player.data.db.PlaylistDao
import com.vortex.player.data.db.PlaylistEntity
import com.vortex.player.data.db.PlaylistItemEntity
import com.vortex.player.data.db.PlaylistItemDraft
import com.vortex.player.data.db.VortexDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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

fun MediaEntry.toPlaylistDraft(): PlaylistItemDraft = PlaylistItemDraft(
    uri = uri.toString(),
    title = title.ifBlank { displayName },
    artist = artist,
    album = album,
    durationMs = durationMs,
    isVideo = isVideo
)

@OptIn(FlowPreview::class)
class MediaRepository(
    private val context: Context,
    private val dao: MediaStateDao,
    private val playlistDao: PlaylistDao,
    private val scanner: MediaScanner = MediaScanner(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanned = MutableStateFlow<List<MediaEntry>?>(null)
    private val refreshMutex = Mutex()

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

    /** Un reescaneo está en curso; la interfaz lo usa para el indicador de refresco. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _lastScan = MutableStateFlow(MediaScanReport())
    val lastScan: StateFlow<MediaScanReport> = _lastScan

    fun refresh() {
        scope.launch {
            if (!refreshMutex.tryLock()) return@launch
            _refreshing.value = true
            try {
                val result = scanner.scanAll(scanned.value.orEmpty())
                scanned.value = result.entries
                _lastScan.value = result.report
            } finally {
                _refreshing.value = false
                refreshMutex.unlock()
            }
        }
    }

    /**
     * Vigila la mediateca del sistema y reescanea cuando cambia.
     *
     * Android avisa por cada fichero, así que una descarga de cincuenta canciones
     * dispararía cincuenta reescaneos completos. El antirrebote agrupa la ráfaga en uno
     * solo, un segundo y medio después del último aviso. Esto cubre a la vez lo que se
     * descarga desde Vórtex, lo que llega por otras apps y lo que se borra desde el
     * explorador de archivos.
     */
    private fun observeMediaStore() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Sin traza aquí: durante una descarga de cien canciones esto se dispara
                // cientos de veces y ahogaría el log. La traza va tras el antirrebote.
                changes.tryEmit(Unit)
            }
        }
        // Se observa la raíz del proveedor y no `…/external/video/media`, porque Android
        // notifica con el nombre real del volumen (`external_primary`), que no es
        // descendiente de `external`: registrándolo ahí no llegaba ni un solo aviso.
        context.contentResolver.registerContentObserver(
            MediaStore.AUTHORITY_URI, true, observer
        )

        scope.launch {
            changes.debounce(1_500).collect {
                if (LibraryPreferences.observe(context).first().autoRefresh) {
                    Log.d(TAG, "Reescaneando la biblioteca por cambio en MediaStore")
                    refresh()
                }
            }
        }
    }

    private val changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        observeMediaStore()
    }

    // ------------------------------------------------------------ listas

    fun createPlaylist(
        name: String,
        initial: List<MediaEntry> = emptyList(),
        source: String = "LOCAL"
    ) {
        scope.launch {
            createPlaylistNow(name, initial, source)
        }
    }

    suspend fun createPlaylistNow(
        name: String,
        initial: List<MediaEntry> = emptyList(),
        source: String = "LOCAL"
    ): Long {
        val id = playlistDao.insertPlaylist(PlaylistEntity(name = name.trim(), source = source))
        if (initial.isNotEmpty()) {
            playlistDao.append(id, initial.map(MediaEntry::toPlaylistDraft))
        }
        return id
    }

    suspend fun createPlaylistFromDrafts(
        name: String,
        entries: List<PlaylistItemDraft>,
        source: String,
        description: String = ""
    ): Long {
        val id = playlistDao.insertPlaylist(
            PlaylistEntity(name = name.trim(), description = description.trim(), source = source)
        )
        if (entries.isNotEmpty()) {
            playlistDao.append(id, entries)
        }
        return id
    }

    suspend fun createSmartPlaylist(name: String, rule: SmartPlaylistRule): Long =
        playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name.trim(),
                description = rule.description,
                source = "SMART",
                smartRule = rule.name
            )
        )

    fun addToPlaylist(playlistId: Long, entries: List<MediaEntry>) {
        scope.launch {
            playlistDao.append(playlistId, entries.map(MediaEntry::toPlaylistDraft))
        }
    }

    fun removeFromPlaylist(playlistId: Long, itemIds: List<Long>) {
        scope.launch { playlistDao.removeAndTouch(playlistId, itemIds) }
    }

    fun removeFromPlaylist(playlistId: Long, uri: String) {
        val ids = playlists.value.firstOrNull { it.playlist.id == playlistId }
            ?.items?.filter { it.uri == uri }?.map { it.id }.orEmpty()
        removeFromPlaylist(playlistId, ids)
    }

    fun restorePlaylistItem(playlistId: Long, item: PlaylistItemEntity) {
        scope.launch {
            playlistDao.restore(
                playlistId,
                PlaylistItemDraft(
                    item.uri, item.title, item.artist, item.album, item.durationMs, item.isVideo
                ),
                item.position
            )
        }
    }

    fun restorePlaylistItems(playlistId: Long, items: List<PlaylistItemEntity>) {
        scope.launch {
            items.sortedBy { it.position }.forEach { item ->
                playlistDao.restore(
                    playlistId,
                    PlaylistItemDraft(
                        item.uri, item.title, item.artist, item.album, item.durationMs, item.isVideo
                    ),
                    item.position
                )
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch { playlistDao.deletePlaylist(playlistId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        scope.launch { playlistDao.rename(playlistId, name.trim()) }
    }

    fun updatePlaylistDetails(
        playlistId: Long,
        name: String,
        description: String,
        coverUri: String?
    ) {
        scope.launch {
            playlistDao.updateDetails(playlistId, name.trim(), description.trim(), coverUri)
        }
    }

    fun reorderPlaylist(playlistId: Long, orderedItemIds: List<Long>) {
        scope.launch { playlistDao.reorder(playlistId, orderedItemIds) }
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
        private const val TAG = "MediaRepository"

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
