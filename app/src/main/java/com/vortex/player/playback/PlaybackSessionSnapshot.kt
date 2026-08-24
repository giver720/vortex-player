package com.vortex.player.playback

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.vortex.player.data.MediaEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Copia durable de la sesión de reproducción.
 *
 * La cola puede contener miles de pistas, por eso se guarda en un archivo propio y no en
 * Preferences DataStore. [AtomicFile] evita que un cierre o reinicio a mitad de escritura
 * deje un JSON truncado que borre la sesión anterior.
 */
data class PlaybackSessionSnapshot(
    val entries: List<PersistedMediaEntry>,
    val currentIndex: Int,
    val positionMs: Long,
    val audioOnly: Boolean,
    val speed: Float,
    val repeat: RepeatMode,
    val shuffle: Boolean,
    val updatedAtMs: Long
) {
    fun normalized(): PlaybackSessionSnapshot {
        if (entries.isEmpty()) return copy(currentIndex = 0, positionMs = 0L)
        return copy(
            currentIndex = currentIndex.coerceIn(entries.indices),
            positionMs = positionMs.coerceAtLeast(0L),
            speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        )
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}

/** Modelo sin tipos Android para que el códec pueda probarse en la JVM. */
data class PersistedMediaEntry(
    val id: Long,
    val uri: String,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val folderPath: String,
    val folderName: String,
    val dateAddedSec: Long,
    val isVideo: Boolean,
    val artist: String?,
    val album: String?
)

internal fun MediaEntry.toPersisted(): PersistedMediaEntry = PersistedMediaEntry(
    id = id,
    uri = uri.toString(),
    title = title,
    displayName = displayName,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    width = width,
    height = height,
    folderPath = folderPath,
    folderName = folderName,
    dateAddedSec = dateAddedSec,
    isVideo = isVideo,
    artist = artist,
    album = album
)

internal fun PersistedMediaEntry.toMediaEntry(): MediaEntry = MediaEntry(
    id = id,
    uri = Uri.parse(uri),
    title = title,
    displayName = displayName,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    width = width,
    height = height,
    folderPath = folderPath,
    folderName = folderName,
    dateAddedSec = dateAddedSec,
    isVideo = isVideo,
    artist = artist,
    album = album
)

/** Códec versionado y tolerante a datos opcionales de versiones anteriores. */
object PlaybackSessionCodec {
    private const val VERSION = 1

    fun encode(snapshot: PlaybackSessionSnapshot): String {
        val value = snapshot.normalized()
        return JSONObject().apply {
            put("version", VERSION)
            put("currentIndex", value.currentIndex)
            put("positionMs", value.positionMs)
            put("audioOnly", value.audioOnly)
            put("speed", value.speed.toDouble())
            put("repeat", value.repeat.name)
            put("shuffle", value.shuffle)
            put("updatedAtMs", value.updatedAtMs)
            put("entries", JSONArray().apply {
                value.entries.forEach { entry -> put(entry.toJson()) }
            })
        }.toString()
    }

    fun decode(json: String): PlaybackSessionSnapshot? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("version", -1) !in 1..VERSION) return null
        val items = root.optJSONArray("entries") ?: return null
        val entries = buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toPersistedOrNull()?.let(::add)
            }
        }
        if (entries.isEmpty()) return null
        PlaybackSessionSnapshot(
            entries = entries,
            currentIndex = root.optInt("currentIndex", 0),
            positionMs = root.optLong("positionMs", 0L),
            audioOnly = root.optBoolean("audioOnly", false),
            speed = root.optDouble("speed", 1.0).toFloat(),
            repeat = root.optString("repeat")
                .let { runCatching { RepeatMode.valueOf(it) }.getOrDefault(RepeatMode.OFF) },
            shuffle = root.optBoolean("shuffle", false),
            updatedAtMs = root.optLong("updatedAtMs", 0L)
        ).normalized()
    }.getOrNull()

    private fun PersistedMediaEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("title", title)
        put("displayName", displayName)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("mimeType", mimeType)
        put("width", width)
        put("height", height)
        put("folderPath", folderPath)
        put("folderName", folderName)
        put("dateAddedSec", dateAddedSec)
        put("isVideo", isVideo)
        put("artist", artist ?: JSONObject.NULL)
        put("album", album ?: JSONObject.NULL)
    }

    private fun JSONObject.toPersistedOrNull(): PersistedMediaEntry? {
        val uri = optString("uri").takeIf { it.isNotBlank() } ?: return null
        return PersistedMediaEntry(
            id = optLong("id", 0L),
            uri = uri,
            title = optString("title"),
            displayName = optString("displayName"),
            durationMs = optLong("durationMs", 0L),
            sizeBytes = optLong("sizeBytes", 0L),
            mimeType = optString("mimeType"),
            width = optInt("width", 0),
            height = optInt("height", 0),
            folderPath = optString("folderPath"),
            folderName = optString("folderName"),
            dateAddedSec = optLong("dateAddedSec", 0L),
            isVideo = optBoolean("isVideo", false),
            artist = optNullableString("artist"),
            album = optNullableString("album")
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

class PlaybackSessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): PlaybackSessionSnapshot? = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            PlaybackSessionCodec.decode(reader.readText())
        }
    }.getOrNull()

    @Synchronized
    fun save(snapshot: PlaybackSessionSnapshot) {
        val stream = file.startWrite()
        try {
            stream.write(PlaybackSessionCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object {
        private const val FILE_NAME = "playback-session.json"
    }
}
