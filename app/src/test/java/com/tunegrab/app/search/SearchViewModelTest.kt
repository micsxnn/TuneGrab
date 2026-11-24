package com.tunegrab.app.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // `: Unit` explícito: a última expressão (gate.complete) devolve Boolean e
    // métodos @Test precisam ser void, senão o JUnit 4 rejeita a classe inteira.
    @Test
    fun `estado Searching fica visivel enquanto a busca aguarda`(): Unit = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<List<ProcessedSearchResult>>()
        val service = SearchProvider { _, _ -> gate.await() }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = SearchViewModel(service, scope)

        viewModel.search("música")

        assertTrue(viewModel.uiState.value.isSearching)
        assertFalse(viewModel.uiState.value.results.isNotEmpty())

        gate.complete(listOf(result("one")))
    }

    @Test
    fun `estado final guarda resultados`() = runBlocking {
        val service = SearchProvider { _, _ -> listOf(result("one")) }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = SearchViewModel(service, scope)

        viewModel.search("música")

        assertEquals(listOf("one"), viewModel.uiState.value.results.map { it.videoId })
        assertEquals(1, viewModel.uiState.value.totalResults)
    }
}
