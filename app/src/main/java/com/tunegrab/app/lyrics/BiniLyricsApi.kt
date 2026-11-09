package com.tunegrab.app.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap
import retrofit2.http.Url

@Serializable
data class BiniSearchResponse(
    val total: Int? = null,
    val source: String? = null,
    val results: List<BiniHit> = emptyList(),
)

@Serializable
data class BiniHit(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    val duration: Int? = null,
    val isrc: String? = null,
    @SerialName("timing_type") val timingType: String? = null,
    val lyricsUrl: String? = null,
)

/** Syllable-Sync (https://lyrics-api.binimum.org): TTML casado com a gravação. */
interface BiniService {
    /** A URL muda com a consulta (o endpoint é a raiz da API). */
    @GET
    suspend fun search(
        @Url url: String,
        @QueryMap params: Map<String, String>,
    ): Response<BiniSearchResponse>
}

/**
 * SYLLABLE-SYNC (Bini) — TTML casado com a GRAVAÇÃO, e traz o ISRC.
 *
 * Duas coisas que nenhuma outra fonte faz junto:
 *
 *  1. a letra é da gravação certa (o catálogo casa pela gravação, não pelo
 *     nome solto), com tempo por sílaba;
 *  2. o resultado traz o **ISRC** — o código que identifica a gravação no
 *     mundo todo. Com ele, a fonte lrc.red (que é indexada SÓ por ISRC)
 *     entra na jogada.
 *
 * A busca é por nome, então duas formas são tentadas: a "comformada"
 * (faixa, artista, álbum, duração) e a busca solta. Cada uma passa pelo
 * filtro de versão, título e artista.
 */
class BiniLyricsApi(
    private val service: BiniService =
        LyricsNetwork.service("https://lyrics-api.binimum.org/", BiniService::class.java),
) : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.BINI

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val hit = identify(query) ?: return null
        val lines = fetchLinesFor(hit) ?: return null
        return SourceLyrics(
            lines = lines,
            isSynced = true,
            isWordSynced = lines.any { it.hasSyllables },
            plainLyrics = lines.joinToString("\n") { it.text },
        )
    }

    /**
     * Acha o registro da gravação. Se o [LyricsQuery] já tiver ISRC, ele
     * manda: o ISRC nomeia a gravação sem ambiguidade.
     */
    suspend fun identify(query: LyricsQuery): BiniHit? {
        val wantedIsrc = query.isrc?.trim()
        if (!wantedIsrc.isNullOrBlank()) {
            // O ISRC nomeia a gravação sem ambiguidade: pega direto.
            val byIsrc = runQuery(listOf("isrc" to wantedIsrc))
                .sortedByDescending { it.timingType.equals("word", ignoreCase = true) }
                .firstOrNull()
            if (byIsrc != null) return byIsrc
        }
        val title = query.title.trim()
        if (title.isBlank()) return null
        val artist = query.artist.trim()
        val shaped = buildList {
            add("track" to title)
            add("artist" to artist)
            query.album?.takeIf { it.isNotBlank() }?.let { add("album" to it.trim()) }
            query.durationSec?.takeIf { it > 0 }?.let { add("duration" to it.toString()) }
        }
        // A busca "comformada" erra uma, a busca solta acerta a outra:
        // tenta as duas antes de desistir.
        val shapedHit = selectBest(runQuery(shaped), title, artist, query.durationSec)
        if (shapedHit != null) return shapedHit
        val freeHit = selectBest(
            runQuery(listOf("q" to if (artist.isBlank()) title else "$artist - $title")),
            title,
            artist,
            query.durationSec,
        )
        return freeHit
    }

    private suspend fun runQuery(params: List<Pair<String, String>>): List<BiniHit> {
        return try {
            val response = service.search(
                url = "https://lyrics-api.binimum.org/",
                params = params.toMap(),
            )
            if (!response.isSuccessful) return emptyList()
            response.body()?.results.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * O catálogo pode devolver várias versões do mesmo nome (ao vivo, remix,
     * cover): só a MESMA gravação passa, com os dois lados concordando sobre
     * título e artista. Arquivo com tempo por palavra vem primeiro, depois
     * o de duração mais parecida. O que fica abaixo do piso é rejeitado,
     * pra corrida cair na próxima fonte.
     */
    private fun selectBest(
        hits: List<BiniHit>,
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): BiniHit? {
        if (hits.isEmpty()) return null
        return hits
            .filter { LyricsMatchers.sameVersion(title, it.trackName.orEmpty()) }
            .map { it to scoreHit(it, title, artist, durationSeconds) }
            .filter { (_, score) -> score >= MIN_HIT_SCORE }
            .sortedWith(
                compareByDescending<Pair<BiniHit, Int>> { (_, score) -> score }
                    .thenByDescending { (hit, _) -> hit.timingType.equals("word", ignoreCase = true) }
                    .thenBy { (hit, _) -> durationDelta(hit, durationSeconds) },
            )
            .firstOrNull()?.first
    }

    /** Baixa o documento TTML do registro e lê as linhas. */
    suspend fun fetchLinesFor(hit: BiniHit): List<LyricLine>? {
        val documentUrl = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return null
        val request = Request.Builder()
            .url(documentUrl)
            .header("User-Agent", LyricsNetwork.USER_AGENT)
            .header("Accept", "application/xml, text/xml, */*")
            .get()
            .build()
        val ttml = try {
            LyricsNetwork.http.newCall(request).awaitBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null
        return TtmlParser.parse(ttml).takeIf { it.isNotEmpty() }
    }

    private fun durationDelta(hit: BiniHit, durationSeconds: Int?): Long {
        if (durationSeconds == null || durationSeconds <= 0 || (hit.duration ?: 0) <= 0) return 0L
        return kotlin.math.abs((hit.duration ?: 0) - durationSeconds).toLong()
    }

    companion object {
        /**
         * Piso da pontuação: título exato (3) + artista batendo (2). Um
         * resultado com título só parecido (1 + 2) nunca passa sozinho —
         * homônimos ficam de fora.
         */
        const val MIN_HIT_SCORE = 5

        fun scoreHit(hit: BiniHit, title: String, artist: String, durationSeconds: Int?): Int {
            var score = 0
            val candTitle = LyricsMatchers.cleanTrackTitle(hit.trackName.orEmpty())
            val reqTitle = LyricsMatchers.cleanTrackTitle(title)
            if (candTitle.equals(reqTitle, ignoreCase = true)) {
                score += 3
            } else if (LyricsMatchers.titlesMatch(candTitle, reqTitle)) {
                score += 1
            }
            val candArtist = LyricsMatchers.cleanArtistName(hit.artistName.orEmpty())
            val reqArtist = LyricsMatchers.cleanArtistName(artist)
            if (LyricsMatchers.artistMatches(candArtist, reqArtist)) score += 2
            if (durationSeconds != null && durationSeconds > 0 && (hit.duration ?: 0) > 0) {
                score += when (kotlin.math.abs((hit.duration ?: 0) - durationSeconds)) {
                    in 0..3 -> 3
                    in 4..10 -> 1
                    else -> 0
                }
            }
            return score
        }
    }
}

/**
 * LRC.RED (https://lrc.red) — arquivo indexado DIRETO pelo ISRC.
 *
 * É a fonte mais precisa que existe: o ISRC identifica a gravação no mundo
 * todo, então a letra é da gravação certa ou não existe. Devolve o TTML da
 * Apple (tempo por sílaba) e, se não tiver, o LRC.
 *
 * Só funciona com ISRC em mãos — quem resolve é o Syllable-Sync.
 */
class LrcRedLyricsApi : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.LRC_RED

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val isrc = query.isrc?.takeIf { it.isNotBlank() } ?: return null
        return fetchForIsrc(isrc)
    }

    /** Busca pelo ISRC: tenta o TTML (sílaba) e depois o LRC (linha). */
    suspend fun fetchForIsrc(rawIsrc: String): SourceLyrics? {
        val isrc = rawIsrc.trim().uppercase().filter { it.isLetterOrDigit() }
        if (isrc.length != 12) return null
        val ttml = LyricsNetwork.getText(
            "https://lrc.red/s/$isrc.ttml",
            LyricsNetwork.USER_AGENT,
            "application/xml, text/xml, */*",
        )
        if (!ttml.isNullOrBlank()) {
            val lines = TtmlParser.parse(ttml).takeIf { it.isNotEmpty() }
            if (lines != null) {
                return SourceLyrics(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = lines.any { it.hasSyllables },
                    plainLyrics = lines.joinToString("\n") { it.text },
                )
            }
        }
        val lrc = LyricsNetwork.getText(
            "https://lrc.red/s/$isrc.lrc",
            LyricsNetwork.USER_AGENT,
            "text/plain, */*",
        )
        if (!lrc.isNullOrBlank()) {
            val lines = LyricsLrc.parse(lrc).takeIf { it.isNotEmpty() } ?: return null
            return SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = lines.any { it.hasSyllables },
                plainLyrics = lines.joinToString("\n") { it.text },
            )
        }
        return null
    }
}
