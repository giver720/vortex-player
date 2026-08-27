package com.vortex.player.download

import android.content.Context
import android.util.AtomicFile
import android.webkit.CookieManager
import java.io.File

/** Sesión de YouTube privada de Vortex. Nunca se exporta, registra ni incluye en copias de seguridad. */
object YoutubeAuthStore {
    private const val DIRECTORY = "youtube-auth"
    private const val FILE_NAME = "cookies.txt"
    private val youtubeUrls = listOf(
        "https://www.youtube.com",
        "https://youtube.com",
        "https://m.youtube.com",
        "https://music.youtube.com"
    )

    fun capture(context: Context, manager: CookieManager = CookieManager.getInstance()): Boolean =
        runCatching {
            manager.flush()
            val headers = youtubeUrls.mapNotNull { url ->
                manager.getCookie(url)?.takeIf(String::isNotBlank)?.let { url to it }
            }
            val contents = YoutubeCookieJarCodec.encode(headers)
            if (!YoutubeCookieJarCodec.isAuthenticated(contents)) return@runCatching false
            val directory = File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }
            val atomicFile = AtomicFile(directory.resolve(FILE_NAME))
            val stream = atomicFile.startWrite()
            try {
                stream.write(contents.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (failure: Throwable) {
                atomicFile.failWrite(stream)
                throw failure
            }
            true
        }.getOrDefault(false)

    fun cookieFileOrNull(context: Context): File? {
        val file = cookieFile(context)
        if (!file.isFile) return null
        return runCatching {
            file.takeIf {
                YoutubeCookieJarCodec.isAuthenticated(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    fun hasSession(context: Context): Boolean = cookieFileOrNull(context) != null

    fun clear(context: Context) {
        cookieFile(context).delete()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    private fun cookieFile(context: Context): File =
        File(File(context.noBackupFilesDir, DIRECTORY), FILE_NAME)
}

/** Conversión pura del encabezado WebView al formato Netscape que entiende yt-dlp. */
object YoutubeCookieJarCodec {
    private val validName = Regex("^[A-Za-z0-9_.-]+$")

    fun encode(headers: List<Pair<String, String>>): String {
        val cookies = linkedMapOf<String, String>()
        headers.forEach { (_, header) ->
            header.split(';').forEach { raw ->
                val separator = raw.indexOf('=')
                if (separator <= 0) return@forEach
                val name = raw.substring(0, separator).trim()
                val value = raw.substring(separator + 1).trim()
                if (name.matches(validName) && '\t' !in value && '\n' !in value && '\r' !in value) {
                    cookies[name] = value
                }
            }
        }
        return buildString {
            appendLine("# Netscape HTTP Cookie File")
            appendLine("# Generado internamente por Vortex; no compartir.")
            cookies.forEach { (name, value) ->
                append(".youtube.com\tTRUE\t/\tTRUE\t0\t")
                append(name).append('\t').append(value).append('\n')
            }
        }
    }

    fun isAuthenticated(contents: String): Boolean {
        val names = contents.lineSequence().mapNotNull { line ->
            line.split('\t').takeIf { it.size >= 7 }?.get(5)
        }.toSet()
        val hasSid = names.any { it in setOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID") }
        return "LOGIN_INFO" in names && hasSid
    }
}
