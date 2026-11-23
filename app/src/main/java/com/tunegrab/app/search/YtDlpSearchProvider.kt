package com.tunegrab.app.search

import android.content.Context
import android.util.Log
import com.tunegrab.app.yt.YtDlpEngine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

internal fun buildSearchTarget(query: String, maxResults: Int): String {
    val normalized = SearchQuery.normalize(query)
    val limit = maxResults.coerceIn(1, 25)
    return "ytsearch${limit}:${normalized}"
}

class YtDlpSearchProvider(
    private val context: Context
) : SearchProvider {

    override suspend fun search(
        query: String,
        maxResults: Int
    ): List<ProcessedSearchResult> = withContext(Dispatchers.IO) {
        val target = buildSearchTarget(query, maxResults)
        try {
            YtDlpEngine.ensureReady(context) { }
            val request = YoutubeDLRequest(target).apply {
                addOption("--ignore-config")
                addOption("--no-plugin-dirs")
                addOption("--no-warnings")
                addOption("--socket-timeout", "20")
                addOption("--sleep-requests", "1")
                addOption("--dump-single-json")
                addOption("--skip-download")
            }
            val processId = UUID.randomUUID().toString()
            val response = withTimeout(SEARCH_TIMEOUT_MS) {
                YoutubeDL.execute(request, processId) { _, _, _ -> }
            }
            val output = response.out
            if (output.isBlank()) {
                throw SearchProviderException("O yt-dlp não devolveu resultados.")
            }
            SearchJsonParser.parse(output, maxResults)
        } catch (error: SearchProviderException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Falha na busca pelo yt-dlp", error)
            throw SearchProviderException("Não foi possível consultar o yt-dlp.", error)
        }
    }

    private companion object {
        const val TAG = "YtDlpSearchProvider"
        const val SEARCH_TIMEOUT_MS = 20_000L
    }
}
