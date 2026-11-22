package com.tunegrab.app.search

class NewPipeSearchProvider(
    private val source: suspend (String, Int) -> List<ProcessedSearchResult> =
        { query, maxResults -> YouTubeSearchService.search(query, maxResults) }
) : SearchProvider {

    override suspend fun search(
        query: String,
        maxResults: Int
    ): List<ProcessedSearchResult> {
        val normalized = SearchQuery.normalize(query)
        return source(normalized, maxResults.coerceIn(1, 25)).map { result ->
            result.copy(
                webUrl = result.webUrl.ifBlank {
                    "https://www.youtube.com/watch?v=${result.videoId}"
                }
            )
        }
    }
}
