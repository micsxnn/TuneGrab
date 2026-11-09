package com.tunegrab.app.lyrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** BetterLyrics (https://lyrics-api.boidu.dev): TTML da Apple, tempo por sílaba. */
interface BetterLyricsService {
    /** Devolve `{"ttml": "..."}`. */
    @GET("getLyrics")
    suspend fun getLyrics(
        @Query("s") title: String,
        @Query("a") artist: String,
        @Query("d") duration: Int? = null,
        @Query("al") album: String? = null,
    ): Response<JsonElement>

    /** Devolve `{"lyrics": "..."}`. */
    @GET("ttml/getLyrics")
    suspend fun ttmlLyrics(
        @Query("s") title: String,
        @Query("a") artist: String,
        @Query("d") duration: Int? = null,
        @Query("al") album: String? = null,
    ): Response<JsonElement>

    /** Karaokê da QQ Music: LRC com tempo por PALAVRA (`[inicio,dur]` + `(inicio,dur)`). */
    @GET("qq/getLyrics")
    suspend fun qqLyrics(
        @Query("s") title: String,
        @Query("a") artist: String,
        @Query("d") duration: Int? = null,
        @Query("al") album: String? = null,
    ): Response<JsonElement>
}

/**
 * BETTERLYRICS — o TTML da Apple Music servido de graça, sem chave.
 *
 * A fonte entrega tempo por SÍLABA (`<span begin end>` dentro de cada verso),
 * que é exatamente o modelo [LyricLine]/[LyricSyllable] do app. É por isso
 * que ela entra na frente do LRCLIB na corrida: letra com tempo de PALAVRA
 * acende palavra a palavra, letra de linha acende a linha inteira.
 *
 * A resposta muda de formato de endpoint pra endpoint (`{"ttml": ...}`,
 * `{"lyrics": ...}`, LRC de karaokê, LRC comum), então o payload é desembrulhado
 * porshape e depois lido na ordem: TTML → karaokê → LRC enriquecido → LRC.
 */
class BetterLyricsApi(
    private val service: BetterLyricsService =
        LyricsNetwork.service("https://lyrics-api.boidu.dev/", BetterLyricsService::class.java),
) : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.BETTER_LYRICS

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isBlank() || artist.isBlank()) return null

        fetchWord(title, artist, query.durationSec, query.album)?.let { return it }

        // Título limpo: o servidor casa por aproximação, e nome sujo
        // ("feat.", "[Official Video]") atrapalha a aproximação.
        val cleanTitle = LyricsMatchers.cleanTrackTitle(title)
        val cleanArtist = LyricsMatchers.cleanArtistName(artist)
        if (cleanTitle != title || cleanArtist != artist) {
            fetchWord(cleanTitle, cleanArtist, query.durationSec, query.album)?.let { return it }
        }
        return null
    }

    /** Karaokê da QQ: LRC com tempo por palavra. */
    suspend fun fetchKaraoke(query: LyricsQuery): SourceLyrics? {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isBlank() || artist.isBlank()) return null
        val payload = request(service.qqLyrics(title, artist, query.durationSec, query.album)) ?: return null
        val lines = parseKaraokeLrc(payload).takeIf { it.isNotEmpty() } ?: return null
        return SourceLyrics(
            lines = lines,
            isSynced = true,
            isWordSynced = true,
            plainLyrics = lines.joinToString("\n") { it.text },
        )
    }

    private suspend fun fetchWord(
        title: String,
        artist: String,
        duration: Int?,
        album: String?,
    ): SourceLyrics? {
        val payload = request(service.getLyrics(title, artist, duration, album))
            ?: request(service.ttmlLyrics(title, artist, duration, album))
            ?: return null
        val lines = parseDocument(payload).takeIf { it.isNotEmpty() } ?: return null
        return SourceLyrics(
            lines = lines,
            isSynced = true,
            isWordSynced = lines.any { it.hasSyllables },
            plainLyrics = lines.joinToString("\n") { it.text },
        )
    }

    private suspend fun request(response: Response<JsonElement>): String? {
        if (!response.isSuccessful) return null
        val element = response.body() ?: return null
        return unwrapPayload(element)
    }

    /** Desembrulha o JSON até achar o texto da letra. */
    internal fun unwrapPayload(element: JsonElement): String? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> if (element.isString) {
                val text = element.content.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    // JSON aninhado dentro da string: desembrulha mais um nível.
                    val nested = runCatching { LyricsNetwork.json.parseToJsonElement(text) }.getOrNull()
                    if (nested != null && (text.startsWith("{") || text.startsWith("["))) {
                        unwrapPayload(nested) ?: text
                    } else {
                        text
                    }
                }
            } else {
                null
            }

            is JsonArray -> element.mapNotNull { unwrapPayload(it) }
                .joinToString("\n").takeIf { it.isNotBlank() }

            is JsonObject -> {
                if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false") return null
                val keys = listOf(
                    "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
                    "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
                    "data", "result", "response",
                )
                keys.asSequence().mapNotNull { key -> element[key]?.let { unwrapPayload(it) } }.firstOrNull()
            }
        }
    }

    /** Lê o payload na ordem do melhor formato pro pior. */
    internal fun parseDocument(raw: String): List<LyricLine> {
        val payload = raw.trim()
        if (payload.isBlank()) return emptyList()
        if ("<tt" in payload.lowercase() || "http://www.w3.org/ns/ttml" in payload) {
            TtmlParser.parse(payload).takeIf { it.isNotEmpty() }?.let { return it }
        }
        parseKaraokeLrc(payload).takeIf { it.isNotEmpty() }?.let { return it }
        LyricsLrc.parseEnhanced(payload).takeIf { it.isNotEmpty() }?.let { return it }
        LyricsLrc.parse(payload).takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private val KARAOKE_LINE_REGEX = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val KARAOKE_WORD_REGEX = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val KARAOKE_TIME_REGEX = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")

    /**
     * LRC de karaokê da QQ: a linha começa com `[inicio,duracao]` (ms) e cada
     * palavra vem entre parênteses com o próprio tempo: `(inicio,duracao)`.
     */
    internal fun parseKaraokeLrc(raw: String): List<LyricLine> {
        if ("[" !in raw || "(" !in raw) return emptyList()
        val rows = mutableListOf<LyricLine>()
        for (source in raw.lines()) {
            val match = KARAOKE_LINE_REGEX.matchEntire(source.trim()) ?: continue
            val lineStart = match.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = match.groupValues[2].toLongOrNull() ?: 0L
            val body = match.groupValues[3]
            val matches = KARAOKE_WORD_REGEX.findAll(body).toList()
            if (matches.isEmpty()) continue
            val words = mutableListOf<LyricSyllable>()
            val firstMatch = matches.first()
            val leadingText = LyricsLrc.decodeEntities(body.substring(0, firstMatch.range.first)).trim()
            if (leadingText.isNotEmpty()) {
                val firstStart = firstMatch.groupValues[1].toLongOrNull() ?: lineStart
                val startMs = minOf(lineStart, firstStart)
                val durMs = (firstStart - startMs).coerceAtLeast(0L)
                words += LyricSyllable(timeMs = startMs, durationMs = durMs, text = leadingText)
            }
            for (word in matches) {
                val text = LyricsLrc.decodeEntities(word.groupValues[3]).trim()
                if (text.isEmpty()) continue
                val startMs = word.groupValues[1].toLongOrNull() ?: continue
                val durMs = word.groupValues[2].toLongOrNull() ?: 0L
                words += LyricSyllable(timeMs = startMs, durationMs = durMs.coerceAtLeast(0L), text = text)
            }
            if (words.isEmpty()) continue
            val text = LyricsSanitizer.safeLineText(
                LyricsLrc.decodeEntities(body.replace(KARAOKE_TIME_REGEX, "")),
                words.map { it.text },
            )
            if (text.isEmpty()) continue
            rows += LyricLine(
                timeMs = minOf(lineStart, words.first().timeMs),
                durationMs = lineDuration.coerceAtLeast(0L),
                text = text,
                syllables = words,
            )
        }
        return rows.sortedBy { it.timeMs }
    }
}
