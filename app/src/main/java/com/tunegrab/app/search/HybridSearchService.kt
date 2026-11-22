package com.tunegrab.app.search

class HybridSearchService(
    private val primary: SearchProvider,
    private val fallback: SearchProvider
) : SearchProvider {

    suspend fun search(query: String): List<ProcessedSearchResult> =
        search(query, 25)

    override suspend fun search(
        query: String,
        maxResults: Int
    ): List<ProcessedSearchResult> {
        val normalizedQuery = SearchQuery.normalize(query)
        return try {
            primary.search(normalizedQuery, maxResults)
        } catch (primaryError: Exception) {
            try {
                fallback.search(normalizedQuery, maxResults)
            } catch (fallbackError: Exception) {
                throw SearchProviderException(
                    "Não foi possível buscar agora. Tente novamente em instantes.",
                    fallbackError
                )
            }
        }
    }
}
