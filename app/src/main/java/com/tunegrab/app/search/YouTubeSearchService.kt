package com.tunegrab.app.search

import android.util.Log
import com.tunegrab.app.yt.YtExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor

/**
 * Serviço para busca do YouTube usando NewPipeExtractor
 * - Não requer API key
 * - Funciona igual ao app já faz para downloads
 * - Usa o mesmo mecanismo de PoToken/BotGuard
 */
object YouTubeSearchService {
    private const val TAG = "YouTubeSearchService"
    private const val DEFAULT_MAX_RESULTS = 25

    private var initialized = false

    /**
     * Inicializa o serviço (usa o mesmo init do YtExtractor)
     */
    fun init() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    YtExtractor.init()
                    initialized = true
                }
            }
        }
    }

    /**
     * Busca vídeos no YouTube usando web scraping/NewPipe
     * @param query Termo de busca
     * @param maxResults Número máximo de resultados
     * @return Lista de resultados processados
     */
    suspend fun search(query: String, maxResults: Int = DEFAULT_MAX_RESULTS): List<ProcessedSearchResult> =
        withContext(Dispatchers.IO) {
            try {
                init()

                if (query.isBlank()) {
                    return@withContext emptyList()
                }

                // Usa NewPipe para fazer a busca via SearchInfo
                val service = ServiceList.YouTube
                val qhFactory = service.searchQHFactory
                val searchQuery = qhFactory.fromQuery(query, listOf("videos"), "")

                // Busca os resultados via SearchInfo (API correta do fork)
                val searchInfo = SearchInfo.getInfo(service, searchQuery)

                // Converte para nossos modelos
                searchInfo.relatedItems.mapNotNull { item ->
                    try {
                        // item é um InfoItem que no caso de busca de vídeo implementa StreamInfoItemExtractor
                        val url = item.url
                        val videoId = url?.let { extractVideoId(it) }
                        if (videoId != null) {
                            // Tenta fazer cast para StreamInfoItemExtractor para pegar uploaderName e description
                            val streamItem = item as? StreamInfoItemExtractor
                            val uploaderName = streamItem?.getUploaderName() ?: "Desconhecido"
                            val description = streamItem?.getShortDescription() ?: ""

                            ProcessedSearchResult(
                                videoId = videoId,
                                webUrl = "https://www.youtube.com/watch?v=$videoId",
                                title = item.name ?: "",
                                channel = uploaderName,
                                thumbnailUrl = item.thumbnails?.maxByOrNull { it.height }?.url,
                                description = description.takeIf { !it.isNullOrBlank() } ?: ""
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Erro ao processar resultado: ${item.name}", e)
                        null
                    }
                }.take(maxResults.coerceAtMost(100))
            } catch (e: ExtractionException) {
                Log.e(TAG, "Erro de extração na busca", e)
                throw SearchProviderException("Não foi possível consultar o NewPipeExtractor.", e)
            } catch (e: ParsingException) {
                Log.e(TAG, "Erro de parsing na busca", e)
                throw SearchProviderException("Não foi possível consultar o NewPipeExtractor.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Erro na busca do YouTube", e)
                throw SearchProviderException("Não foi possível consultar o NewPipeExtractor.", e)
            }
        }

    private fun extractVideoId(url: String): String? {
        // Extrai o ID do vídeo da URL
        val patterns = listOf(
            "v=([^&]+)",
            "youtu\\.be/([^/?]+)",
            "youtube\\.com/embed/([^/?]+)",
            "youtube\\.com/v/([^/?]+)"
        )

        for (pattern in patterns) {
            val match = Regex(pattern).find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun isConfigured(): Boolean = initialized
}