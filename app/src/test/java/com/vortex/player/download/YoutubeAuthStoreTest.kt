package com.vortex.player.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAuthStoreTest {

    @Test
    fun authenticatedYoutubeCookiesBecomeAPrivateNetscapeJar() {
        val jar = YoutubeCookieJarCodec.encode(
            listOf(
                "https://www.youtube.com" to
                    "LOGIN_INFO=session; SAPISID=secret; PREF=hl=es"
            )
        )

        assertTrue(jar.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(jar.contains("\tLOGIN_INFO\tsession"))
        assertTrue(jar.contains("\tSAPISID\tsecret"))
        assertTrue(YoutubeCookieJarCodec.isAuthenticated(jar))
    }

    @Test
    fun incompleteOrInjectedCookieHeadersAreRejected() {
        val incomplete = YoutubeCookieJarCodec.encode(
            listOf("https://www.youtube.com" to "PREF=hl=es")
        )
        val injected = YoutubeCookieJarCodec.encode(
            listOf(
                "https://www.youtube.com" to
                    "LOGIN_INFO=value\nmalicious; SAPISID=value\tmalicious"
            )
        )

        assertFalse(YoutubeCookieJarCodec.isAuthenticated(incomplete))
        assertFalse(YoutubeCookieJarCodec.isAuthenticated(injected))
    }
}
