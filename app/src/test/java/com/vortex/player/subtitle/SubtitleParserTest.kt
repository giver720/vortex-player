package com.vortex.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun `parses multiline srt and removes presentation tags`() {
        val source = """
            ﻿1
            00:00:01,250 --> 00:00:03,500
            <i>Hola</i><br>mundo &amp; compañía

            2
            00:01:00,000 --> 00:01:02,000
            Segunda línea
        """.trimIndent()

        val cues = SubtitleParser.parse(source)

        assertEquals(2, cues.size)
        assertEquals(1_250L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("Hola\nmundo & compañía", cues[0].text)
        assertEquals(60_000L, cues[1].startMs)
    }

    @Test
    fun `parses webvtt timestamps without hour component`() {
        val source = """
            WEBVTT

            intro
            00:02.45 --> 00:04.900 align:center
            Welcome
        """.trimIndent()

        val cues = SubtitleParser.parse(source)

        assertEquals(1, cues.size)
        assertEquals(2_450L, cues.single().startMs)
        assertEquals(4_900L, cues.single().endMs)
    }

    @Test
    fun `ignores malformed and inverted cues`() {
        val source = """
            1
            esto no es tiempo
            Texto

            2
            00:00:05,000 --> 00:00:04,000
            Invertido
        """.trimIndent()

        assertTrue(SubtitleParser.parse(source).isEmpty())
        assertNull(SubtitleParser.parseTimestamp("70:00.000"))
    }

    @Test
    fun `timeline joins overlapping cues and respects end boundary`() {
        val cues = listOf(
            SubtitleCue(1_000L, 3_000L, "Español"),
            SubtitleCue(2_000L, 4_000L, "English")
        )

        assertNull(SubtitleTimeline.activeText(cues, 999L))
        assertEquals("Español\nEnglish", SubtitleTimeline.activeText(cues, 2_500L))
        assertEquals("English", SubtitleTimeline.activeText(cues, 3_000L))
        assertNull(SubtitleTimeline.activeText(cues, 4_000L))
    }
}
