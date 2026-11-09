package com.tunegrab.app.lyrics

/**
 * CASAMENTO DE NOME (o filtro que evita letra errada).
 *
 * Este é o pedaço mais importante do sistema de letras: quase toda fonte
 * responde com uma lista de candidatos e a gente precisa escolher O
 * candidato certo. Errar aqui significa letra de outra música — pior do que
 * não achar letra nenhuma.
 *
 * As regras vieram do LastWave (que já tinha provado elas em produção) e são
 * três filtros, aplicados em ordem:
 *
 *  1. VERSÃO: "Live", "Remix", "Acoustic" etc. precisam bater dos dois
 *     lados. Estúdio não é ao vivo.
 *  2. TÍTULO: o nome da faixa tem que ser o mesmo (com tolerância pra
 *     sujeira e pro prefixo "Artista - " que os catálogos às vezes guardam).
 *  3. ARTISTA: o nome do artista tem que bater.
 *
 * A duração é um DESEMPATE, nunca um filtro: a duração do clipe no YouTube
 * costuma diferir do áudio do catálogo em 10 a 60 segundos.
 */
object LyricsMatchers {

    /** Diferença de duração que ainda indica a MESMA gravação (8s). */
    const val STRICT_DURATION_DELTA = 8.0

    /** Diferença tolerada quando a faixa é clipe/música com vídeo (30s). */
    const val RELAXED_DURATION_DELTA = 30.0

    /**
     * Título bate? Aceita o título sujo dos catálogos ("Artista - Faixa
     * [Official Video]") porque a variante sem o prefixo também é testada.
     *
     * O substring é PLAINEMENTE limitado pelo tamanho: `contains` sozinho
     * aceitava "Love" para "Love Me Like You Do" (letra de outra música).
     * O lado menor precisa cobrir 70% do maior; abaixo disso quem decide é
     * o overlap de palavras.
     */
    fun titlesMatch(candidateTitle: String, requestTitle: String): Boolean {
        val variants = listOf(candidateTitle, stripLeadingArtistPrefix(candidateTitle))
        return variants.any { cand -> strictTitleMatch(cand, requestTitle) }
    }

    private fun strictTitleMatch(a: String, b: String): Boolean {
        val ca = a.trim()
        val cb = b.trim()
        if (ca.isBlank() || cb.isBlank()) return false
        if (ca.equals(cb, ignoreCase = true)) return true
        if (ca.contains(cb, ignoreCase = true) || cb.contains(ca, ignoreCase = true)) {
            val ratio = minOf(ca.length, cb.length).toDouble() / maxOf(ca.length, cb.length)
            if (ratio >= 0.7) return true
        }
        return isSimilar(ca, cb)
    }

    /**
     * Artista bate? `contains` dos dois lados aceitava "Ann" para "Annie"
     * ou "John" para "John Lennon covers". Exato vence; substring precisa
     * de cobertura de tamanho; senão, overlap de palavras.
     */
    fun artistMatches(candidateArtist: String, requestArtist: String): Boolean {
        val ca = candidateArtist.trim()
        val ra = requestArtist.trim()
        if (ca.isBlank() || ra.isBlank()) return false
        if (ca.equals(ra, ignoreCase = true)) return true
        if (ca.contains(ra, ignoreCase = true) || ra.contains(ca, ignoreCase = true)) {
            if (minOf(ca.length, ra.length) < 4) return isSimilar(ca, ra)
            val ratio = minOf(ca.length, ra.length).toDouble() / maxOf(ca.length, ra.length)
            if (ratio >= 0.6) return true
        }
        return isSimilar(ca, ra)
    }

    /** Tira o prefixo "Artista - " / "Artista – " / "Artista: " do começo. */
    fun stripLeadingArtistPrefix(raw: String): String {
        val stripped = raw.replace(
            Regex("""^\s*.+?\s*[-–—:]\s+(?=\S)"""),
            "",
        ).trim()
        // "A - B" também é nome de música de verdade: só aceita a troca
        // quando sobra algo com tamanho de nome.
        return if (stripped.length >= 2) stripped else raw.trim()
    }

    /**
     * Marcas de versão que distinguem uma gravação da outra. "Remaster" e
     * "Radio Edit" NÃO entram de propósito: são a mesma gravação e a limpeza
     * de título já normaliza esses.
     */
    private val VERSION_KEYWORDS = mapOf(
        "live" to "live",
        "concert" to "live",
        "session" to "live",
        "unplugged" to "unplugged",
        "acoustic" to "acoustic",
        "remix" to "remix",
        "cover" to "cover",
        "karaoke" to "karaoke",
        "instrumental" to "instrumental",
        "slowed" to "slowed",
        "sped up" to "sped",
        "speed up" to "sped",
        "spedup" to "sped",
        "sped" to "sped",
        "nightcore" to "sped",
        "demo" to "demo",
        "lullaby" to "lullaby",
        "8d" to "8d",
    )

    /** Marcas de versão achadas entre colchetes ou no " - X" do fim. */
    fun versionTags(rawTitle: String): Set<String> {
        val tags = mutableSetOf<String>()
        val segments = mutableListOf<String>()
        Regex("""[\(\[](.*?)[\)\]]""").findAll(rawTitle).forEach { segments.add(it.groupValues[1]) }
        Regex("""\s*[-–—:]\s*([^-–—:\(\[]+)\s*$""").find(rawTitle)?.let { segments.add(it.groupValues[1]) }
        for (segment in segments) {
            val lower = " $segment ".lowercase()
            for ((keyword, tag) in VERSION_KEYWORDS) {
                if (lower.contains(keyword)) tags.add(tag)
            }
        }
        return tags
    }

    /** Verdadeiro só quando os dois títulos falam da MESMA gravação. */
    fun sameVersion(requestTitle: String, candidateTitle: String): Boolean =
        versionTags(requestTitle) == versionTags(candidateTitle)

    /** Similaridade por palavras em comum (Jaccard >= 0.5). */
    fun isSimilar(s1: String, s2: String): Boolean {
        val a = s1.trim().lowercase()
        val b = s2.trim().lowercase()
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val aTokens = a.split(Regex("""\s+""")).toSet()
        val bTokens = b.split(Regex("""\s+""")).toSet()
        val intersection = aTokens.intersect(bTokens).size
        val union = aTokens.union(bTokens).size
        return if (union > 0) (intersection.toDouble() / union) >= 0.5 else false
    }

    /**
     * Limpa o título da faixa. Aqui saem SÓ os créditos e o embrulho
     * ("feat. X", "[Official Video]", "(Official Audio)"). As marcas de
     * versão (remix, live, acoustic) FICAM: elas nomeiam outra gravação e o
     * filtro de versão precisa delas pra rejeitar o corte errado.
     */
    fun cleanTrackTitle(raw: String): String {
        var out = raw
        out = out.replace(
            Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|featuring|with)[^\)\]]*[\)\]]"""),
            "",
        )
        out = out.replace(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$"""), "")
        out = out.replace(
            Regex(
                """(?i)\s*[\(\[]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[\)\]]"""
            ),
            "",
        )
        out = out.replace(Regex("""(?i)\s*[\(\[]\s*official\s*[\)\]]"""), "")
        return out.replace(Regex("""\s+"""), " ").trim().ifBlank { raw.trim() }
    }

    /** Limpa o nome do artista (tira o "feat." do fim). */
    fun cleanArtistName(raw: String): String {
        return raw
            .replace(Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?)[^\)\]]*[\)\]]"""), "")
            .replace(Regex("""(?i)\s*(?:feat\.?|ft\.?)\s+.*$"""), "")
            .trim()
    }

    // ---------- nomes de busca (o que a gente MANDA pra fonte) ----------

    private val SEARCH_CREDITS = listOf(
        Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|featuring|with)\s+[^\)\]]*[\)\]]"""),
        Regex("""(?i)\s*[\(\[]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|lyrics?\s*video|m/?v|hd|hq|4k)\s*[\)\]]"""),
        Regex("""(?i)\s*[\(\[]\s*official\s*[\)\]]"""),
        Regex("""(?i)\s*[-–—:]\s*[^-–—:]{0,40}\s*$"""),
    )

    private val SEARCH_WHITESPACE = Regex("""\s+""")

    /** Título de busca: tira crédito e embrulho, mas mantém a versão. */
    fun forSearchTitle(raw: String): String {
        var name = raw
        SEARCH_CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
        return name.replace(SEARCH_WHITESPACE, " ").trim().trimEnd(',', '-', '–', '—').trim()
            .ifBlank { raw.trim() }
    }

    /** Artista de busca: tira o sufixo " - Topic" que o YouTube Music usa. */
    fun forSearchArtist(raw: String): String =
        raw.removeSuffix(" - Topic").trim().ifBlank { raw.trim() }
}
