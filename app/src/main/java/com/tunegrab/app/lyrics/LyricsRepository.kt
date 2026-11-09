package com.tunegrab.app.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * O ORQUESTRADOR DAS LETRAS.
 *
 * Todas as fontes são consultadas AO MESMO TEMPO e a melhor resposta vence.
 * A ordem de qualidade é:
 *
 *  1. letra com tempo de PALAVRA (acende palavra a palavra) — o melhor nível;
 *  2. letra com tempo de LINHA (acende a linha inteira);
 *  3. texto puro (serve, mas não sincroniza).
 *
 * Duas regras IMPORTANTES:
 *
 *  - **A corrida não espera a mais rápida, espera a melhor.** Uma fonte que
 *    responde em 300 ms com letra de LINHA não ganha de uma fonte que
 *    responde em 2 s com tempo de PALAVRA: a primeira fica de reserva e a
 *    corrida continua.
 *  - **Linha do tempo plausível.** Uma fonte que devolve a letra de outra
 *    gravação (mesmo nome, corte errado) é rejeitada pela duração: não pode
 *    passar 45 s do fim da música nem cobrir menos da metade de uma música
 *    de 3 minutos.
 *
 * A fonte também pode ser TRAVADA pelo usuário (a tela de letra tem um
 * botão de "Fonte"): aí só ela responde, sem corrida.
 */
object LyricsRepository {

    /** Teto da corrida: nenhuma fonte segura a tela por mais que isso. */
    const val RACE_TOTAL_MS = 14_000L

    /** Tempo pra resolver o ISRC da gravação (destrava o lrc.red). */
    const val IDENTIFY_TIMEOUT_MS = 3_000L

    /** Todas as fontes, na ordem em que a UI mostra. */
    val sources: List<LyricsSourceApi> = listOf(
        BetterLyricsApi(),
        AppleMusicLyricsApi(),
        KugouLyricsApi(),
        LyricsPlusLyricsApi(),
        UnisonLyricsApi(),
        BiniLyricsApi(),
        LrcRedLyricsApi(),
        SimpMusicLyricsApi(),
        YoutubeTranscriptApi(),
        LrclibLyricsApi(),
        MegalobizLyricsApi(),
        GeniusLyricsApi(),
    )

    private val bySource: Map<LyricsSource, LyricsSourceApi> = sources.associateBy { it.source }

    /** Cache de memória: voltar na mesma faixa não refaz a busca. */
    private val cache = object : LinkedHashMap<String, LyricsResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean =
            size > 24
    }

    private fun cacheKey(query: LyricsQuery, source: LyricsSource?): String =
        "${source?.id ?: "auto"}|${query.artist.lowercase()}|${query.title.lowercase()}|" +
            "${query.durationSec ?: 0}|${query.videoId.orEmpty()}"

    private fun lookup(key: String): LyricsResult? = synchronized(cache) { cache[key] }

    private fun remember(key: String, result: LyricsResult) = synchronized(cache) {
        cache[key] = result
        while (cache.size > 24) cache.remove(cache.keys.first())
    }

    /**
     * Procura a letra. [forced] trava uma fonte (null = corrida automática).
     *
     * Devolve [LyricsResult.Empty] quando nenhuma fonte achou. LANÇA
     * [IOException] quando o problema foi a REDE (aí a tela pede pra tentar
     * de novo, em vez de dizer que não existe letra).
     */
    suspend fun fetch(query: LyricsQuery, forced: LyricsSource? = null): LyricsResult {
        val wanted = forced ?: LyricsSource.AUTO
        val key = cacheKey(query, wanted)
        lookup(key)?.let { return it }

        if (wanted != LyricsSource.AUTO) {
            val api = bySource[wanted] ?: return LyricsResult.Empty
            val result = runSource(api, query)
            val mapped = result?.let { toResult(it, api.source) } ?: LyricsResult.Empty
            remember(key, mapped)
            return mapped
        }

        val outcome = race(query)
        val result = outcome.result ?: LyricsResult.Empty
        remember(key, result)
        if (result == LyricsResult.Empty && outcome.networkFailure) {
            throw IOException("rede indisponível")
        }
        return result
    }

    /** O que a corrida devolve: o melhor resultado e se a rede falhou. */
    private class RaceOutcome(val result: LyricsResult?, val networkFailure: Boolean)

    /** A resposta de uma fonte (com a marca de "a rede caiu"). */
    private class Attempt(
        val source: LyricsSource,
        val result: SourceLyrics?,
        val networkError: Boolean,
    )

    private suspend fun race(query: LyricsQuery): RaceOutcome {
        var lineFallback: SourceLyrics? = null
        var lineFallbackSource: LyricsSource? = null
        var anyAnswered = false
        var networkFailure = true

        val best = withTimeoutOrNull(RACE_TOTAL_MS) {
            coroutineScope {
                val pending = sources.map { api ->
                    api to async(Dispatchers.IO) {
                        try {
                            Attempt(api.source, api.fetch(query), false)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: IOException) {
                            Attempt(api.source, null, true)
                        } catch (_: Exception) {
                            Attempt(api.source, null, false)
                        }
                    }
                }.toMutableList()
                try {
                    while (pending.isNotEmpty()) {
                        // `select` aqui devolve a PRIMEIRA fonte que
                        // respondeu, não a primeira que respondeu bem: a
                        // qualidade é avaliada logo abaixo.
                        val (api, attempt) = select<Pair<LyricsSourceApi, Attempt>> {
                            pending.forEach { (candidate, deferred) -> deferred.onAwait { candidate to it } }
                        }
                        pending.removeAll { it.first == api }
                        if (!attempt.networkError) anyAnswered = true
                        val res = attempt.result ?: continue
                        val plausible = res.isInstrumental ||
                            LyricsLrc.plausibleDuration(res.lines, query.durationSec)
                        if (res.isWordSynced && plausible) {
                            return@coroutineScope attempt.source to res
                        }
                        if (res.lines.isNotEmpty() && plausible && lineFallback == null) {
                            lineFallback = res
                            lineFallbackSource = attempt.source
                        }
                    }
                    null
                } finally {
                    pending.forEach { it.second.cancel() }
                }
            }
        }

        networkFailure = !anyAnswered
        best?.let { (source, res) -> return RaceOutcome(toResult(res, source), false) }

        // A corrida devolveu só letra de linha (ou nada): antes de desistir,
        // tenta o caminho do ISRC, que é o ÚNICO jeito de chegar no lrc.red
        // (o arquivo é indexado pelo código da gravação).
        val isrcResult = fetchByIsrc(query)
        if (isrcResult != null) return RaceOutcome(isrcResult, false)

        if (lineFallback != null && lineFallbackSource != null) {
            return RaceOutcome(toResult(lineFallback!!, lineFallbackSource!!), false)
        }
        return RaceOutcome(null, networkFailure)
    }

    /**
     * Resolve o ISRC da gravação (Syllable-Sync sabe dizer) e tenta o
     * lrc.red, que é indexado SÓ por ISRC.
     */
    private suspend fun fetchByIsrc(query: LyricsQuery): LyricsResult? {
        val bini = bySource[LyricsSource.BINI] as? BiniLyricsApi ?: return null
        val isrc = query.isrc?.takeIf { it.isNotBlank() }
            ?: withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                runCatching { bini.identify(query)?.isrc }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            ?: return null
        val enriched = query.copy(isrc = isrc)
        val lrcRed = bySource[LyricsSource.LRC_RED] as? LrcRedLyricsApi
        lrcRed?.fetchForIsrc(isrc)?.let { return toResult(it, LyricsSource.LRC_RED) }
        bini.fetch(enriched)?.let { return toResult(it, LyricsSource.BINI) }
        return null
    }

    /** Chama UMA fonte, sem quebrar a corrida se ela estourar. */
    private suspend fun runSource(api: LyricsSourceApi, query: LyricsQuery): SourceLyrics? {
        return try {
            api.fetch(query)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Limpa e valida o que a fonte devolve antes de mostrar. */
    private fun toResult(res: SourceLyrics, source: LyricsSource): LyricsResult {
        val lines = LyricsSanitizer.sanitize(res.lines)
        val normalized = LyricsLrc.normalizeTiming(lines)
        val plain = LyricsSanitizer.sanitizePlain(res.plainLyrics)
            ?: normalized.joinToString("\n") { it.text }.ifBlank { null }
        if (normalized.isEmpty() && plain.isNullOrBlank() && !res.isInstrumental) {
            return LyricsResult.Empty
        }
        return LyricsResult.Success(
            lines = normalized,
            isSynced = res.isSynced && normalized.isNotEmpty(),
            isWordSynced = res.isWordSynced && normalized.any { it.hasSyllables },
            plainLyrics = plain,
            isInstrumental = res.isInstrumental,
            source = source,
            sourceLabel = source.label,
        )
    }
}
