package com.vortex.player.download

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAutomationTest {

    @Test
    fun publicFallbackUsesMaintainedClients() {
        assertTrue(YoutubeAutomation.PUBLIC_CLIENT_ARGUMENT.contains("web_embedded"))
        assertTrue(YoutubeAutomation.PUBLIC_CLIENT_ARGUMENT.contains("visionos"))
        assertFalse(YoutubeAutomation.PUBLIC_CLIENT_ARGUMENT.contains("android_vr"))
    }

    @Test
    fun recognizesBothBotChallengeApostrophes() {
        assertTrue(YoutubeAutomation.isBotChallenge("Sign in to confirm you're not a bot"))
        assertTrue(YoutubeAutomation.isBotChallenge("Sign in to confirm you’re not a bot"))
        assertTrue(YoutubeAutomation.isBotChallenge("[youtube] Use --cookies for authentication"))
        assertFalse(YoutubeAutomation.isBotChallenge("HTTP Error 503"))
    }

    @Test
    fun findsOnlyTheBundledQuickJsBinary() {
        val directory = Files.createTempDirectory("vortex-qjs").toFile()
        try {
            assertNull(YoutubeAutomation.quickJsExecutable(directory.absolutePath))
            val executable = File(directory, "libqjs.so").apply { writeText("test") }
            assertEquals(
                executable.absolutePath,
                YoutubeAutomation.quickJsExecutable(directory.absolutePath)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun enablesEjsOnlyForVersionsThatSupportIt() {
        assertFalse(YoutubeAutomation.supportsEjs("2025.10.30"))
        assertTrue(YoutubeAutomation.supportsEjs("stable@2025.11.12"))
        assertTrue(YoutubeAutomation.supportsEjs("2026.08.22"))
        assertFalse(YoutubeAutomation.supportsEjs("desconocida"))
    }
}
