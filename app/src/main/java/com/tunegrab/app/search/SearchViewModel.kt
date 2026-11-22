package com.tunegrab.app.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel para gerenciar a busca de músicas
 */
class SearchViewModel(
    private val searchService: SearchProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private val _searchResults = MutableStateFlow<List<ProcessedSearchResult>>(emptyList())
    val searchResults: StateFlow<List<ProcessedSearchResult>> = _searchResults

    private var currentQuery = ""
    private var searchToken = 0L

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _uiState.value = SearchUiState()
            return
        }

        val normalizedQuery = SearchQuery.normalize(query)
        val token = ++searchToken
        currentQuery = normalizedQuery
        _uiState.value = SearchUiState(isSearching = true, query = normalizedQuery)

        viewModelScope.launch {
            try {
                val results = searchService.search(normalizedQuery, 25)
                if (token != searchToken) return@launch
                _searchResults.value = results
                _uiState.value = SearchUiState(
                    isSearching = false,
                    query = normalizedQuery,
                    results = results,
                    totalResults = results.size
                )
            } catch (error: Exception) {
                if (token != searchToken) return@launch
                _uiState.value = SearchUiState(
                    isSearching = false,
                    query = normalizedQuery,
                    error = error.message ?: "Erro ao buscar"
                )
            }
        }
    }

    fun clearSearch() {
        searchToken++
        currentQuery = ""
        _searchResults.value = emptyList()
        _uiState.value = SearchUiState()
    }

    fun retrySearch() {
        if (currentQuery.isNotEmpty()) {
            search(currentQuery)
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                SearchViewModel(
                    HybridSearchService(
                        primary = YtDlpSearchProvider(appContext),
                        fallback = NewPipeSearchProvider()
                    )
                )
            }
        }
    }
}

/**
 * Estado da UI de busca
 */
data class SearchUiState(
    val isSearching: Boolean = false,
    val query: String = "",
    val results: List<ProcessedSearchResult> = emptyList(),
    val totalResults: Int = 0,
    val error: String? = null
)