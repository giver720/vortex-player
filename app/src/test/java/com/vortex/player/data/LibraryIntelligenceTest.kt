package com.vortex.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIntelligenceTest {

    @Test
    fun `recognizes the supported episode naming conventions`() {
        assertEquals(
            ParsedEpisodeLabel("The Expanse", 2, 7),
            LibraryIntelligenceEngine.parseEpisodeLabel("The.Expanse.S02E07.1080p")
        )
        assertEquals(
            ParsedEpisodeLabel("Dark", 1, 3),
            LibraryIntelligenceEngine.parseEpisodeLabel("Dark 1x03 WEB-DL")
        )
        assertEquals(
            ParsedEpisodeLabel("Arcane", 2, 4),
            LibraryIntelligenceEngine.parseEpisodeLabel("Arcane Temporada 2 Episodio 4")
        )
        assertNull(LibraryIntelligenceEngine.parseEpisodeLabel("Una pelicula cualquiera"))
    }

    @Test
    fun `duplicate signature rounds duration but keeps media type separate`() {
        val videoA = LibraryIntelligenceEngine.duplicateKey(true, 10_000L, 60_100L)
        val videoB = LibraryIntelligenceEngine.duplicateKey(true, 10_000L, 60_400L)
        val audio = LibraryIntelligenceEngine.duplicateKey(false, 10_000L, 60_100L)

        assertEquals(videoA, videoB)
        assertFalse(videoA == audio)
    }

    @Test
    fun `technical filter boundaries are deterministic`() {
        assertTrue(LibraryIntelligenceEngine.matchesResolution(true, 1920, 1080, ResolutionFilter.FULL_HD))
        assertFalse(LibraryIntelligenceEngine.matchesResolution(false, 0, 0, ResolutionFilter.FULL_HD))

        assertTrue(LibraryIntelligenceEngine.matchesDuration(10 * 60_000L, DurationFilter.MEDIUM))
        assertTrue(LibraryIntelligenceEngine.matchesDuration(60 * 60_000L, DurationFilter.MEDIUM))
        assertTrue(LibraryIntelligenceEngine.matchesDuration(60 * 60_000L + 1L, DurationFilter.LONG))

        val mb = 1024L * 1024L
        assertTrue(LibraryIntelligenceEngine.matchesSize(100L * mb, SizeFilter.MEDIUM))
        assertTrue(LibraryIntelligenceEngine.matchesSize(1024L * mb + 1L, SizeFilter.LARGE))
    }

    @Test
    fun `container detection accepts common aliases`() {
        assertEquals(ContainerFilter.MP4, LibraryIntelligenceEngine.containerFor("video.M4V"))
        assertEquals(ContainerFilter.MP4, LibraryIntelligenceEngine.containerFor("song.m4a"))
        assertEquals(ContainerFilter.FLAC, LibraryIntelligenceEngine.containerFor("album.flac"))
        assertEquals(ContainerFilter.OTHER, LibraryIntelligenceEngine.containerFor("archive.avi"))
    }
}
