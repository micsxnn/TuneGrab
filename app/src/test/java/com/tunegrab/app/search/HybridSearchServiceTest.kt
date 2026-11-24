package com.tunegrab.app.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridSearchServiceTest {

    private fun result(id: String) = ProcessedSearchResult(
        videoId = id,
        webUrl = "https://www.youtube.com/watch?v=$id",
        title = "Song $id",
        channel = "Artist",
        thumbnailUrl = null,
        description = ""
    )

    @Test
    fun `resultado do provedor principal tem precedencia`() = runBlocking {
        var fallbackCalls = 0
        val service = HybridSearchService(
            primary = SearchProvider { _, _ -> listOf(result("primary")) },
            fallback = SearchProvider { _, _ ->
                fallbackCalls++
                listOf(result("fallback"))
            }
        )

        val results = service.search("música", 25)

        assertEquals(listOf("primary"), results.map { it.videoId })
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `lista vazia do provedor principal aciona o fallback`() = runBlocking {
        var fallbackCalls = 0
        val service = HybridSearchService(
            primary = SearchProvider { _, _ -> emptyList() },
            fallback = SearchProvider { _, _ ->
                fallbackCalls++
                listOf(result("fallback"))
            }
        )

        val results = service.search("música", 25)

        assertEquals(listOf("fallback"), results.map { it.videoId })
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `falha do provedor principal aciona o fallback`() = runBlocking {
        val service = HybridSearchService(
            primary = SearchProvider { _, _ -> throw SearchProviderException("primary falhou") },
            fallback = SearchProvider { _, _ -> listOf(result("fallback")) }
        )

        assertEquals(listOf("fallback"), service.search("música", 25).map { it.videoId })
    }

    @Test
    fun `se ambos ficarem vazios o resultado continua vazio`() = runBlocking {
        val service = HybridSearchService(
            primary = SearchProvider { _, _ -> emptyList() },
            fallback = SearchProvider { _, _ -> emptyList() }
        )

        assertEquals(0, service.search("música", 25).size)
    }
}
