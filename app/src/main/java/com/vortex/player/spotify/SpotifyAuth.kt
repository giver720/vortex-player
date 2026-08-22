package com.vortex.player.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vortex.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface SpotifyAccountState {
    data object Disconnected : SpotifyAccountState
    data object Connecting : SpotifyAccountState
    data class Connected(
        val accountId: String,
        val displayName: String,
        val spotifyUrl: String?
    ) : SpotifyAccountState
    data class Error(val message: String) : SpotifyAccountState
}

private data class SpotifyCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val accountId: String,
    val displayName: String,
    val spotifyUrl: String?
)

/**
 * Sesión oficial de Spotify para funciones de cuenta y biblioteca.
 *
 * Usa un listener loopback porque Spotify exige HTTPS salvo para 127.0.0.1. El secreto de
 * cliente no existe en el móvil; los tokens se protegen con una clave AES del Android
 * Keystore que no puede exportarse.
 */
object SpotifyAuth {
    const val REDIRECT_URI = "http://127.0.0.1:43821/callback"
    private const val CALLBACK_PORT = 43_821
    private const val CALLBACK_TIMEOUT_MS = 180_000
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val PROFILE_URL = "https://api.spotify.com/v1/me"
    private val SCOPES = listOf(
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read"
    )

    private val mutableState = MutableStateFlow<SpotifyAccountState>(SpotifyAccountState.Disconnected)
    val state: StateFlow<SpotifyAccountState> = mutableState.asStateFlow()

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            SpotifyCredentialStore.read(context)?.let { credentials ->
                mutableState.value = credentials.accountState()
            }
            initialized = true
        }
    }

    suspend fun connect(context: Context): Result<SpotifyAccountState.Connected> {
        initialize(context)
        mutableState.value = SpotifyAccountState.Connecting
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = SpotifyPkce.create(
                    clientId = BuildConfig.SPOTIFY_CLIENT_ID,
                    redirectUri = REDIRECT_URI,
                    scopes = SCOPES
                )
                val callback = awaitAuthorization(context, request)
                val token = exchangeCode(callback, request.verifier)
                val profile = fetchProfile(token.accessToken)
                val credentials = token.copy(
                    accountId = profile.accountId,
                    displayName = profile.displayName,
                    spotifyUrl = profile.spotifyUrl
                )
                SpotifyCredentialStore.write(context, credentials)
                credentials.accountState().also { mutableState.value = it }
            }.onFailure { error ->
                mutableState.value = SpotifyAccountState.Error(
                    error.message ?: "No se pudo conectar con Spotify"
                )
            }
        }
    }

    fun disconnect(context: Context) {
        SpotifyCredentialStore.clear(context)
        mutableState.value = SpotifyAccountState.Disconnected
    }

    /** Token listo para futuras llamadas de biblioteca; se renueva antes de caducar. */
    suspend fun accessToken(context: Context): String? = withContext(Dispatchers.IO) {
        initialize(context)
        val stored = SpotifyCredentialStore.read(context) ?: return@withContext null
        if (stored.expiresAt > System.currentTimeMillis() + 60_000L) {
            return@withContext stored.accessToken
        }
        runCatching {
            val renewed = refresh(stored)
            SpotifyCredentialStore.write(context, renewed)
            mutableState.value = renewed.accountState()
            renewed.accessToken
        }.getOrElse {
            // No destruimos una sesión por un fallo temporal de red. Así la cuenta y su
            // caché siguen visibles sin conexión y el próximo refresco puede reintentarlo.
            null
        }
    }

    private suspend fun awaitAuthorization(
        context: Context,
        request: SpotifyAuthorizationRequest
    ): Map<String, String> = withContext(Dispatchers.IO) {
        ServerSocket(CALLBACK_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = CALLBACK_TIMEOUT_MS
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val target = requestLine.split(' ').getOrNull(1)
                    ?: throw IllegalStateException("Spotify no devolvió una respuesta válida")
                val params = parseQuery(URI("http://127.0.0.1$target").rawQuery)
                val ok = params["code"] != null && params["state"] == request.state
                val body = if (ok) {
                    "<html><body><h2>Vórtex conectado</h2>" +
                        "<p>Ya puedes cerrar esta pestaña y volver a la aplicación.</p></body></html>"
                } else {
                    "<html><body><h2>No se pudo conectar Vórtex</h2>" +
                        "<p>Vuelve a la aplicación para intentarlo otra vez.</p></body></html>"
                }
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write("HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\n")
                    writer.write("Content-Type: text/html; charset=utf-8\r\n")
                    writer.write("Content-Length: ${bytes.size}\r\n")
                    writer.write("Connection: close\r\n\r\n")
                    writer.write(body)
                }
                params
            }
        }.also { params ->
            params["error"]?.let { throw IllegalStateException("Spotify rechazó el acceso: $it") }
            if (params["state"] != request.state) {
                throw SecurityException("La respuesta de Spotify no coincide con esta solicitud")
            }
            if (params["code"].isNullOrBlank()) {
                throw IllegalStateException("Spotify no devolvió el código de autorización")
            }
        }
    }

    private fun exchangeCode(params: Map<String, String>, verifier: String): SpotifyCredentials {
        val response = postForm(
            TOKEN_URL,
            linkedMapOf(
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to params.getValue("code"),
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier
            )
        )
        return SpotifyCredentials(
            accessToken = response.getString("access_token"),
            refreshToken = response.optString("refresh_token"),
            expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 3_600L) * 1_000L,
            accountId = "",
            displayName = "Spotify",
            spotifyUrl = null
        )
    }

    private fun refresh(stored: SpotifyCredentials): SpotifyCredentials {
        if (stored.refreshToken.isBlank()) throw IllegalStateException("Spotify no entregó refresh token")
        val response = postForm(
            TOKEN_URL,
            linkedMapOf(
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to stored.refreshToken
            )
        )
        return stored.copy(
            accessToken = response.getString("access_token"),
            refreshToken = response.optString("refresh_token").ifBlank { stored.refreshToken },
            expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 3_600L) * 1_000L
        )
    }

    private fun fetchProfile(accessToken: String): SpotifyCredentials {
        val response = requestJson(PROFILE_URL, accessToken)
        return SpotifyCredentials(
            accessToken = "",
            refreshToken = "",
            expiresAt = 0,
            accountId = response.getString("id"),
            displayName = response.optString("display_name").ifBlank { "Spotify" },
            spotifyUrl = response.optJSONObject("external_urls")?.optString("spotify")
        )
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return connection.readJson(body)
    }

    private fun requestJson(url: String, accessToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return connection.readJson()
    }

    private fun HttpURLConnection.readJson(body: ByteArray? = null): JSONObject = try {
        body?.let { outputStream.use { output -> output.write(it) } }
        val status = responseCode
        val text = (if (status in 200..299) inputStream else errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val reason = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message")
                    ?: JSONObject(text).optString("error_description")
            }.getOrNull().orEmpty()
            throw IllegalStateException(
                reason.ifBlank { "Spotify respondió HTTP $status" }
            )
        }
        JSONObject(text)
    } finally {
        disconnect()
    }

    private fun parseQuery(query: String?): Map<String, String> =
        query.orEmpty().split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val parts = pair.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }.toMap()

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun SpotifyCredentials.accountState() = SpotifyAccountState.Connected(
        accountId = accountId,
        displayName = displayName,
        spotifyUrl = spotifyUrl
    )
}

/** Un único blob cifrado evita dejar tokens o datos de cuenta legibles en SharedPreferences. */
private object SpotifyCredentialStore {
    private const val PREFERENCES = "vortex_spotify_account"
    private const val VALUE = "credentials"
    private const val KEY_ALIAS = "vortex.spotify.oauth"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun read(context: Context): SpotifyCredentials? {
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
            val raw = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
                .toString(StandardCharsets.UTF_8)
            JSONObject(raw).let { json ->
                SpotifyCredentials(
                    accessToken = json.getString("accessToken"),
                    refreshToken = json.getString("refreshToken"),
                    expiresAt = json.getLong("expiresAt"),
                    accountId = json.getString("accountId"),
                    displayName = json.getString("displayName"),
                    spotifyUrl = json.optString("spotifyUrl").takeIf { it.isNotBlank() }
                )
            }
        }.getOrElse {
            clear(context)
            null
        }
    }

    fun write(context: Context, credentials: SpotifyCredentials) {
        val raw = JSONObject().apply {
            put("accessToken", credentials.accessToken)
            put("refreshToken", credentials.refreshToken)
            put("expiresAt", credentials.expiresAt)
            put("accountId", credentials.accountId)
            put("displayName", credentials.displayName)
            put("spotifyUrl", credentials.spotifyUrl)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.doFinal(raw)
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
