package com.tunegrab.app.search

fun interface SearchProvider {
    suspend fun search(query: String, maxResults: Int): List<ProcessedSearchResult>
}

class SearchProviderException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
