package com.tunegrab.app.lyrics

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class UnisonResponse(
    val success: Boolean? = null,
    val data: UnisonEntry? = null,
)

@Serializable
data class UnisonEntry(
    val lyrics: String? = null,
    /** "ttml" ou "lrc". */
    val format: String? = null,
    /** "wordsync", "linesync" ou "plain". */
    val syncType: String? = null,
)

/** Unison (https://unison.boidu.dev): banco de letra comunitário. */
interface UnisonService {
    @GET("lyrics")
    suspend fun lyrics(
        @Query("song") song: String,
        @Query("artist") artist: String,
        @Query("album") album: String? = null,
        @Query("duration") duration: Int? = null,
    ): Response<UnisonResponse>
}

/**
 * UNISON — banco de letra CONTRIBUTADO pela comunidade.
 *
 * A cobertura é fina e desiguais, mas às vezes tem uma faixa que nenhum
 * catálogo licenciado tem. Três formatos saem de lá e o próprio registro diz
 * qual é: TTML no estilo Apple, LRC comum com marcação de tempo (reconhece
 * a marcação por palavra quando existe) ou texto puro sem tempo nenhum —
 * esse último é descartado aqui, porque os textos puros ficam no fim da
 * cadeia de qualquer jeito.
 */
class UnisonLyricsApi(
    private val service: UnisonService =
        LyricsNetwork.service("https://unison.boidu.dev/", UnisonService::class.java),
) : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.UNISON

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isBlank() || artist.isBlank()) return null
        return try {
            val response = service.lyrics(
                song = title,
                artist = artist,
                album = query.album?.takeIf { it.isNotBlank() },
                duration = query.durationSec?.takeIf { it > 0 },
            )
            if (!response.isSuccessful) return null
            val body = response.body() ?: return null
            if (body.success != true) return null
            val entry = body.data ?: return null
            val text = entry.lyrics?.takeIf { it.isNotBlank() } ?: return null
            val lines = parse(text, entry.format, entry.syncType) ?: return null
            SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = lines.any { it.hasSyllables },
                plainLyrics = lines.joinToString("\n") { it.text },
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Os três formatos, differentiate pelo que o registro diz que é. */
    internal fun parse(text: String, format: String?, syncType: String?): List<LyricLine>? {
        val lines = when {
            format.equals("ttml", ignoreCase = true) -> TtmlParser.parse(text)
            syncType.equals("plain", ignoreCase = true) -> emptyList()
            else -> LyricsLrc.parseEnhanced(text).takeIf { it.isNotEmpty() } ?: LyricsLrc.parse(text)
        }
        return lines.takeIf { it.isNotEmpty() }
    }
}

@Serializable
data class SimpMusicResponse(
    val success: Boolean = false,
    val data: List<SimpMusicTrack>? = null,
)

@Serializable
data class SimpMusicTrack(
    val duration: Int? = null,
    val richSyncLyrics: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
)

/** SimpMusic (https://api-lyrics.simpmusic.org): banco indexado pelo vídeo. */
interface SimpMusicService {
    @GET("{videoId}")
    suspend fun track(@Path("videoId") videoId: String): Response<SimpMusicResponse>
}

/**
 * VÍDEO (SimpMusic) — banco community que usa o ID do VÍDEO como chave.
 *
 * Isso é melhor do que casar pelo nome: ele acha a letra exatamente do
 * corte que está tocando, e não de uma edição com o mesmo nome. O rich sync
 * é LRC enriquecido (marca por palavra) e o LRC comum é a reserva.
 */
class SimpMusicLyricsApi(
    private val service: SimpMusicService =
        LyricsNetwork.service("https://api-lyrics.simpmusic.org/v1/", SimpMusicService::class.java),
) : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.SIMP_MUSIC

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val videoId = query.videoId?.trim().orEmpty()
        if (videoId.isBlank()) return null
        return try {
            val response = service.track(videoId)
            if (!response.isSuccessful) return null
            val body = response.body() ?: return null
            if (!body.success) return null
            val seconds = query.durationSec?.takeIf { it > 0 } ?: 0
            val track = body.data.orEmpty()
                .filter { seconds <= 0 || kotlin.math.abs((it.duration ?: 0) - seconds) <= DURATION_TOLERANCE_SECONDS }
                .minByOrNull { kotlin.math.abs((it.duration ?: 0) - seconds) }
                ?: return null
            val lines = track.richSyncLyrics?.takeIf { it.isNotBlank() }
                ?.let { LyricsLrc.parseEnhanced(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: track.syncedLyrics?.takeIf { it.isNotBlank() }
                    ?.let { LyricsLrc.parse(it) }
                    ?.takeIf { it.isNotEmpty() }
                ?: return null
            SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = lines.any { it.hasSyllables },
                plainLyrics = track.plainLyrics?.takeIf { it.isNotBlank() }
                    ?: lines.joinToString("\n") { it.text },
            )
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val DURATION_TOLERANCE_SECONDS = 10
    }
}
