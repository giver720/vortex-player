package com.vortex.player.subtitle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SubtitleLanguageChoice(val label: String, val apiValue: String) {
    SPANISH("ESPAÑOL", "es"),
    ENGLISH("ENGLISH", "en"),
    BOTH("ES + EN", "es,en")
}

enum class OnlineSubtitleTarget { PRIMARY, SECONDARY }

data class OnlineSubtitleResult(
    val fileId: Long,
    val language: String,
    val release: String,
    val fileName: String,
    val hearingImpaired: Boolean,
    val downloadCount: Int,
    val machineTranslated: Boolean
)

data class OnlineSubtitleDownload(
    val fileName: String,
    val bytes: ByteArray,
    val remaining: Int?
)

data class OnlineSubtitleUiState(
    val configured: Boolean,
    val apiKeyDraft: String,
    val query: String,
    val language: SubtitleLanguageChoice,
    val searching: Boolean,
    val downloadingFileId: Long?,
    val results: List<OnlineSubtitleResult>
)

/** Conversión aislada del JSON para poder verificar cambios del API con pruebas locales. */
object OpenSubtitlesParser {
    fun parseSearch(json: JSONObject): List<OnlineSubtitleResult> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
                val files = attributes.optJSONArray("files") ?: continue
                val file = files.optJSONObject(0) ?: continue
                val fileId = file.optLong("file_id", -1L)
                if (fileId <= 0L) continue
                val fileName = file.optString("file_name").ifBlank { "subtitulo-$fileId.srt" }
                add(
                    OnlineSubtitleResult(
                        fileId = fileId,
                        language = attributes.optString("language").ifBlank { "—" },
                        release = attributes.optString("release").ifBlank { fileName },
                        fileName = fileName,
                        hearingImpaired = attributes.optBoolean("hearing_impaired", false),
                        downloadCount = attributes.optInt("download_count", 0),
                        machineTranslated = attributes.optBoolean("machine_translated", false) ||
                            attributes.optBoolean("ai_translated", false)
                    )
                )
            }
        }
    }

    fun parseDownload(json: JSONObject): Triple<String, String, Int?> {
        val link = json.optString("link")
        require(link.isNotBlank()) { "OpenSubtitles no devolvió el enlace temporal" }
        val fileName = json.optString("file_name").ifBlank { "subtitulo.srt" }
        val remaining = if (json.has("remaining")) json.optInt("remaining") else null
        return Triple(link, fileName, remaining)
    }
}

class OpenSubtitlesClient(private val appVersion: String) {
    suspend fun search(
        apiKey: String,
        query: String,
        languages: SubtitleLanguageChoice
    ): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Escribe el título de la película o episodio" }
        val url = "$API_ROOT/subtitles?query=${encode(query.trim())}" +
            "&languages=${encode(languages.apiValue)}&order_by=download_count&order_direction=desc"
        val response = requestJson(url, "GET", apiKey)
        OpenSubtitlesParser.parseSearch(response).take(MAX_RESULTS)
    }

    suspend fun download(apiKey: String, fileId: Long): OnlineSubtitleDownload =
        withContext(Dispatchers.IO) {
            require(fileId > 0L) { "Identificador de subtítulo inválido" }
            val body = JSONObject().put("file_id", fileId).toString()
                .toByteArray(StandardCharsets.UTF_8)
            val response = requestJson("$API_ROOT/download", "POST", apiKey, body)
            val (link, name, remaining) = OpenSubtitlesParser.parseDownload(response)
            val url = URL(link)
            require(url.protocol.equals("https", ignoreCase = true)) {
                "OpenSubtitles devolvió un enlace de descarga no seguro"
            }
            val bytes = readSubtitle(url)
            OnlineSubtitleDownload(safeName(name), bytes, remaining)
        }

    private fun requestJson(
        url: String,
        method: String,
        apiKey: String,
        body: ByteArray? = null
    ): JSONObject {
        require(apiKey.isNotBlank()) { "Configura primero tu API key de OpenSubtitles" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Api-Key", apiKey.trim())
            setRequestProperty("User-Agent", "Vortex v$appVersion")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            body?.let { connection.outputStream.use { output -> output.write(it) } }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw apiError(status, text)
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readSubtitle(url: URL, redirectCount: Int = 0): ByteArray {
        require(url.protocol.equals("https", ignoreCase = true)) {
            "OpenSubtitles redirigió a una descarga no segura"
        }
        require(redirectCount <= MAX_REDIRECTS) { "OpenSubtitles devolvió demasiadas redirecciones" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Vortex v$appVersion")
            setRequestProperty("Accept", "text/plain, application/octet-stream, */*")
        }
        return try {
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirección de descarga sin destino")
                return readSubtitle(URL(url, location), redirectCount + 1)
            }
            if (status !in 200..299) throw apiError(status, "")
            connection.contentLengthLong.takeIf { it >= 0 }?.let { length ->
                require(length <= MAX_SUBTITLE_BYTES) { "El subtítulo supera 8 MB" }
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SUBTITLE_BYTES) { "El subtítulo supera 8 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(status: Int, body: String): IllegalStateException {
        val serverMessage = runCatching {
            val json = JSONObject(body)
            json.optString("message").ifBlank {
                json.optJSONObject("error")?.optString("message").orEmpty()
            }
        }.getOrNull().orEmpty()
        val fallback = when (status) {
            401, 403 -> "La API key de OpenSubtitles no es válida o no está autorizada"
            406 -> "OpenSubtitles rechazó la identificación de Vórtex"
            429 -> "Agotaste temporalmente la cuota de OpenSubtitles"
            else -> "OpenSubtitles respondió HTTP $status"
        }
        return IllegalStateException(serverMessage.ifBlank { fallback })
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._() -]"), "_")
        .take(180)
        .ifBlank { "subtitulo.srt" }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val API_ROOT = "https://api.opensubtitles.com/api/v1"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_RESULTS = 20
        const val MAX_REDIRECTS = 4
        const val MAX_SUBTITLE_BYTES = 8L * 1024L * 1024L
    }
}

/** La API key queda cifrada con una clave AES no exportable del Android Keystore. */
object OpenSubtitlesKeyStore {
    private const val PREFERENCES = "vortex_opensubtitles"
    private const val VALUE = "api_key"
    private const val KEY_ALIAS = "vortex.opensubtitles.api"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun read(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(VALUE, null) ?: return null
        return runCatching {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
                )
            }
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
                .toString(StandardCharsets.UTF_8)
                .takeIf(String::isNotBlank)
        }.getOrElse {
            clear(context)
            null
        }
    }

    fun write(context: Context, apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.length in 10..256) { "La API key no parece válida" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(VALUE, value).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(VALUE).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
