package com.tunegrab.app.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class LyricsPlusResponse(
    val type: String? = null,
    val lyrics: List<LyricsPlusLine>? = null,
)

@Serializable
data class LyricsPlusLine(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    @SerialName("syllabus") val syllabus: List<LyricsPlusSyllable>? = null,
)

@Serializable
data class LyricsPlusSyllable(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
)

/**
 * LYRICSPLUS (YouLy+) — o agregador que junta Apple, QQ e Musixmatch e
 * entrega o tempo de cada SÍLABA.
 *
 * O problema da fonte é a hospedagem: ela roda em espelhos mantidos por
 * gente boa e, a qualquer momento, a maioria está lotada ou fora do ar. Por
 * isso TODOS os espelhos são consultados ao mesmo tempo e vence o primeiro
 * que responde com letra de verdade (não o primeiro que responde 404). O
 * espelho vencedor fica anotado, então a próxima música vai direto pra ele.
 */
class LyricsPlusLyricsApi : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.LYRICS_PLUS

    private val lastGood = AtomicReference<String?>(null)

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? = withContext(Dispatchers.IO) {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val hosts = lastGood.get()
            ?.let { remembered -> listOf(remembered) + MIRRORS.filterNot { it == remembered } }
            ?: MIRRORS

        coroutineScope {
            val pending = hosts.map { host ->
                host to async(Dispatchers.IO) { fetchFrom(host, query) }
            }.toMutableList()
            try {
                while (pending.isNotEmpty()) {
                    val (host, lines) = select {
                        pending.forEach { (candidate, job) -> job.onAwait { candidate to it } }
                    }
                    pending.removeAll { it.first == host }
                    if (lines != null) {
                        lastGood.set(host)
                        return@coroutineScope lines
                    }
                }
                null
            } finally {
                pending.forEach { it.second.cancel() }
            }
        }
    }

    private suspend fun fetchFrom(host: String, query: LyricsQuery): SourceLyrics? {
        val builder = "$host/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        builder.addQueryParameter("title", query.title.trim())
        builder.addQueryParameter("artist", query.artist.trim())
        query.durationSec?.takeIf { it > 0 }?.let { builder.addQueryParameter("duration", it.toString()) }
        query.album?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("album", it.trim()) }
        // O ISRC vai JUNTO do nome, não no lugar dele: o backend junta
        // vários catálogos e nem todos indexam por ISRC.
        query.isrc?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("isrc", it.trim()) }

        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", LyricsNetwork.USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            val body = LyricsNetwork.http.newCall(request).awaitBodyOrNull() ?: return null
            val response = runCatching {
                LyricsNetwork.json.decodeFromString<LyricsPlusResponse>(body)
            }.getOrNull() ?: return null
            val lines = parse(response).takeIf { it.isNotEmpty() } ?: return null
            SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = lines.any { it.hasSyllables },
                plainLyrics = lines.joinToString("\n") { it.text },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    internal fun parse(response: LyricsPlusResponse): List<LyricLine> {
        return response.lyrics.orEmpty().mapNotNull { line ->
            val start = line.time ?: return@mapNotNull null
            val words = mergeSyllables(line.syllabus.orEmpty())
            when {
                words.isNotEmpty() -> LyricLine(
                    timeMs = minOf(start, words.first().timeMs),
                    durationMs = line.duration?.takeIf { it > 0 } ?: 0L,
                    text = words.joinToString(" ") { it.text },
                    syllables = words,
                )
                // Algumas faixas só têm tempo de linha: ainda vale mostrar.
                !line.text.isNullOrBlank() -> LyricLine(
                    timeMs = start,
                    durationMs = line.duration?.takeIf { it > 0 } ?: 0L,
                    text = line.text.trim(),
                )

                else -> null
            }
        }.sortedBy { it.timeMs }
    }

    /**
     * Junta as sílabas de volta em palavras: o ESPAÇO da API é a fronteira
     * da palavra — ela manda "e" e depois "nough ", e o espaço no fim é a
     * única coisa dizendo que são a mesma palavra. Cada palavra montada
     * guarda o próprio tempo, então o varredura de karaokê continua exato.
     */
    internal fun mergeSyllables(syllables: List<LyricsPlusSyllable>): List<LyricSyllable> {
        val words = mutableListOf<LyricSyllable>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L

        fun flush() {
            if (current.isNotEmpty()) {
                words += LyricSyllable(
                    timeMs = start,
                    durationMs = (maxOf(start, end) - start).coerceAtLeast(0L),
                    text = current.toString(),
                )
            }
            current.setLength(0)
        }

        for (syllable in syllables) {
            val raw = syllable.text ?: continue
            val content = raw.trim()
            if (content.isEmpty()) {
                flush()
                continue
            }
            val time = syllable.time ?: continue
            // Um pedaço que nem termina em espaço nem é seguido de um é a
            // frente de uma palavra que continua. Escritas sem espaço (Han,
            // kana, tailandês) não estão dizendo fronteira de palavra, então
            // cada pedaço ali é uma palavra só.
            val joins = current.isNotEmpty() && !raw.first().isWhitespace() &&
                !current.last().isUnspacedScript() && !content.first().isUnspacedScript()
            if (!joins) flush()
            if (current.isEmpty()) {
                start = time
                end = time
            }
            current.append(content)
            end = maxOf(end, time + (syllable.duration ?: 0L))
            if (raw.last().isWhitespace()) flush()
        }
        flush()
        return words
    }

    private companion object {
        val MIRRORS = listOf(
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app",
        )
    }
}

/** Escritas sem espaço entre as palavras. */
private fun Char.isUnspacedScript(): Boolean =
    this in '㐀'..'鿿' || // Han
        this in '぀'..'ヿ' || // hiragana e katakana
        this in '　'..'〿' || // pontuação CJK
        this in '＀'..'￯' || // formas de largura total
        this in '฀'..'๿' // tailandês
