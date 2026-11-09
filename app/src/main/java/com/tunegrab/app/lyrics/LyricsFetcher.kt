package com.tunegrab.app.lyrics

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * LAVAGEM DO NOME DA FAIXA (a parte que decide QUAL letra a gente procura).
 *
 * O nome do arquivo do YouTube nunca vem limpo: chega "Artista - Música
 * (Official Video).mp3", "Artista - Música [Lyrics]", "Artista - Música
 * (Slowed + Reverb)", "Música - Artista (Marília Canta Thomaz)". Nenhuma
 * fonte de letra do mundo casa com esse nome.
 *
 * Então o título passa por três etapas:
 *
 *  1. tira a extensão e os blocos de lixo entre parênteses/colchetes
 *     (official video, lyrics, ao vivo, slowed, feat...);
 *  2. arranca os blocos SEM palavra-chave que sobraram no FIM ("(Marília
 *     Canta Thomaz)", "[Ultra Records]") — nome de álbum ou gravadora;
 *  3. quebra no " - " pra separar ARTISTA de FAIXA.
 *
 * Arquivo LOCAL com metadados embutidos (o yt-dlp grava título e artista na
 * hora do download)? Esses valem MAIS que o nome do arquivo, e substituem
 * os dois campos.
 */
object LyricsFetcher {

    private val EXT = Regex(
        """\.(mp3|m4a|opus|ogg|oga|wav|flac|mp4|mkv|webm|m4v)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Blocos de lixo entre parênteses/colchetes que o YouTube cola. */
    private val JUNK = Regex(
        """\s*[\(\[][^\)\]]*(official|oficial|vide[oó]|[áa]udios?|lyrics?|letras?|clipe?|clip|visuali[sz]er|remaster(?:izado)?|explicit|h\.?q|4k|2160p|1080p|720p|karaok[êe]|instrumental|fanmade|legendad[oa]|tradu[cç][ãa]o|sub\s*esp|legendas?|ao\s+vivo|live|feat\.?|ft\.?|part\.?|slowed|reverb|lofi|lo-fi|nightcore|sped\s*up|speed\s*up|extended|remix)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    /** Blocos SEM palavra-chave que sobraram no FIM do título. */
    private val TRAILING_BLOCK = Regex("""(?:\s*[\(\[][^\)\]]*[\)\]])+\s*$""")

    /** Separador que sobrou solto no fim depois da lavagem ("Música -"). */
    private val DANGLE_SEP = Regex("""\s+[-–—]\s*$""")

    /** "Artista - Música" (também aceita – e —). */
    private val SEP = Regex("""\s+[-–—]\s+""")

    private val SPACES = Regex("""\s+""")

    /**
     * Monta a busca a partir do que o app tem: o título que aparece na tela
     * (nome do arquivo, quase sempre) e, se for arquivo LOCAL, os metadados
     * embutidos (título/artista de verdade). [durationMs] vem do player
     * (0 = não sabe) e vai junto pra API afinar o casamento.
     */
    fun buildQuery(
        ctx: Context,
        displayTitle: String,
        localUri: String?,
        durationMs: Int,
        videoId: String? = null,
    ): LyricsQuery {
        var name = EXT.replace(displayTitle.trim(), "")
        name = JUNK.replace(name, " ")
        // Blocos sem palavra-chave no FIM (nome de álbum/gravadora): tira
        // enquanto sobrar título de verdade — nunca deixa o nome em branco.
        while (true) {
            val stripped = TRAILING_BLOCK.replace(name, "").trim()
            if (stripped.isNotEmpty() && stripped != name.trim()) name = stripped else break
        }
        var artist = ""
        var title = SPACES.replace(name, " ").trim()
        title = DANGLE_SEP.replace(title, "").trim()
        val sep = SEP.find(title)
        if (sep != null) {
            artist = title.substring(0, sep.range.first).trim()
            title = title.substring(sep.range.last + 1).trim()
        }
        // Metadados embutidos valem MAIS que o nome do arquivo (só em arquivo
        // local — stream remoto não tem metadado pra ler).
        if (localUri != null && !localUri.startsWith("http")) {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, Uri.parse(localUri))
                    val embeddedArtist = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_ARTIST
                    )?.trim().orEmpty()
                    val embeddedTitle = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_TITLE
                    )?.trim().orEmpty()
                    if (embeddedArtist.isNotEmpty()) artist = embeddedArtist
                    if (embeddedTitle.isNotEmpty()) title = embeddedTitle
                } finally {
                    try {
                        retriever.release()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
                // Arquivo podre/sumido: fica o nome lavado mesmo.
            }
        }
        return LyricsQuery(
            title = title,
            artist = artist,
            durationSec = durationMs.takeIf { it > 0 }?.div(1000),
            videoId = videoId,
        )
    }

    /**
     * O ID do vídeo do YouTube de um link de faixa (as fontes que casam
     * pelo vídeo precisam dele).
     */
    fun videoIdOf(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            pattern.find(raw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
