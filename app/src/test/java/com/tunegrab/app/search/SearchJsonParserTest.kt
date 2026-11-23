package com.tunegrab.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchJsonParserTest {

    @Test
    fun `parse usa webpage_url e thumbnail`() {
        val json = """
            {
              "entries": [{
                "id": "abc1234567",
                "title": "Song",
                "uploader": "Artist",
                "duration": 125,
                "thumbnail": "https://img.test/song.jpg",
                "webpage_url": "https://www.youtube.com/watch?v=abc1234567"
              }]
            }
        """.trimIndent()

        val result = SearchJsonParser.parse(json).single()

        assertEquals("abc1234567", result.videoId)
        assertEquals("https://www.youtube.com/watch?v=abc1234567", result.webUrl)
        assertEquals("Song", result.title)
        assertEquals("Artist", result.channel)
        assertEquals("https://img.test/song.jpg", result.thumbnailUrl)
        assertEquals(125L, result.durationSec)
    }

    @Test
    fun `parse usa url quando webpage_url falta`() {
        val json = """
            {"entries":[{
              "id":"xyz7890123",
              "title":"Fallback",
              "url":"https://www.youtube.com/watch?v=xyz7890123"
            }]}
        """.trimIndent()

        val result = SearchJsonParser.parse(json).single()

        assertEquals("xyz7890123", result.videoId)
        assertEquals("https://www.youtube.com/watch?v=xyz7890123", result.webUrl)
    }

    @Test
    fun `parse usa maior thumbnail disponivel`() {
        val json = """
            {"entries":[{
              "id":"abc1234567",
              "title":"Song",
              "webpage_url":"https://www.youtube.com/watch?v=abc1234567",
              "thumbnails":[
                {"url":"https://img.test/small.jpg","width":120,"height":90},
                {"url":"https://img.test/large.jpg","width":1280,"height":720}
              ]
            }]}
        """.trimIndent()

        assertEquals(
            "https://img.test/large.jpg",
            SearchJsonParser.parse(json).single().thumbnailUrl
        )
    }

    @Test
    fun `parse usa url canonica quando entrada tem apenas id`() {
        val json = """{"entries":[{"id":"abc1234567","title":"Song"}]}"""

        val result = SearchJsonParser.parse(json).single()

        assertEquals("https://www.youtube.com/watch?v=abc1234567", result.webUrl)
    }

    @Test
    fun `parse descarta playlist e id invalido`() {
        val json = """
            {"entries":[
              {"id":"PL123","title":"Playlist","url":"https://www.youtube.com/playlist?list=PL123"},
              {"id":"bad id","title":"Invalid","webpage_url":"https://www.youtube.com/watch?v=bad id"}
            ]}
        """.trimIndent()

        assertTrue(SearchJsonParser.parse(json).isEmpty())
    }

    @Test
    fun `parse limita quantidade de resultados`() {
        val entries = (1..30).joinToString(",") { index ->
            val id = "id%09d".format(index)
            """{"id":"$id","title":"Song $index","webpage_url":"https://www.youtube.com/watch?v=$id"}"""
        }

        assertEquals(25, SearchJsonParser.parse("""{"entries":[$entries]}""").size)
    }

    @Test
    fun `parse remove duplicatas por id`() {
        val json = """
            {"entries":[
              {"id":"same0000000","title":"First","webpage_url":"https://www.youtube.com/watch?v=same0000000"},
              {"id":"same0000000","title":"Second","webpage_url":"https://www.youtube.com/watch?v=same0000000"}
            ]}
        """.trimIndent()

        assertEquals(1, SearchJsonParser.parse(json).size)
    }

    @Test
    fun `normalize remove espacos e caracteres de controle`() {
        assertEquals("música test", SearchQuery.normalize("  música\n test  "))
    }

    @Test
    fun `normalize recusa prefixo ytsearch malicioso`() {
        val error = runCatching { SearchQuery.normalize("ytsearch10:termo") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `normalize recusa consulta vazia`() {
        val error = runCatching { SearchQuery.normalize("   ") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
