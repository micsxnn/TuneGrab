package com.tunegrab.app.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resultado de busca do YouTube Data API
 */
@Serializable
data class SearchResult(
    val kind: String,
    val id: Id,
    val snippet: Snippet
) {
    @Serializable
    data class Id(
        @SerialName("videoId") val videoId: String?
    )

    @Serializable
    data class Snippet(
        @SerialName("title") val title: String,
        @SerialName("description") val description: String,
        @SerialName("thumbnails") val thumbnails: Thumbnails,
        @SerialName("channelTitle") val channelTitle: String,
        @SerialName("publishTime") val publishTime: String?
    )

    @Serializable
    data class Thumbnails(
        val default: Thumbnail? = null,
        val medium: Thumbnail? = null,
        val high: Thumbnail? = null
    )

    @Serializable
    data class Thumbnail(
        val url: String,
        val width: Int,
        val height: Int
    )
}

/**
 * Resposta da API de busca
 */
@Serializable
data class SearchResponse(
    @SerialName("items") val items: List<SearchResult>,
    @SerialName("regionCode") val regionCode: String? = null,
    @SerialName("pageInfo") val pageInfo: PageInfo? = null
) {
    @Serializable
    data class PageInfo(
        @SerialName("totalResults") val totalResults: Int,
        @SerialName("resultsPerPage") val resultsPerPage: Int
    )
}

/**
 * Resultado de busca processado para exibição
 */
data class ProcessedSearchResult(
    val videoId: String,
    val webUrl: String = "https://www.youtube.com/watch?v=$videoId",
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val description: String,
    val durationSec: Long? = null
)