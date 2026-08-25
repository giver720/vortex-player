package com.vortex.player.network

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class NetworkSourceRepository private constructor(context: Context) {
    private val store = NetworkSourceStore(context.applicationContext)
    private val mutex = Mutex()
    private val _sources = MutableStateFlow(store.load())
    val sources: StateFlow<List<NetworkSource>> = _sources.asStateFlow()

    suspend fun recordOpened(draft: NetworkSourceDraft, nowMs: Long = System.currentTimeMillis()) {
        mutate { NetworkSourceLibraryPolicy.recordOpened(it, draft, nowMs) }
    }

    suspend fun saveFavorite(draft: NetworkSourceDraft, nowMs: Long = System.currentTimeMillis()) {
        mutate { NetworkSourceLibraryPolicy.saveFavorite(it, draft, nowMs) }
    }

    suspend fun toggleFavorite(url: String) {
        mutate { NetworkSourceLibraryPolicy.toggleFavorite(it, url) }
    }

    suspend fun remove(url: String) {
        mutate { NetworkSourceLibraryPolicy.remove(it, url) }
    }

    suspend fun clearRecent() {
        mutate(NetworkSourceLibraryPolicy::clearRecent)
    }

    private suspend fun mutate(block: (List<NetworkSource>) -> List<NetworkSource>) {
        mutex.withLock {
            val updated = block(_sources.value)
            withContext(Dispatchers.IO) { store.save(updated) }
            _sources.value = updated
        }
    }

    companion object {
        @Volatile private var instance: NetworkSourceRepository? = null

        fun get(context: Context): NetworkSourceRepository = instance ?: synchronized(this) {
            instance ?: NetworkSourceRepository(context).also { instance = it }
        }
    }
}

private class NetworkSourceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): List<NetworkSource> = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            NetworkSourceCodec.decode(reader.readText())
        }
    }.getOrDefault(emptyList())

    fun save(sources: List<NetworkSource>) {
        val stream = file.startWrite()
        try {
            stream.write(NetworkSourceCodec.encode(sources).toByteArray(Charsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object {
        private const val FILE_NAME = "network-sources.json"
    }
}
