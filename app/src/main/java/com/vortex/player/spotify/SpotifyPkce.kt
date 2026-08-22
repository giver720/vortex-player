package com.vortex.player.spotify

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class SpotifyAuthorizationRequest(
    val verifier: String,
    val state: String,
    val url: String
)

/** Construcción pura del desafío OAuth; no contiene ni necesita un Client Secret. */
object SpotifyPkce {

    fun create(
        clientId: String,
        redirectUri: String,
        scopes: List<String>,
        random: SecureRandom = SecureRandom()
    ): SpotifyAuthorizationRequest {
        val verifier = randomUrlToken(64, random)
        val state = randomUrlToken(24, random)
        val challenge = challenge(verifier)
        val query = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
            "scope" to scopes.joinToString(" "),
            "show_dialog" to "true"
        ).entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return SpotifyAuthorizationRequest(
            verifier = verifier,
            state = state,
            url = "https://accounts.spotify.com/authorize?$query"
        )
    }

    internal fun challenge(verifier: String): String =
        base64Url(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )

    private fun randomUrlToken(bytes: Int, random: SecureRandom): String {
        val value = ByteArray(bytes).also(random::nextBytes)
        return base64Url(value)
    }

    /** RFC 4648 URL-safe, sin padding; implementación propia para seguir soportando API 24. */
    private fun base64Url(input: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString((input.size * 4 + 2) / 3) {
            var index = 0
            while (index < input.size) {
                val first = input[index].toInt() and 0xff
                val second = input.getOrNull(index + 1)?.toInt()?.and(0xff)
                val third = input.getOrNull(index + 2)?.toInt()?.and(0xff)
                append(alphabet[first ushr 2])
                append(alphabet[((first and 0x03) shl 4) or ((second ?: 0) ushr 4)])
                if (second != null) {
                    append(alphabet[((second and 0x0f) shl 2) or ((third ?: 0) ushr 6)])
                }
                if (third != null) append(alphabet[third and 0x3f])
                index += 3
            }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
