package com.tunegrab.app.lyrics

/**
 * LEITOR DE LRC (o formato de marcação de tempo dos players de karaokê).
 *
 * LRC é um arquivo de texto onde cada verso começa com o tempo:
 * `[01:23.45] Essa é a linha`. Tem três sabores na rua:
 *
 *  - `[mm:ss]`         → segundos
 *  - `[mm:ss.xx]`      → centésimos
 *  - `[mm:ss.xxx]`     → milissegundos
 *
 * E tem o LRC "enriquecido", que marca a PALAVRA dentro da linha:
 * `[01:23.45] Essa <01:23.45>é <01:23.90>a <01:24.10>linha`. É esse que
 * dá o efeito de karaokê palavra a palavra.
 *
 * Tudo aqui devolve [LyricLine] com as sílabas preenchidas quando o arquivo
 * traz tempo por palavra, e sem sílabas quando traz só tempo de linha.
 */
object LyricsLrc {

    /** O "e comercial" solto, pra montar os escapes de HTML sem colar a entidade inteira. */
    const val AMP = "&"

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?]""")
    private val WORD_STAMP_REGEX = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>""")
    private val OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    /** Cache de memória: o mesmo arquivo não é lido duas vezes. */
    private val parsedCache = object : LinkedHashMap<String, List<LyricLine>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>?): Boolean =
            size > 64
    }

    // ---------- utilidades ----------

    /** Converte os escapes de HTML que as fontes colam no texto. */
    fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        var out = raw
        out = out.replace(Regex("&#x([0-9a-fA-F]+);")) {
            runCatching { it.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(it.value)
        }
        out = out.replace(Regex("&#(\\d+);")) {
            runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault(it.value)
        }
        // O "&" fica numa constante: escrever "&" direto no código
        // vira "&" quando o arquivo passa por um filtro de HTML.
        out = out.replace(AMP + "nbsp;", " ")
            .replace(AMP + "quot;", "\"")
            .replace(AMP + "apos;", "'")
            .replace(AMP + "#39;", "'")
            .replace(AMP + "lt;", "<")
            .replace(AMP + "gt;", ">")
            .replace(AMP + "amp;", AMP)
        return out
    }

    /** `mm:ss`, `mm:ss.xx` ou `mm:ss.xxx` → milissegundos. */
    private fun stampToMs(minutes: Long, seconds: Long, fractionStr: String): Long {
        val fractionMs = when (fractionStr.length) {
            2 -> (fractionStr.toLongOrNull() ?: 0L) * 10
            3 -> fractionStr.toLongOrNull() ?: 0L
            1 -> (fractionStr.toLongOrNull() ?: 0L) * 100
            else -> 0L
        }
        return (minutes * 60 * 1000) + (seconds * 1000) + fractionMs
    }

    // ---------- vocal de fundo ----------

    /**
     * No LRC não existe marcação de papel: a convenção da comunidade é
     * colocar o vocal de fundo entre parênteses, NA LINHA INTEIRA.
     */
    fun isBackgroundVocalText(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3 || !t.startsWith('(') || !t.endsWith(')')) return false
        var depth = 0
        for (i in t.indices) {
            when (t[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0) return i == t.lastIndex
        }
        return false
    }

    /** Forma de exibição da linha de fundo: tira UM par de parênteses. */
    fun stripBackgroundParens(text: String): String =
        if (isBackgroundVocalText(text)) {
            val t = text.trim()
            t.substring(1, t.length - 1).trim()
        } else {
            text
        }

    // ---------- leitura ----------

    /**
     * Lê um LRC comum ou enriquecido. Devolve as linhas com tempo; as
     * sílabas só vêm preenchidas se o arquivo tiver marcação por palavra.
     */
    fun parse(lrcContent: String): List<LyricLine> {
        synchronized(parsedCache) { parsedCache[lrcContent] }?.let { return it }
        data class Raw(val timeMs: Long, val body: String)
        val raws = mutableListOf<Raw>()
        var offsetMs = 0L

        for (line in lrcContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val offsetMatch = OFFSET_REGEX.find(trimmed)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val body = trimmed.replace(TIMESTAMP_REGEX, "")
            for (match in matches) {
                val totalMs = stampToMs(
                    match.groupValues[1].toLongOrNull() ?: 0L,
                    match.groupValues[2].toLongOrNull() ?: 0L,
                    match.groupValues.getOrNull(3).orEmpty(),
                ) + offsetMs
                raws += Raw(totalMs.coerceAtLeast(0L), body)
            }
        }
        raws.sortBy { it.timeMs }

        val result = mutableListOf<LyricLine>()
        raws.forEachIndexed { index, raw ->
            val nextStart = raws.getOrNull(index + 1)?.timeMs
            val cleanText = decodeEntities(raw.body.replace(WORD_STAMP_REGEX, "").trim())
            val words = parseWordRuns(raw.body, raw.timeMs, nextStart)
            val isBg = isBackgroundVocalText(cleanText)
            val displayText = if (isBg) stripBackgroundParens(cleanText) else cleanText
            if (words.isNotEmpty()) {
                val lineStart = minOf(raw.timeMs, words.first().timeMs)
                // Guarda a pontuação do autor, com o espaço entre as marcas
                // de tempo colapsado em um só.
                val text = LyricsSanitizer.safeLineText(displayText, words.map { it.text })
                result += LyricLine(
                    timeMs = lineStart,
                    durationMs = (
                        (nextStart ?: (words.maxOf { it.timeMs + it.durationMs })) - lineStart
                        ).coerceAtLeast(0L),
                    text = text,
                    syllables = if (isBg) words.map { it.copy(isBackground = true) } else words,
                )
            } else {
                val lineDuration = if (nextStart != null && nextStart > raw.timeMs) nextStart - raw.timeMs else 0L
                // Linha de fundo só com tempo de linha ganha UMA sílaba de
                // cobertura, pra tela poder piscar ela apagada.
                val syllables = if (isBg && displayText.isNotBlank()) {
                    listOf(
                        LyricSyllable(
                            timeMs = raw.timeMs,
                            durationMs = lineDuration.takeIf { it > 0 } ?: 4000L,
                            text = displayText,
                            isBackground = true,
                        ),
                    )
                } else {
                    emptyList()
                }
                result += LyricLine(
                    timeMs = raw.timeMs,
                    durationMs = lineDuration,
                    text = displayText,
                    syllables = syllables,
                )
            }
        }

        val sorted = result.sortedBy { it.timeMs }
        synchronized(parsedCache) {
            parsedCache[lrcContent] = sorted
            while (parsedCache.size > 64) parsedCache.remove(parsedCache.keys.first())
        }
        return sorted
    }

    /** Só o LRC que tem tempo por PALAVRA (o resto é o parse simples). */
    fun parseEnhanced(lrcContent: String): List<LyricLine> {
        val parsed = parse(lrcContent)
        return parsed.takeIf { lines -> lines.any { it.hasSyllables } } ?: emptyList()
    }

    /**
     * As marcas de palavra dentro da linha: `<mm:ss.xx>texto`. Cada trecho
     * entre duas marcas é uma palavra que acende no tempo dela.
     */
    private fun parseWordRuns(
        body: String,
        lineStartMs: Long,
        lineEndMs: Long?,
    ): List<LyricSyllable> {
        val marks = WORD_STAMP_REGEX.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        data class Run(val startMs: Long, val text: String)
        val allRuns = mutableListOf<Run>()

        val firstMark = marks.first()
        val leadingText = body.substring(0, firstMark.range.first)
        if (leadingText.trim().isNotEmpty()) {
            val firstMarkMs = stampToMs(
                firstMark.groupValues[1].toLongOrNull() ?: 0L,
                firstMark.groupValues[2].toLongOrNull() ?: 0L,
                firstMark.groupValues[3],
            )
            val startMs = minOf(lineStartMs, firstMarkMs)
            allRuns += Run(startMs, leadingText)
        }

        marks.forEachIndexed { index, mark ->
            val until = marks.getOrNull(index + 1)?.range?.first ?: body.length
            val startMs = stampToMs(
                mark.groupValues[1].toLongOrNull() ?: 0L,
                mark.groupValues[2].toLongOrNull() ?: 0L,
                mark.groupValues[3],
            )
            allRuns += Run(startMs, body.substring(mark.range.last + 1, until))
        }
        val words = mutableListOf<LyricSyllable>()
        allRuns.forEachIndexed { index, run ->
            val text = decodeEntities(run.text).trim()
            if (text.isBlank()) return@forEachIndexed
            val endMs = allRuns.getOrNull(index + 1)?.startMs
                ?: lineEndMs
                ?: (run.startMs + 800L)
            words += LyricSyllable(
                timeMs = run.startMs.coerceAtLeast(0L),
                durationMs = (endMs - run.startMs).coerceAtLeast(0L),
                text = text,
            )
        }
        return words
    }

    // ---------- pós-processamento ----------

    /**
     * Normaliza o tempo DE PONTA A PONTA: linha sobreposta é o motivo de
     * DUAS linhas acenderem ao mesmo tempo num corte de verso. Só encolhe
     * sobreposição: carimbo duplicado some, linha que passa do começo da
     * próxima é puxada pra trás, e cada sílaba é limitada pelo começo da
     * próxima sílaba e pelo fim da própria linha. Idempotente.
     */
    fun normalizeTiming(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val sorted = lines.sortedBy { it.timeMs }
        val deduped = mutableListOf<LyricLine>()
        sorted.forEach { line ->
            val prev = deduped.lastOrNull()
            if (prev != null && prev.timeMs == line.timeMs &&
                prev.text.trim() == line.text.trim() &&
                prev.syllables.size == line.syllables.size
            ) {
                return@forEach
            }
            deduped += line
        }
        return deduped.mapIndexed { index, line ->
            val nextStart = deduped.getOrNull(index + 1)?.timeMs
            var duration = line.durationMs
            if (duration > 0 && nextStart != null && nextStart > line.timeMs &&
                line.timeMs + duration > nextStart
            ) {
                duration = nextStart - line.timeMs
            }
            val lineEnd = if (duration > 0) line.timeMs + duration else {
                line.syllables.maxOfOrNull { it.timeMs + it.durationMs } ?: line.timeMs
            }
            val syllables = clampSyllables(line.syllables, lineEnd)
            if (duration != line.durationMs || syllables !== line.syllables) {
                line.copy(durationMs = duration, syllables = syllables)
            } else {
                line
            }
        }
    }

    private fun clampSyllables(units: List<LyricSyllable>, lineEnd: Long): List<LyricSyllable> {
        if (units.size < 2 && (units.isEmpty() || units.first().timeMs + units.first().durationMs <= lineEnd)) {
            return units
        }
        val ordered = units.sortedBy { it.timeMs }
        return ordered.mapIndexed { si, syl ->
            val nextSylStart = ordered.getOrNull(si + 1)?.timeMs
            var end = syl.timeMs + syl.durationMs
            if (nextSylStart != null && nextSylStart > syl.timeMs && end > nextSylStart) {
                end = nextSylStart
            }
            if (lineEnd > syl.timeMs && end > lineEnd) end = lineEnd
            if (end < syl.timeMs) end = syl.timeMs
            if (end - syl.timeMs != syl.durationMs) syl.copy(durationMs = end - syl.timeMs) else syl
        }
    }

    /**
     * A linha do tempo bate com a música? Serve pra fontes que respondem
     * sem dizer QUAL gravação é a resposta (o servidor casa por aproximação):
     * rejeita linha que passa 45s do fim da música, ou que cobre menos da
     * metade de uma música e ainda falta 90s (outra música, mais curta).
     * Deixado frouxo de propósito: outro/skit acaba antes.
     */
    fun plausibleDuration(lines: List<LyricLine>, durationSeconds: Int?): Boolean {
        if (durationSeconds == null || durationSeconds <= 0) return true
        if (lines.size < 3) return true
        val expectedMs = durationSeconds * 1000L
        if (expectedMs < 60_000L) return true
        val endMs = lines.maxOfOrNull { line ->
            val sylEnd = line.syllables.maxOfOrNull { it.timeMs + it.durationMs } ?: 0L
            maxOf(line.timeMs + line.durationMs, sylEnd, line.timeMs)
        } ?: return true
        if (endMs <= 0L) return true
        if (endMs > expectedMs + 45_000L) return false
        if (endMs < expectedMs * 0.5 && expectedMs - endMs > 90_000L) return false
        return true
    }
}
