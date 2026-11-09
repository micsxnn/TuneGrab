package com.tunegrab.app.lyrics

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class ITunesSong(
    val trackId: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val trackTimeMillis: Long? = null,
    val artworkUrl100: String? = null,
    val primaryGenreName: String? = null,
    val releaseDate: String? = null,
    val copyright: String? = null,
)

@Serializable
data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesSong> = emptyList(),
)

/** A busca da iTunes (resolve nome de faixa → trackId da Apple Music). */
interface ITunesService {
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 5,
    ): Response<ITunesSearchResponse>
}

/** O envelope de sílabas do Lyrically (https://lyrics.paxsenix.org). */
@Serializable
data class PaxEnvelope(
    val syncType: String? = null,
    val lyrics: List<PaxLine> = emptyList(),
    val lrc: String? = null,
    val plain: String? = null,
)

@Serializable
data class PaxLine(
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val text: List<PaxWord> = emptyList(),
    val background: Boolean = false,
    val backgroundText: List<PaxWord> = emptyList(),
)

@Serializable
data class PaxWord(
    val text: String = "",
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val part: Boolean = false,
)

/** O Lyrically/Paxsenix serve TTML cru ou o envelope JSON, conforme o parâmetro. */
interface PaxsenixService {
    @GET("apple-music/lyrics")
    suspend fun lyrics(
        @Query("id") id: Long,
        @Query("ttml") ttml: Boolean? = null,
        @Query("v") version: Int? = null,
    ): Response<ResponseBody>
}

/**
 * APPLE MUSIC (via Lyrically) — a melhor sincronia que existe.
 *
 * Duas etapas: a busca da iTunes resolve "artista + faixa" no `trackId` da
 * Apple Music, e o `/apple-music/lyrics` devolve o envelope com o tempo de
 * CADA SÍLABA (`timestamp`/`endtime` em milissegundos). O campo `part`
 * marca pedaço de palavra ("with" + "drawals"), que precisa ser colado sem
 * espaço.
 *
 * Quando a faixa não tem tempo de sílaba, o mesmo envelope traz `lrc` (LRC
 * comum) e `plain` (texto puro) como reserva.
 */
class AppleMusicLyricsApi(
    private val itunes: ITunesService =
        LyricsNetwork.service("https://itunes.apple.com/", ITunesService::class.java),
    private val paxsenix: PaxsenixService =
        LyricsNetwork.service("https://lyrics.paxsenix.org/", PaxsenixService::class.java),
) : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.APPLE_MUSIC

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val title = query.title.trim()
        if (title.isBlank()) return null
        val artist = query.artist.trim()

        val trackId = searchITunes(title, artist, query.durationSec)?.trackId ?: return null

        // O TTML direto traz tempo por sílaba; o envelope estruturado fica
        // como reserva.
        fetchTtmlLines(trackId)?.let { lines ->
            return SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = lines.any { it.hasSyllables },
                plainLyrics = lines.joinToString("\n") { it.text },
            )
        }

        val envelope = fetchEnvelope(trackId) ?: return null

        val lines = mapLines(envelope.lyrics)
        if (lines.isNotEmpty()) {
            return SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = lines.any { it.hasSyllables },
                plainLyrics = lines.joinToString("\n") { it.text },
            )
        }

        envelope.lrc?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lrcLines = LyricsLrc.parse(lrc).takeIf { it.isNotEmpty() }
            if (lrcLines != null) {
                return SourceLyrics(
                    lines = lrcLines,
                    isSynced = true,
                    plainLyrics = envelope.plain?.takeIf { it.isNotBlank() }
                        ?: lrcLines.joinToString("\n") { it.text },
                )
            }
        }

        envelope.plain?.takeIf { it.isNotBlank() }?.let { plain ->
            return SourceLyrics(lines = emptyList(), isSynced = false, plainLyrics = plain.trim())
        }
        return null
    }

    /** Resolve o `trackId` da Apple Music pelo nome da faixa. */
    private suspend fun searchITunes(title: String, artist: String, durationSec: Int?): ITunesSong? {
        findBest(title, artist, durationSec)?.let { return it }
        val cleanTitle = LyricsMatchers.cleanTrackTitle(title)
        val cleanArtist = LyricsMatchers.cleanArtistName(artist)
        if (cleanTitle != title || cleanArtist != artist) {
            return findBest(cleanTitle, cleanArtist, durationSec)
        }
        return null
    }

    private suspend fun findBest(title: String, artist: String, durationSec: Int?): ITunesSong? {
        val term = if (artist.isNotBlank()) "$title $artist" else title
        val results = try {
            val response = itunes.search(term = term, limit = 5)
            if (!response.isSuccessful) return null
            response.body()?.results.orEmpty()
        } catch (_: Exception) {
            return null
        }
        if (results.isEmpty()) return null
        val expectedMs = durationSec?.takeIf { it > 0 }?.times(1000L)
        val cleanReqTitle = LyricsMatchers.cleanTrackTitle(title)
        val cleanReqArtist = LyricsMatchers.cleanArtistName(artist)
        return results
            .filter { it.trackId > 0 }
            .filter { isValidMatch(it, title, artist, cleanReqTitle, cleanReqArtist, expectedMs) }
            .minWithOrNull(
                compareBy<ITunesSong>(
                    {
                        !it.trackName.equals(title, ignoreCase = true) &&
                            !LyricsMatchers.cleanTrackTitle(it.trackName.orEmpty())
                            .equals(cleanReqTitle, ignoreCase = true)
                    },
                    {
                        artist.isNotBlank() && !it.artistName.equals(artist, ignoreCase = true) &&
                            !LyricsMatchers.cleanArtistName(it.artistName.orEmpty())
                            .equals(cleanReqArtist, ignoreCase = true)
                    },
                    { durationDiff(it.trackTimeMillis, expectedMs) },
                )
            )
    }

    private fun isValidMatch(
        song: ITunesSong,
        rawTitle: String,
        rawArtist: String,
        cleanReqTitle: String,
        cleanReqArtist: String,
        expectedMs: Long?,
    ): Boolean {
        val songTitle = song.trackName ?: return false
        val songArtist = song.artistName.orEmpty()
        if (!LyricsMatchers.sameVersion(rawTitle, songTitle)) return false
        val cleanSongTitle = LyricsMatchers.cleanTrackTitle(songTitle)
        val titleMatches = cleanSongTitle.equals(cleanReqTitle, ignoreCase = true) ||
            LyricsMatchers.titlesMatch(cleanSongTitle, cleanReqTitle)
        if (!titleMatches) return false
        if (rawArtist.isNotBlank() && !artistCreditsMatch(songArtist, rawArtist)) return false
        if (expectedMs != null && expectedMs > 0 && song.trackTimeMillis != null && song.trackTimeMillis > 0) {
            if (kotlin.math.abs(song.trackTimeMillis - expectedMs) > MAX_DURATION_DIFF_MS) return false
        }
        return true
    }

    /**
     * A iTunes devolve a linha de crédito COMPLETA ("Badshah, Shreya Ghoshal
     * & Hiten"), e o YouTube Music às vezes devolve só o artista principal.
     * O pedido casa com a linha inteira ou com um dos creditados.
     */
    private fun artistCreditsMatch(candidate: String, request: String): Boolean {
        val candidateCredits = splitArtistCredits(candidate)
        val requestCredits = splitArtistCredits(request)
        return candidateCredits.any { candidateCredit ->
            requestCredits.any { requestCredit -> artistNamesMatch(candidateCredit, requestCredit) }
        }
    }

    private fun splitArtistCredits(raw: String): List<String> =
        LyricsMatchers.cleanArtistName(raw)
            .split(ARTIST_CREDIT_SEPARATOR)
            .map { it.normalizeArtistName() }
            .filter(String::isNotBlank)

    private fun artistNamesMatch(candidate: String, request: String): Boolean {
        if (candidate == request) return true
        if (candidate.contains(request) || request.contains(candidate)) {
            return minOf(candidate.length, request.length).toDouble() /
                maxOf(candidate.length, request.length) >= 0.6
        }
        // Mantém a tolerância a erro de digitação entre nomes do mesmo
        // tamanho, sem aceitar um nome a mais ("Vicky Badshah" pra "Badshah").
        return candidate.split(' ').size == request.split(' ').size &&
            LyricsMatchers.artistMatches(candidate, request)
    }

    private fun String.normalizeArtistName(): String = lowercase()
        .replace(ARTIST_PUNCTUATION, " ")
        .trim()
        .replace(ARTIST_WHITESPACE, " ")

    private fun durationDiff(songMs: Long?, expectedMs: Long?): Long {
        if (songMs == null || expectedMs == null || songMs <= 0 || expectedMs <= 0) return 0L
        return kotlin.math.abs(songMs - expectedMs)
    }

    /** O TTML cru (com `ttml=true`). */
    private suspend fun fetchTtmlLines(trackId: Long): List<LyricLine>? {
        val body = rawBody(trackId, ttml = true, version = null) ?: return null
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        // O endpoint responde TTML direto ou um envelope JSON com ele dentro.
        val ttml = extractTtml(trimmed) ?: trimmed
        if ("<tt" !in ttml.lowercase() && "http://www.w3.org/ns/ttml" !in ttml) return null
        return TtmlParser.parse(ttml).takeIf { it.isNotEmpty() }
    }

    /** O envelope estruturado (`v=2`). */
    private suspend fun fetchEnvelope(trackId: Long): PaxEnvelope? {
        val body = rawBody(trackId, ttml = null, version = 2) ?: return null
        return try {
            LyricsNetwork.json.decodeFromString<PaxEnvelope>(body).takeIf {
                it.lyrics.isNotEmpty() || !it.lrc.isNullOrBlank() || !it.plain.isNullOrBlank()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun rawBody(trackId: Long, ttml: Boolean?, version: Int?): String? {
        return try {
            val response = paxsenix.lyrics(id = trackId, ttml = ttml, version = version)
            if (!response.isSuccessful) return null
            response.body()?.string()
        } catch (_: Exception) {
            null
        }
    }

    /** Acha a string TTML dentro do JSON, se o endpoint devolver envelope. */
    private fun extractTtml(raw: String): String? {
        if (!raw.startsWith("{") && !raw.startsWith("[")) return raw
        return try {
            val element = LyricsNetwork.json.parseToJsonElement(raw)
            findTtmlString(element)
        } catch (_: Exception) {
            null
        }
    }

    private fun findTtmlString(element: kotlinx.serialization.json.JsonElement): String? {
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> if (element.isString) {
                element.content.takeIf { "<tt" in it.lowercase() }
            } else {
                null
            }

            is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { findTtmlString(it) }
            is kotlinx.serialization.json.JsonObject -> {
                val keys = listOf("ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "data", "result")
                keys.firstNotNullOfOrNull { key -> element[key]?.let { findTtmlString(it) } }
                    ?: element.values.firstNotNullOfOrNull { findTtmlString(it) }
            }
        }
    }

    companion object {
        /** Diferença de duração tolerada entre a faixa e a versão da loja. */
        const val MAX_DURATION_DIFF_MS = 12_000L

        private val ARTIST_CREDIT_SEPARATOR = Regex(
            """\s*(?:,|&|;|/|\s+and\s+|\s+with\s+|\s+feat(?:uring)?\.?\s+|\s+ft\.?\s+|\s+x\s+)\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val ARTIST_PUNCTUATION = Regex("[^\\p{L}\\p{N}]+")
        private val ARTIST_WHITESPACE = Regex("\\s+")

        /**
         * Envelope de sílabas → [LyricLine]. Palavra `part` continua a
         * anterior ("with"+"drawals"); vocal de fundo viaja junto como
         * sílaba de fundo, sem sujar o texto da linha. Verso cantado só pelo
         * fundo ainda vira linha, pra nenhuma parte da música sumir.
         */
        internal fun mapLines(lines: List<PaxLine>): List<LyricLine> {
            return lines.mapNotNull { line ->
                val words = line.text.filter { it.text.isNotBlank() }
                val leads = words.isNotEmpty()
                val tokens = mutableListOf<String>()
                val syllables = mutableListOf<LyricSyllable>()
                val sourceWords = if (leads) words else line.backgroundText.filter { it.text.isNotBlank() }
                if (sourceWords.isEmpty()) return@mapNotNull null
                for (word in sourceWords) {
                    val text = word.text.trim()
                    val continues = leads && word.part && tokens.isNotEmpty()
                    if (continues) {
                        tokens[tokens.lastIndex] += text
                    } else {
                        tokens += text
                    }
                    syllables += LyricSyllable(
                        timeMs = word.timestamp.coerceAtLeast(0L),
                        durationMs = (
                            word.duration.takeIf { it > 0 }
                                ?: (word.endtime - word.timestamp)
                            ).coerceAtLeast(0L),
                        text = text,
                        isBackground = line.background || !leads,
                        appendToPrevious = continues,
                    )
                }
                if (leads) {
                    for (word in line.backgroundText.filter { it.text.isNotBlank() }) {
                        syllables += LyricSyllable(
                            timeMs = word.timestamp.coerceAtLeast(0L),
                            durationMs = (
                                word.duration.takeIf { it > 0 }
                                    ?: (word.endtime - word.timestamp)
                                ).coerceAtLeast(0L),
                            text = word.text.trim(),
                            isBackground = true,
                        )
                    }
                }
                val lineDuration = line.duration.takeIf { it > 0 }
                    ?: (line.endtime - line.timestamp).coerceAtLeast(0L)
                LyricLine(
                    timeMs = line.timestamp.coerceAtLeast(0L),
                    durationMs = lineDuration,
                    text = tokens.joinToString(" "),
                    syllables = syllables.sortedBy { it.timeMs },
                )
            }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.let(::clampEdges)
        }

        /**
         * Tempo de ponta a ponta: uma linha nunca fica acesa além do começo
         * da próxima, e uma sílaba nunca além da próxima sílaba (ou do fim
         * da linha). Sem isso, duas linhas ficam acesas ao mesmo tempo em
         * cada virada de verso.
         */
        internal fun clampEdges(lines: List<LyricLine>): List<LyricLine> {
            if (lines.size < 2) return lines
            return lines.mapIndexed { index, line ->
                val nextStart = lines.getOrNull(index + 1)?.timeMs
                var duration = line.durationMs
                if (nextStart != null && nextStart > line.timeMs &&
                    line.timeMs + duration > nextStart
                ) {
                    duration = (nextStart - line.timeMs).coerceAtLeast(100L)
                }
                val lineEnd = line.timeMs + duration
                val syllables = line.syllables
                if (syllables.size < 2) {
                    if (duration != line.durationMs) line.copy(durationMs = duration) else line
                } else {
                    var changed = duration != line.durationMs
                    val clamped = syllables.mapIndexed { si, syl ->
                        val nextSyl = syllables.getOrNull(si + 1)
                        var end = syl.timeMs + syl.durationMs
                        if (nextSyl != null && end > nextSyl.timeMs) {
                            end = nextSyl.timeMs
                            changed = true
                        }
                        if (end > lineEnd) {
                            end = lineEnd
                            changed = true
                        }
                        if (end < syl.timeMs) {
                            end = syl.timeMs
                            changed = true
                        }
                        if (end - syl.timeMs != syl.durationMs) syl.copy(durationMs = end - syl.timeMs) else syl
                    }
                    if (changed) line.copy(durationMs = duration, syllables = clamped) else line
                }
            }
        }
    }
}
