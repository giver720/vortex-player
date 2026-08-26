package com.vortex.player.playback

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class PlaybackEventType {
    APP_START,
    OPEN,
    STATE,
    FIRST_FRAME,
    SURFACE_ATTACH,
    SURFACE_DETACH,
    SCALE,
    DECODER_FALLBACK,
    RECOVERY,
    ERROR,
    RELEASE,
    CRASH
}

/** Evento deliberadamente limitado a datos técnicos y sin rutas, títulos ni URLs. */
data class PlaybackEvent(
    val type: PlaybackEventType,
    val wallTimeMs: Long = System.currentTimeMillis(),
    val mediaIdentity: String? = null,
    val source: String? = null,
    val decoder: String? = null,
    val health: String? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val detailCode: String? = null
)

data class PlaybackLogSummary(
    val eventCount: Int,
    val bytes: Long,
    val crashDetected: Boolean
)

/** Códec puro para poder comprobar en JVM que el registro nunca expone el identificador real. */
object PlaybackEventCodec {
    fun encode(event: PlaybackEvent): String = JSONObject().apply {
        put("timeMs", event.wallTimeMs)
        put("type", event.type.name)
        event.mediaIdentity?.let { put("mediaKey", mediaHash(it)) }
        event.source?.let { put("source", safeToken(it)) }
        event.decoder?.let { put("decoder", safeToken(it)) }
        event.health?.let { put("health", safeToken(it)) }
        event.positionMs?.let { put("positionMs", it.coerceAtLeast(0L)) }
        event.durationMs?.let { put("durationMs", it.coerceAtLeast(0L)) }
        event.codec?.let { put("codec", safeToken(it)) }
        event.width?.takeIf { it > 0 }?.let { put("width", it) }
        event.height?.takeIf { it > 0 }?.let { put("height", it) }
        event.detailCode?.let { put("detail", safeToken(it)) }
    }.toString()

    fun mediaHash(identity: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Lista blanca: evita que mensajes nativos incorporen accidentalmente rutas o consultas. */
    internal fun safeToken(value: String): String = value
        .uppercase()
        .replace(Regex("[^A-Z0-9_ .:/-]"), "_")
        .replace(Regex("HTTPS?://\\S+"), "REDACTED")
        .take(MAX_TOKEN_LENGTH)

    private const val MAX_TOKEN_LENGTH = 160
}

/** Registro local rotativo. Nunca lanza ni puede interrumpir la reproducción. */
object PlaybackEventLog {
    private const val DIRECTORY = "playback-diagnostics"
    private const val EXPORT_DIRECTORY = "diagnostics"
    private const val FILE_PREFIX = "playback-events"
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val FILE_COUNT = 3
    private const val CRASH_MARKER = "last-crash.marker"
    private val lock = Any()

    fun install(context: Context) {
        PlaybackCrashHandler.install(context.applicationContext)
        record(context, PlaybackEvent(PlaybackEventType.APP_START, detailCode = "PROCESS_START"))
    }

    fun record(context: Context, event: PlaybackEvent) {
        runCatching {
            val line = PlaybackEventCodec.encode(event) + "\n"
            synchronized(lock) {
                val directory = directory(context)
                directory.mkdirs()
                val current = eventFile(directory, 0)
                if (current.length() + line.toByteArray().size > MAX_FILE_BYTES) {
                    rotate(directory)
                }
                eventFile(directory, 0).appendText(line, Charsets.UTF_8)
            }
        }
    }

    fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        val stackCode = buildString {
            append(error.javaClass.simpleName)
            append('@').append(thread.name)
            error.stackTrace.take(12).forEach { frame ->
                append('|').append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append(':').append(frame.lineNumber)
            }
        }
        record(context, PlaybackEvent(PlaybackEventType.CRASH, detailCode = stackCode))
        runCatching {
            synchronized(lock) {
                directory(context).apply { mkdirs() }
                    .resolve(CRASH_MARKER)
                    .writeText(System.currentTimeMillis().toString())
            }
        }
    }

    fun summary(context: Context): PlaybackLogSummary = runCatching {
        synchronized(lock) {
            val directory = directory(context)
            val files = eventFiles(directory)
            PlaybackLogSummary(
                eventCount = files.sumOf { file -> file.useLines { it.count() } },
                bytes = files.sumOf(File::length),
                crashDetected = directory.resolve(CRASH_MARKER).exists()
            )
        }
    }.getOrDefault(PlaybackLogSummary(0, 0L, false))

    fun exportFile(context: Context): File = synchronized(lock) {
        val outputDirectory = File(context.filesDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val output = outputDirectory.resolve("vortex-diagnostico.txt")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Vortex ${com.vortex.player.BuildConfig.VERSION_NAME}")
            writer.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            writer.appendLine("ABI ${Build.SUPPORTED_ABIS.joinToString()}")
            writer.appendLine("Registro local censurado; no contiene rutas, títulos ni URLs.")
            writer.appendLine()
            eventFiles(directory(context)).asReversed().forEach { file ->
                file.forEachLine(Charsets.UTF_8) { writer.appendLine(it) }
            }
        }
        output
    }

    fun clear(context: Context) {
        runCatching {
            synchronized(lock) {
                val directory = directory(context)
                eventFiles(directory).forEach(File::delete)
                directory.resolve(CRASH_MARKER).delete()
                File(context.filesDir, EXPORT_DIRECTORY)
                    .resolve("vortex-diagnostico.txt")
                    .delete()
            }
        }
    }

    private fun rotate(directory: File) {
        eventFile(directory, FILE_COUNT - 1).delete()
        for (index in FILE_COUNT - 2 downTo 0) {
            val source = eventFile(directory, index)
            if (source.exists()) source.renameTo(eventFile(directory, index + 1))
        }
    }

    private fun eventFiles(directory: File): List<File> =
        (0 until FILE_COUNT).map { eventFile(directory, it) }.filter(File::exists)

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)
    private fun eventFile(directory: File, index: Int) = directory.resolve("$FILE_PREFIX-$index.jsonl")
}

private object PlaybackCrashHandler {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                PlaybackEventLog.recordCrash(context, thread, error)
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
            installed = true
        }
    }
}
