package com.tunegrab.app.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

/**
 * GENIUS — texto puro, raspado da página da música.
 *
 * É a ÚLTIMA fonte da fila: o Genius não tem marcação de tempo nenhum, só
 * texto. Mas o catálogo dele é gigantesco, então quando nenhuma fonte
 * sincronizada tem a letra, é ele que salva.
 *
 * O texto sai da página com **jsoup** (o parser de HTML do app): a letra
 * mora em `div[data-lyrics-container=true]` nas páginas novas e em
 * `div.lyrics` nas antigas, com as estrofes separadas por `<br>`.
 */
class GeniusLyricsApi : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.GENIUS

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val text = scrape(query.title, query.artist) ?: return null
        val plain = LyricsSanitizer.sanitizePlain(text)?.trim().orEmpty()
        if (plain.isBlank()) return null
        return SourceLyrics(
            lines = emptyList(),
            isSynced = false,
            plainLyrics = plain,
        )
    }

    private suspend fun scrape(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanQuery(title)
            val cleanArtist = cleanQuery(artist)
            // Título no formato "Artista - Faixa" é separado nas duas partes.
            val titleParts = if (cleanTitle.contains(TITLE_SEPARATOR)) {
                cleanTitle.split(TITLE_SEPARATOR, limit = 2)
            } else {
                null
            }
            val extractedTitle = when {
                titleParts != null && titleParts[0].trim().equals(cleanArtist, ignoreCase = true) -> titleParts[1].trim()
                titleParts != null && titleParts[1].trim().equals(cleanArtist, ignoreCase = true) -> titleParts[0].trim()
                titleParts != null && titleParts[0].isNotBlank() && titleParts[1].isNotBlank() -> titleParts[1].trim()
                else -> cleanTitle
            }
            val extractedArtist = when {
                titleParts != null && titleParts[0].trim().equals(cleanArtist, ignoreCase = true) -> cleanArtist
                titleParts != null && titleParts[1].trim().equals(cleanArtist, ignoreCase = true) -> cleanArtist
                titleParts != null && cleanArtist.isBlank() -> titleParts[0].trim()
                else -> cleanArtist
            }
            val titleWithoutBrackets = extractedTitle
                .replace(BRACKETED_CONTENT, " ")
                .replace(NON_ALPHANUMERIC, " ")
                .replace(WHITESPACE, " ")
                .trim()
            val attempts = buildList {
                if (extractedArtist.isNotBlank() && extractedTitle.isNotBlank()) {
                    add(SearchAttempt("$extractedArtist $extractedTitle".trim(), extractedTitle, extractedArtist))
                }
                if (extractedArtist.isNotBlank() && titleWithoutBrackets.isNotBlank() && titleWithoutBrackets != extractedTitle) {
                    add(SearchAttempt("$extractedArtist $titleWithoutBrackets".trim(), titleWithoutBrackets, extractedArtist))
                }
                if (cleanTitle != extractedTitle) {
                    add(SearchAttempt("$cleanArtist $cleanTitle".trim(), cleanTitle, cleanArtist))
                    add(SearchAttempt(cleanTitle, extractedTitle, extractedArtist))
                }
                if (titleWithoutBrackets.isNotBlank()) {
                    add(SearchAttempt(titleWithoutBrackets, titleWithoutBrackets, extractedArtist))
                }
            }.distinctBy { it.query }
            val songUrl = attempts.firstNotNullOfOrNull { attempt ->
                searchSongUrl(attempt.query, attempt.title, attempt.artist)
            } ?: return@withContext null
            val html = httpGet(songUrl)?.takeIf { it.isNotBlank() } ?: return@withContext null
            parseText(html)?.takeIf { it.isNotBlank() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private data class SearchAttempt(val query: String, val title: String, val artist: String)

    /** A busca aberta do Genius devolve JSON com os candidatos. */
    private suspend fun searchSongUrl(query: String, targetTitle: String, targetArtist: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://genius.com/api/search/multi?q=$encoded") ?: return null
        return runCatching {
            val root = LyricsNetwork.json.parseToJsonElement(body).jsonObject
            val response = root["response"]?.jsonObject ?: return null
            val sections = response["sections"]?.let { it as? JsonArray } ?: return null
            val songSection = sections.firstOrNull {
                (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "song"
            }?.jsonObject ?: return null
            val hits = songSection["hits"]?.let { it as? JsonArray } ?: return null
            val candidates = hits.mapNotNull { (it as? JsonObject)?.get("result")?.jsonObject }
            bestMatch(candidates, targetTitle, targetArtist)?.get("url")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    /** Escolhe o candidato que mais parece com a faixa pedida. */
    private fun bestMatch(
        candidates: List<JsonObject>,
        targetTitle: String,
        targetArtist: String,
    ): JsonObject? {
        if (candidates.isEmpty()) return null
        val normTitle = targetTitle.lowercase()
        val normArtist = targetArtist.lowercase()
        return candidates.mapNotNull { item ->
            val title = item["title"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            val artist = item["artist_names"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            var score = 0
            val titleMatches = normTitle.isNotBlank() &&
                (title == normTitle || title.contains(normTitle) || normTitle.contains(title))
            val artistMatches = normArtist.isNotBlank() &&
                (artist == normArtist || artist.contains(normArtist) || normArtist.contains(artist))
            if (!titleMatches && !artistMatches) return@mapNotNull null
            if (title == normTitle) score += 50 else if (titleMatches) score += 25
            if (artistMatches) score += if (artist == normArtist) 40 else 20
            // Thread, tradução e tracklist não são a música.
            val path = item["path"]?.jsonPrimitive?.contentOrNull ?: ""
            if (path.contains("translation", ignoreCase = true) && !normTitle.contains("translation")) score -= 30
            if (path.contains("türkçe", ignoreCase = true) || path.contains("polskie-tlumaczenie", ignoreCase = true)) {
                score -= 40
            }
            if (path.contains("tracklist", ignoreCase = true) || path.contains("album-art", ignoreCase = true)) {
                score -= 50
            }
            if (score <= 0) return@mapNotNull null
            item to score
        }.maxByOrNull { it.second }?.first
    }

    /**
     * Puxa o texto da letra preservando estrofes e cabeçalhos de seção.
     * Aqui é o **jsoup** entrando: HTML não é XML, e o DOM do Android não
     * aguenta a bagunça das páginas do Genius.
     */
    internal fun parseText(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)
            // Genius novo usa data-lyrics-container="true"; o antigo, div.lyrics.
            var containers = doc.select("div[data-lyrics-container=true]")
            if (containers.isEmpty()) containers = doc.select("div.lyrics")
            if (containers.isEmpty()) {
                null
            } else {
                val builder = StringBuilder()
                for (container in containers) {
                    container.select(
                        "[data-exclude-from-selection=true], " +
                            ".LyricsHeader__Container, " +
                            ".SongBioPreview__Container, " +
                            ".InreadAd__Container, " +
                            "button, " +
                            "script, " +
                            "style",
                    ).remove()
                    // O <br> vira quebra de linha antes de extrair o texto.
                    container.select("br").forEach { it.replaceWith(TextNode("\n")) }
                    container.select("p").forEach { it.prepend("\n") }
                    val text = container.wholeText()
                    if (text.isNotBlank()) builder.append(text).append("\n")
                }
                stripArtifacts(builder.toString()).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Limpa os sobras de uma página raspada (bloco de recomendação, "Embed"). */
    internal fun stripArtifacts(raw: String): String =
        raw.replace(' ', ' ')
            .replace('​', ' ')
            .replace('﻿', ' ')
            .replace(YOU_MIGHT_ALSO_LIKE, "")
            .replace(TRAILING_EMBED, "")
            .trim()

    private fun cleanQuery(text: String): String {
        val cleaned = text
            .replace(DECORATIVE_CHARS, " ")
            .replace(NOISE, " ")
            .replace(PRODUCER_TAGS, " ")
            .substringBefore(" | ")
            .replace(WHITESPACE, " ")
            .trim()
        return cleaned.ifBlank { text.trim() }
    }

    private suspend fun httpGet(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            // DE PROPOSITO não é navegador: o Genius fica atrás do
            // Cloudflare, que desafia User-Agent que se diz navegador sem
            // impressão digital de TLS correspondente. Um simples é
            // respondido normalmente.
            .header("User-Agent", GENIUS_USER_AGENT)
            .get()
            .build()
        return try {
            LyricsNetwork.http.newCall(request).awaitBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val GENIUS_USER_AGENT = "TuneGrab"

        val TITLE_SEPARATOR = Regex("""\s*[-–—:]\s*""")
        val DECORATIVE_CHARS = Regex("""[♪♫★☆【】《》「」~_]""")
        val PRODUCER_TAGS = Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$""")
        val NOISE = Regex(
            """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
                """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|hd\s+video|music\s+video)\b""",
            RegexOption.IGNORE_CASE,
        )
        val BRACKETED_CONTENT = Regex("""\s*[\(\[].*?[\)\]]""")
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}\\s]")
        val WHITESPACE = Regex("""\s+""")
        val YOU_MIGHT_ALSO_LIKE = Regex("""\d*You might also like""", RegexOption.IGNORE_CASE)
        val TRAILING_EMBED = Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE)
    }
}

/**
 * MEGALOBIZ — LRC comunitário raspado do site.
 *
 * Duas buscas contra um site antigo: procura a faixa, segue o primeiro
 * link de download de LRC e puxa as linhas marcadas da página. Fica baixo na
 * cadeia por causa disso.
 */
class MegalobizLyricsApi : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.MEGALOBIZ

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val title = query.title.trim()
        if (title.isBlank()) return null
        val artist = query.artist.trim()
        val searchUrl = "https://www.megalobiz.com/searchall".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("qry", "$artist $title".trim())
            ?.build()
            ?: return null
        val results = get(searchUrl.toString()) ?: return null
        val path = lrcPathFromSearch(results) ?: return null
        // O link vem com "&" no HTML; o navegador entenderia "&".
        val page = get(BASE + path.replace(LyricsLrc.AMP + "amp;", "&")) ?: return null
        val lines = lrcLinesFromPage(page) ?: return null
        return SourceLyrics(
            lines = lines,
            isSynced = true,
            isWordSynced = lines.any { it.hasSyllables },
            plainLyrics = lines.joinToString("\n") { it.text },
        )
    }

    private suspend fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LyricsNetwork.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .get()
            .build()
        return try {
            LyricsNetwork.http.newCall(request).awaitBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    internal fun lrcPathFromSearch(results: String): String? =
        LRC_LINK.find(results)?.groupValues?.get(1)

    internal fun lrcLinesFromPage(page: String): List<LyricLine>? {
        val raw = LRC_BODY.find(page)?.groupValues?.get(1) ?: return null
        val lrc = raw.replace(BR_TAG, "\n")
            .replace(HTML_TAG, "")
            .let { text -> LyricsLrc.decodeEntities(text) }
        return LyricsLrc.parse(lrc).takeIf { lines -> lines.any { it.text.isNotBlank() } }
    }

    private companion object {
        const val BASE = "https://www.megalobiz.com"
        val LRC_LINK = Regex("""href=["'](/lrc/maker/download/[^"']+)["']""", RegexOption.IGNORE_CASE)
        val LRC_BODY = Regex(
            """id=["']lrc_[^"']*_details["'][^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val BR_TAG = Regex("""(?i)<br\s*/?>""")
        val HTML_TAG = Regex("""<[^>]+>""")
    }
}
