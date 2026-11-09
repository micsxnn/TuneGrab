package com.tunegrab.app.lyrics

/**
 * MODELO DE LETRA (v0.22.17 — a casa de fundo do LastWave entrou no app).
 *
 * Antes a letra do TuneGrab era só um `LyricLine(timeMs, text)` e uma string
 * com o texto puro. Agora o modelo é o do LastWave: cada linha carrega as
 * SÍLABAS (as palavras com o tempo de cada uma), o papel vocal (quem canta a
 * linha: principal, segunda voz do dueto, coro, vocal de fundo) e o texto
 * "limpo" que vai pra tela.
 *
 * Por que a sílaba importa: com tempo por palavra a letra acende palavra a
 * palavra, que é o efeito de karaokê do Spotify e do YouTube Music. As
 * fontes que entregam só tempo de linha (LRCLIB, Megalobiz, lrc.red em LRC)
 * continuam funcionando — a linha acende inteira, sem sílaba.
 */
data class LyricSyllable(
    /** Momento da sílaba, em milissegundos do começo da música. */
    val timeMs: Long,
    /** Quanto tempo a sílaba dura (o próximo evento corta o resto). */
    val durationMs: Long,
    /** A palavra (ou pedaço de palavra) que acende. */
    val text: String,
    /** Vocal de fundo: acende apagado, nunca como a voz principal. */
    val isBackground: Boolean = false,
    /**
     * A sílaba CONTINUA a anterior (a Apple manda "with" + "drawals" como
     * duas partes da mesma palavra). A tela não pode botar espaço entre elas.
     */
    val appendToPrevious: Boolean = false,
)

/**
 * Quem canta a linha. A tela usa isso pra separar dueto (voz principal à
 * esquerda, segunda voz à direita) e coro (no centro).
 */
enum class VocalRole {
    LEAD_LEFT,
    DUET_RIGHT,
    UNISON_CENTER,
    BACKGROUND_SUBLINE,
}

/** Uma linha da letra, com o tempo e (quando a fonte dá) as sílabas. */
data class LyricLine(
    val timeMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList(),
    val role: VocalRole = VocalRole.LEAD_LEFT,
) {
    val hasSyllables: Boolean get() = syllables.isNotEmpty()

    /** A linha é só vocal de fundo? (todas as sílabas marcadas como fundo) */
    val isBackgroundLine: Boolean
        get() = syllables.isNotEmpty() && syllables.all { it.isBackground }

    /** Texto em escrita da direita pra esquerda (árabe, hebraico)? */
    val isRtl: Boolean
        get() = isRtlText(text) || syllables.any { isRtlText(it.text) }
}

/**
 * As FONTES de letra. Cada uma é um catálogo diferente com uma cobertura
 * diferente; o app corre todas em paralelo e fica com a primeira que trouxer
 * letra WORDSYNC (o melhor nível), guardando a de LINESYNC como reserva.
 *
 * O usuário também pode TRAVAR uma fonte: a tela de letra tem um botão de
 * "Fonte" que abre esta lista, e a escolha fica valendo até ele trocar de
 * novo (o "Automático" volta pro modo corrida).
 */
enum class LyricsSource(val id: String, val label: String) {
    /** Corrida: quem responder primeiro com letra boa ganha. */
    AUTO("auto", "Automático"),

    /** LRCLIB: catálogo comunitário, linha sincronizada, sem chave. */
    LRCLIB("lrclib", "LRCLIB"),

    /** BetterLyrics: TTML da Apple com tempo por sílaba. */
    BETTER_LYRICS("better_lyrics", "BetterLyrics"),

    /** QQ Karaoke: LRC de karaokê com tempo por palavra. */
    QQ_KARAOKE("qq_karaoke", "QQ Karaoke"),

    /** Kugou: KRC criptografado com tempo por sílaba. */
    KUGOU("kugou", "Kugou"),

    /** LyricsPlus (YouLy+): agregador de sílabas, vários espelhos. */
    LYRICS_PLUS("lyrics_plus", "LyricsPlus"),

    /** Unison: banco comunitário, TTML ou LRC enriquecido. */
    UNISON("unison", "Unison"),

    /** Apple Music via Lyrically: o melhor tempo por sílaba que existe. */
    APPLE_MUSIC("apple_music", "Apple Music"),

    /** Syllable-Sync: TTML casada com a gravação (traz o ISRC). */
    BINI("bini", "Syllable-Sync"),

    /** lrc.red: arquivo indexado direto pelo ISRC da gravação. */
    LRC_RED("lrc_red", "lrc.red"),

    /** Vídeo: letra casada pelo ID do vídeo que está tocando. */
    SIMP_MUSIC("simp_music", "Vídeo"),

    /** Legendas do YouTube: o transcript do vídeo (via InnerTubeX). */
    YOUTUBE_CAPTIONS("youtube_captions", "Legendas do YouTube"),

    /** Megalobiz: LRC comunitário raspado do site. */
    MEGALOBIZ("megalobiz", "Megalobiz"),

    /** Genius: texto puro raspado com jsoup (último recurso). */
    GENIUS("genius", "Genius"),
    ;

    companion object {
        fun byId(id: String?): LyricsSource? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * O que uma fonte devolve. [Success] carrega as linhas, se há tempo de linha
 * ([isSynced]), se há tempo de PALAVRA ([isWordSynced]) e o texto puro.
 */
sealed interface LyricsResult {
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: LyricsSource,
        /** Nome da fonte pro crédito no pé da tela. */
        val sourceLabel: String = source.label,
    ) : LyricsResult

    /** Nenhuma fonte achou letra. */
    data object Empty : LyricsResult

    /** A busca estourou (rede caiu, timeout) e vale tentar de novo. */
    data class Error(val message: String) : LyricsResult
}

/**
 * O que a gente procura. [videoId] é o ID do vídeo do YouTube (quando a
 * faixa veio do YouTube) — as fontes que casam pelo vídeo precisam dele.
 */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int? = null,
    val isrc: String? = null,
    val videoId: String? = null,
)

/**
 * O que UMA fonte devolve. Cada fonte é uma classe com o mesmo contrato:
 * pergunta pro catálogo e devolve as linhas (ou null = "não achei").
 */
data class SourceLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val isWordSynced: Boolean = false,
    val plainLyrics: String? = null,
    val isInstrumental: Boolean = false,
)

/** O contrato de toda fonte de letra do app. */
interface LyricsSourceApi {
    /** Quem é essa fonte (pra crédito e pro seletor de fonte). */
    val source: LyricsSource

    /** null = essa fonte não achou letra pra essa faixa. */
    suspend fun fetch(query: LyricsQuery): SourceLyrics?
}

/**
 * Escreve da direita pra esquerda? (árabe, hebraico, farsi). A letra em RTL
 * precisa de alinhamento e ordem visual diferentes, e a fonte pode ter
 * invertido a ordem das palavras.
 */
fun isRtlText(text: CharSequence?): Boolean {
    if (text.isNullOrBlank()) return false
    var rtlCount = 0
    var ltrCount = 0
    var firstStrongRtl: Boolean? = null
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> {
                if (firstStrongRtl == null) firstStrongRtl = true
                rtlCount++
            }

            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                if (firstStrongRtl == null) firstStrongRtl = false
                ltrCount++
            }
        }
        i += Character.charCount(codePoint)
    }
    return (firstStrongRtl == true) || (rtlCount > 0 && rtlCount >= ltrCount)
}
