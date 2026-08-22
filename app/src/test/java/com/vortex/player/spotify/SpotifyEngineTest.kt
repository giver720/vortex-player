package com.vortex.player.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyEngineTest {

    @Test
    fun acceptsOnlyDeclarativePathsInsideSpotifyPageProps() {
        val config = SpotifyEngine.parse(
            """
            {
              "schema": 1,
              "version": 3,
              "label": "3.0",
              "entityPaths": ["/props/pageProps/newState/entity"],
              "tokenPaths": ["/props/pageProps/session/accessToken"]
            }
            """.trimIndent()
        )

        assertEquals(3, config?.version)
        assertEquals("3.0", config?.label)
    }

    @Test
    fun rejectsManifestThatTriesToAddAnEndpoint() {
        val config = SpotifyEngine.parse(
            """
            {
              "schema": 1,
              "version": 3,
              "label": "3.0",
              "entityPaths": ["https://example.com/steal-token"],
              "tokenPaths": ["/props/pageProps/session/accessToken"]
            }
            """.trimIndent()
        )

        assertNull(config)
    }

    @Test
    fun rejectsRollbackAndUnknownSchema() {
        val rollback = SpotifyEngine.bundled.toJson().replace("\"version\":2", "\"version\":1")
        val unknownSchema = SpotifyEngine.bundled.toJson().replace("\"schema\":1", "\"schema\":9")

        assertNull(SpotifyEngine.parse(rollback))
        assertNull(SpotifyEngine.parse(unknownSchema))
    }

    @Test
    fun recognizesWebUrisAndInternationalSpotifyLinks() {
        assertTrue(SpotifyResolver.isSpotifyLink("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(
            SpotifyResolver.isSpotifyLink(
                "https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M"
            )
        )
        assertTrue(SpotifyResolver.isSpotifyLink("https://spotify.link/AbCdEf123"))
        assertTrue(SpotifyResolver.isSpotifyLink("https://spoti.fi/AbCdEf123"))
    }
}
