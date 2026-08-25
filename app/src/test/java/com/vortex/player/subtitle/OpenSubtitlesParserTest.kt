package com.vortex.player.subtitle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesParserTest {

    @Test
    fun `search maps the first downloadable file and translation flags`() {
        val json = JSONObject(
            """
            {
              "total_count": 1,
              "data": [{
                "attributes": {
                  "language": "es",
                  "release": "Example.Movie.2026.1080p",
                  "hearing_impaired": true,
                  "download_count": 4312,
                  "machine_translated": false,
                  "ai_translated": true,
                  "files": [
                    {"file_id": 98765, "file_name": "example.es.srt"},
                    {"file_id": 98766, "file_name": "example.cd2.es.srt"}
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        val result = OpenSubtitlesParser.parseSearch(json).single()

        assertEquals(98_765L, result.fileId)
        assertEquals("es", result.language)
        assertEquals("Example.Movie.2026.1080p", result.release)
        assertEquals("example.es.srt", result.fileName)
        assertEquals(4_312, result.downloadCount)
        assertTrue(result.hearingImpaired)
        assertTrue(result.machineTranslated)
    }

    @Test
    fun `search skips rows without a usable file id`() {
        val json = JSONObject(
            """
            {"data": [
              {"attributes": {"language": "en", "files": []}},
              {"attributes": {"language": "es", "files": [{"file_id": 0}]}},
              {"attributes": {"language": "fr", "files": [{"file_id": 42}]}}
            ]}
            """.trimIndent()
        )

        val result = OpenSubtitlesParser.parseSearch(json).single()

        assertEquals(42L, result.fileId)
        assertEquals("subtitulo-42.srt", result.fileName)
        assertEquals(result.fileName, result.release)
        assertFalse(result.hearingImpaired)
    }

    @Test
    fun `empty search response is accepted`() {
        assertTrue(OpenSubtitlesParser.parseSearch(JSONObject("{}" )).isEmpty())
        assertTrue(OpenSubtitlesParser.parseSearch(JSONObject("{\"data\":[]}" )).isEmpty())
    }

    @Test
    fun `download maps temporary link name and remaining quota`() {
        val response = OpenSubtitlesParser.parseDownload(
            JSONObject(
                """
                {
                  "link": "https://www.opensubtitles.com/download/example/subfile/example.srt",
                  "file_name": "example.es.srt",
                  "remaining": 97
                }
                """.trimIndent()
            )
        )

        assertEquals("https://www.opensubtitles.com/download/example/subfile/example.srt", response.first)
        assertEquals("example.es.srt", response.second)
        assertEquals(97, response.third)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `download rejects a response without link`() {
        OpenSubtitlesParser.parseDownload(JSONObject("{\"file_name\":\"broken.srt\"}"))
    }

    @Test
    fun `language options use official comma separated filter`() {
        assertEquals("es", SubtitleLanguageChoice.SPANISH.apiValue)
        assertEquals("en", SubtitleLanguageChoice.ENGLISH.apiValue)
        assertEquals("es,en", SubtitleLanguageChoice.BOTH.apiValue)
    }
}
