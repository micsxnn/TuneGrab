package com.tunegrab.app.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NewPipeSearchProviderTest {

    @Test
    fun `converte resultado NewPipe para url canonica`() = runBlocking {
        val provider = NewPipeSearchProvider { _, _ ->
            listOf(
                ProcessedSearchResult(
                    videoId = "abc12345678",
                    webUrl = "",
                    title = "Song",
                    channel = "Artist",
                    thumbnailUrl = null,
                    description = ""
                )
            )
        }

        val result = provider.search("música", 25).single()

        assertEquals("https://www.youtube.com/watch?v=abc12345678", result.webUrl)
    }
}
