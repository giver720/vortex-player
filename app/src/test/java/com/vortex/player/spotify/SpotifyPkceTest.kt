package com.vortex.player.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPkceTest {

    @Test
    fun challengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun authorizationRequestContainsPkceAndNoClientSecret() {
        val request = SpotifyPkce.create(
            clientId = "public-client-id",
            redirectUri = "http://127.0.0.1:43821/callback",
            scopes = listOf("playlist-read-private", "user-library-read")
        )
        assertTrue(request.verifier.length in 43..128)
        assertTrue("client_id=public-client-id" in request.url)
        assertTrue("code_challenge_method=S256" in request.url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A43821%2Fcallback" in request.url)
        assertTrue("scope=playlist-read-private%20user-library-read" in request.url)
        assertFalse("client_secret" in request.url)
    }
}
