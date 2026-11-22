package com.tunegrab.app.search

import org.json.JSONArray
import org.json.JSONObject

object SearchQuery {
    const val MAX_LENGTH = 200

    fun normalize(raw: String): String {
        val cleaned = raw
            .filter { it.isISOControl().not() }
            .trim()
            .replace(Regex("\\s+"), " ")

        require(cleaned.isNotEmpty()) { "Digite uma música para buscar." }
        require(cleaned.length <= MAX_LENGTH) { "A busca deve ter no máximo $MAX_LENGTH caracteres." }
        require(!cleaned.startsWith("ytsearch", ignoreCase = true)) { "Consulta de busca inválida." }
        return cleaned
    }
}

object SearchJsonParser {
    private const val MAX_RESULTS = 25
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{1,20}$")

    fun parse(json: String, maxResults: Int = MAX_RESULTS): List<ProcessedSearchResult> {
        val root = JSONObject(json)
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val limit = maxResults.coerceIn(0, MAX_RESULTS)
        val seen = linkedSetOf<String>()
        val results = mutableListOf<ProcessedSearchResult>()

        for (index in 0 until entries.length()) {
            if (results.size >= limit) break
            val entry = entries.optJSONObject(index) ?: continue
            val result = entry.toResult() ?: continue
            if (!seen.add(result.videoId)) continue
            results += result
        }
        return results
    }

    private fun JSONObject.toResult(): ProcessedSearchResult? {
        val title = optString("title").trim()
        if (title.isEmpty()) return null

        val rawUrl = optString("webpage_url").trim()
            .ifEmpty { optString("url").trim() }
        if (rawUrl.contains("/playlist?") || rawUrl.contains("/list?")) return null
        val rawId = optString("id").trim()
        val url = when {
            rawUrl.startsWith("https://www.youtube.com/watch?v=") -> rawUrl
            rawUrl.startsWith("https://youtu.be/") -> rawUrl
            rawId.isNotEmpty() -> "https://www.youtube.com/watch?v=$rawId"
            else -> ""
        }
        if (url.isEmpty() || url.contains("/playlist?")) return null

        val videoId = rawId.ifEmpty { extractVideoId(url) }
        if (!videoIdPattern.matches(videoId)) return null

        val channel = optString("uploader").trim()
            .ifEmpty { optString("channel").trim() }
            .ifEmpty { optString("uploader_id").trim() }
        val thumbnail = optString("thumbnail").trim()
            .ifEmpty { largestThumbnailUrl() }
        val duration = if (optDouble("duration", 0.0) > 0.0) {
            optDouble("duration", 0.0).toLong()
        } else {
            null
        }

        return ProcessedSearchResult(
            videoId = videoId,
            webUrl = url,
            title = title,
            channel = channel,
            thumbnailUrl = thumbnail.ifEmpty { null },
            description = optString("description").trim(),
            durationSec = duration
        )
    }

    private fun JSONObject.largestThumbnailUrl(): String {
        val thumbnails = optJSONArray("thumbnails") ?: return ""
        var bestUrl = ""
        var bestHeight = -1L
        for (index in 0 until thumbnails.length()) {
            val thumbnail = thumbnails.optJSONObject(index) ?: continue
            val url = thumbnail.optString("url").trim()
            val height = thumbnail.optLong("height", 0L)
            if (url.isNotEmpty() && height > bestHeight) {
                bestUrl = url
                bestHeight = height
            }
        }
        return bestUrl
    }

    private fun extractVideoId(url: String): String {
        val marker = if (url.contains("youtu.be/")) "youtu.be/" else "v="
        return url.substringAfter(marker, "").substringBefore('&').trim()
    }
}
