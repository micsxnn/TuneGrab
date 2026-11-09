package com.tunegrab.app.lyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** O registro que a LRCLIB devolve (um por faixa). */
@Serializable
data class LrclibRecord(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("albumName") val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

/** A API da LRCLIB (https://lrclib.net), a fonte que já existia no app. */
interface LrclibService {
    /** Casamento exato: 404 quando não tem. */
    @GET("get")
    suspend fun get(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String? = null,
        @Query("duration") duration: Int? = null,
    ): Response<LrclibRecord>

    /** Busca: devolve a lista de candidatos. */
    @GET("search")
    suspend fun search(
        @Query("q") query: String? = null,
        @Query("track_name") trackName: String? = null,
        @Query("artist_name") artistName: String? = null,
    ): Response<List<LrclibRecord>>
}

/**
 * LRCLIB — o catálogo comunitário de letra com marcação de tempo.
 *
 * É a fonte que o TuneGrab já usava desde a v0.22.7 e continua sendo a
 * primeira a responder (não pede chave, não pede cadastro e tem o catálogo
 * mais forte do mundo em letra de linha sincronizada).
 *
 * A escada de busca é a mesma da v0.22.8, que foi provada em sonda real
 * (28/28 com nome sujo do YouTube): casamento exato, depois busca por
 * artista+faixa, depois busca solta, e por último busca só pelo nome da
 * faixa (acha "Favela Vive 2", "Tudo Bem" — nomes comuns que a busca cheia
 * engole). Cada degrau só vale se TROUXE letra: registro achado sem letra
 * não para a escada.
 */
class LrclibLyricsApi(
    private val service: LrclibService = LyricsNetwork.service("https://lrclib.net/api/", LrclibService::class.java),
) : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.LRCLIB

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isBlank() || artist.isBlank()) return null
        val duration = query.durationSec?.takeIf { it > 0 }

        // 1. Casamento exato, do mais estrito pro mais frouxo: álbum e
        //    duração errados não podem dar 404 numa letra que existe.
        exact(title, artist, query.album, duration)?.let { return it.toSource() }
        exact(title, artist, null, duration)?.let { return it.toSource() }
        exact(title, artist, null, null)?.let { return it.toSource() }

        // 2. Título limpo (sem "feat. X", "[Official Video]") e repete.
        val cleanTitle = LyricsMatchers.cleanTrackTitle(title)
        val cleanArtist = LyricsMatchers.cleanArtistName(artist)
        if (cleanTitle != title || cleanArtist != artist) {
            exact(cleanTitle, cleanArtist, query.album, duration)?.let { return it.toSource() }
            exact(cleanTitle, cleanArtist, null, duration)?.let { return it.toSource() }
            exact(cleanTitle, cleanArtist, null, null)?.let { return it.toSource() }
        }

        // 3. Busca com tolerância de duração em degraus.
        search(title = cleanTitle, artist = cleanArtist, duration = duration, rawTitle = title)
            ?.let { return it.toSource() }
        search(title = title, artist = artist, duration = duration, rawTitle = title)
            ?.let { return it.toSource() }
        return null
    }

    private suspend fun exact(
        title: String,
        artist: String,
        album: String?,
        duration: Int?,
    ): LrclibRecord? {
        return try {
            val response = service.get(
                trackName = title,
                artistName = artist,
                albumName = album?.takeIf { it.isNotBlank() },
                duration = duration,
            )
            if (!response.isSuccessful) return null
            response.body()?.takeIf { it.hasLyrics() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun search(
        title: String,
        artist: String,
        duration: Int?,
        rawTitle: String,
    ): LrclibRecord? {
        return try {
            val response = service.search(
                query = "$artist $title".trim(),
                trackName = title,
                artistName = artist,
            )
            if (!response.isSuccessful) return null
            val list = response.body().orEmpty()
            if (list.isEmpty()) return null

            // Artista e versão são OBRIGATÓRIOS: a busca da LRCLIB é por
            // texto e devolve muita letra de outra música com nome parecido.
            // A duração é só desempate (o clipe do YouTube tem duração
            // diferente do áudio do catálogo).
            data class Scored(val record: LrclibRecord, val delta: Double)

            val matched = list.mapNotNull { candidate ->
                val candArtist = LyricsMatchers.cleanArtistName(candidate.artistName.orEmpty())
                val reqArtist = LyricsMatchers.cleanArtistName(artist)
                if (!LyricsMatchers.artistMatches(candArtist, reqArtist)) return@mapNotNull null

                val candRawTitle = candidate.trackName ?: candidate.name.orEmpty()
                // Nunca servir a gravação errada: marca de ao vivo/remix/cover
                // num lado e não no outro rejeita o registro.
                if (!LyricsMatchers.sameVersion(rawTitle, candRawTitle)) return@mapNotNull null

                val candTitle = LyricsMatchers.cleanTrackTitle(candRawTitle)
                val reqTitle = LyricsMatchers.cleanTrackTitle(title)
                if (!LyricsMatchers.titlesMatch(candTitle, reqTitle)) return@mapNotNull null

                val delta = if (duration != null && candidate.duration != null && candidate.duration > 0) {
                    kotlin.math.abs(candidate.duration - duration)
                } else {
                    0.0
                }
                Scored(candidate, delta)
            }.sortedBy { it.delta }

            if (matched.isEmpty()) return null

            fun pick(predicate: (LrclibRecord) -> Boolean, maxDelta: Double): LrclibRecord? =
                matched.firstOrNull { it.delta <= maxDelta && predicate(it.record) }?.record

            // Letra com tempo primeiro, depois só texto — cada um nos seus
            // dois degraus de duração.
            pick({ !it.syncedLyrics.isNullOrBlank() }, LyricsMatchers.STRICT_DURATION_DELTA)
                ?: pick({ !it.syncedLyrics.isNullOrBlank() }, LyricsMatchers.RELAXED_DURATION_DELTA)
                ?: matched.firstOrNull { !it.record.syncedLyrics.isNullOrBlank() }?.record
                ?: pick({ !it.plainLyrics.isNullOrBlank() }, LyricsMatchers.STRICT_DURATION_DELTA)
                ?: pick({ !it.plainLyrics.isNullOrBlank() }, LyricsMatchers.RELAXED_DURATION_DELTA)
                ?: matched.firstOrNull { !it.record.plainLyrics.isNullOrBlank() }?.record
                ?: matched.firstOrNull { it.record.instrumental == true }?.record
        } catch (_: Exception) {
            null
        }
    }

    private fun LrclibRecord.hasLyrics(): Boolean =
        !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental == true

    private fun LrclibRecord.toSource(): SourceLyrics? {
        val syncedRaw = syncedLyrics?.takeIf { it.isNotBlank() }
        val plain = plainLyrics.orEmpty()
        val isInstrumental = instrumental == true
        val lines = syncedRaw?.let { LyricsLrc.parse(it) }.orEmpty()
        if (!isInstrumental && lines.isEmpty() && plain.isBlank()) return null
        return SourceLyrics(
            lines = lines,
            isSynced = lines.isNotEmpty(),
            isWordSynced = lines.any { it.hasSyllables },
            plainLyrics = plain.ifBlank { lines.joinToString("\n") { it.text } },
            isInstrumental = isInstrumental,
        )
    }
}
