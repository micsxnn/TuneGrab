package com.tunegrab.app.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchViewModelTest {

    private fun result(id: String) = ProcessedSearchResult(
        videoId = id,
        webUrl = "https://www.youtube.com/watch?v=$id",
        title = "Song $id",
        channel = "Artist",
        thumbnailUrl = null,
        description = ""
    )

    @Test
    fun `estado Searching fica visivel enquanto a busca aguarda`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<List<ProcessedSearchResult>>()
        val service = SearchProvider { _, _ -> gate.await() }
        val viewModel = SearchViewModel(service)

        viewModel.search("música")

        assertTrue(viewModel.uiState.value.isSearching)
        assertFalse(viewModel.uiState.value.results.isNotEmpty())

        gate.complete(listOf(result("one")))
    }

    @Test
    fun `estado final guarda resultados`() = runBlocking {
        val service = SearchProvider { _, _ -> listOf(result("one")) }
        val viewModel = SearchViewModel(service)

        viewModel.search("música")

        assertEquals(listOf("one"), viewModel.uiState.value.results.map { it.videoId })
        assertEquals(1, viewModel.uiState.value.totalResults)
    }
}
