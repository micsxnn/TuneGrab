package com.tunegrab.app.lyrics

/**
 * LIMPEZA DA LETRA (porta do LastWave).
 *
 * Antes de qualquer linha chegar na tela, ela passa por aqui. As fontes
 *_COMMUNITÁRIAS_ (LRCLIB, Kugou, Unison, Megalobiz) colam no corpo da letra
 * tudo que não é verso cantado:
 *
 *  - "Written by …", "Lyrics provided by …", "[ar: Artista]", "纯音乐，请欣赏"
 *    → ESSA LINHA NUNCA É CANTADA. Se ficar, ela entra na linha do tempo e
 *    rouba o foco de um verso de verdade.
 *  - "Singer 2:", "[v2]", "Both:", "Nome: …" → o RÓTULO de quem canta. Vira
 *    [VocalRole] pra separar dueto, e some do texto.
 *
 * Nenhuma regra aqui mexe no TEMPO: o que sobra mantém o tempo exato, então
 * a sincronia continua idêntica.
 */
object LyricsSanitizer {

    private val IC = setOf(RegexOption.IGNORE_CASE)

    /** Palavras que tornam a linha um crédito quando ela começa com elas. */
    private val CREDIT_ROW = Regex(
        """^(?:lyrics?|words?|music|song|composition|arrangement|translation|romani[sz]ation|transcription|sync(?:ed|hroni[sz]ed)?|tim(?:ed|ing)|subtitles?|lrc|karaoke)?\s*""" +
            """(?:written|composed|produced|arranged|performed|provided|licensed|powered|courtesy|translated|romani[sz]ed|transcribed|synced|synchroni[sz]ed|timed|edited|encoded|uploaded|submitted|contributed|mixed|mastered)\s*(?:by|of)\b.*$""",
        IC,
    )

    /** Linhas de rótulo: "Lyricist: …", "Composer：…", "作词 : …". */
    private val CREDIT_LABEL = Regex(
        """^(?:lyricists?|lyrics|composers?|music|writers?|songwriters?|producers?|arrangers?|arrangement|mix(?:ing)?|master(?:ing)?|engineers?|vocals?\s*producer|publishers?|label|copyright|credits?|contributors?|source|provider|translators?|translation|transcriber|lrc\s*maker|title|artist|album|creator|author|length|offset|by|""" +
            """作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|製作人|监制|監製|混音|母带|母帶|录音|錄音|和声|和聲|吉他|贝斯|鼓|弦乐|出品|发行|發行|策划|企划|统筹|作詞者|作曲者|編曲者)\s*[:：].*$""",
        IC,
    )

    /** Tags de identificação do LRC que sobraram dentro do corpo da letra. */
    private val LRC_ID_TAG = Regex("""^\[(?:ar|ti|al|au|by|offset|length|re|ve|tool|#)\s*:[^\]]*]$""", IC)

    /** Linha que é só a marca d'água do serviço. */
    private val PROVIDER_ROW = Regex(
        """^(?:(?:lyrics|synced lyrics|lrc)\s*(?:from|via|by|on)?\s*)?""" +
            """(?:musixmatch|lrclib|genius|kugou|netease|qq\s*music|spotify|apple\s*music|binilyrics|paxsenix|lyricsplus|megalobiz|unison|betterlyrics|lyricfind|petitlyrics|azlyrics|youtube\s*music)""" +
            """(?:\.com)?\s*(?:lyrics)?\s*$""",
        IC,
    )

    /** "Lyrics powered by Musixmatch", "Synced via LRCLIB", rodapé de copyright. */
    private val PROVIDER_MENTION = Regex(
        """^.*\b(?:musixmatch|lrclib|lyricfind|paxsenix|binilyrics|lyricsplus|megalobiz|betterlyrics|petitlyrics)\b.*$""",
        IC,
    )

    private val COPYRIGHT_ROW = Regex("""^(?:©|\(c\)|copyright\b|℗|all rights reserved).*$""", IC)

    private val NOT_FOR_COMMERCIAL = Regex(
        """^\**\s*(?:this lyrics? is not for commercial use|lyrics? (?:are|is) not (?:yet )?(?:available|synced)).*$""",
        IC,
    )

    /** Aviso de faixa instrumental / letra não carregada. */
    private val FILLER_ROW = Regex(
        """^(?:纯音乐[，,\s]*请欣赏.*|纯音乐.*|此歌曲为没有填词的纯音乐.*|没有填词.*|暂无歌词.*|歌词加载中.*|無歌詞.*|インストゥルメンタル.*)$""",
    )

    /** Cabeçalhos de seção e indicações de palco que não são cantados. */
    private val SECTION_HEADER = Regex(
        """^[\[(]\s*(?:intro|outro|verse|pre[-\s]?chorus|post[-\s]?chorus|chorus|hook|bridge|refrain|interlude|break|drop|breakdown|instrumental|solo|guitar\s*solo|piano\s*solo|music|applause|cheering|beat\s*drop|spoken|skit)(?:\s*\d+)?(?:\s*[:\-–].*)?\s*[\])]$""",
        IC,
    )

    private val BARE_MARKER = Regex(
        """^(?:instrumental|music|applause|cheering|beat\s*drop|guitar\s*solo|piano\s*solo)$""",
        IC,
    )

    private val SUPPRESSIONS = listOf(
        CREDIT_ROW, CREDIT_LABEL, LRC_ID_TAG, PROVIDER_ROW, PROVIDER_MENTION,
        COPYRIGHT_ROW, NOT_FOR_COMMERCIAL, FILLER_ROW, SECTION_HEADER, BARE_MARKER,
    )

    /** Verdadeiro quando [text] é crédito/metadado/preenchimento, nunca verso. */
    fun shouldSuppress(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return SUPPRESSIONS.any { it.matches(trimmed) }
    }

    // ---------- papéis vocais ----------

    /** Rótulos de papel que todas as convenções de fonte concordam. */
    private val ROLE_LABEL = Regex(
        """^\s*(?:\[(v\d+|all|both|bg)]|(singer|vocalist|voice|artist|v)\s*(\d+)\s*[:：]|(both|all|chorus|together|everyone|unison)\s*[:：])\s*""",
        IC,
    )

    /** Rótulo com nome: "Nome: verso" (LRC de dueto da comunidade). */
    private val NAME_LABEL = Regex("""^\s*([\p{L}][\p{L}\p{N} .'’&,+\-]{0,24}?)\s*[:：]\s+(?=\S)""")

    private val GROUP_NAME = Regex(
        """(?:&|\band\b|\bwith\b|,|\+|\bboth\b|\ball\b|\btogether\b|\bchorus\b)""",
        IC,
    )

    private data class Label(val length: Int, val role: VocalRole)

    private fun explicitLabel(text: String): Label? {
        val m = ROLE_LABEL.find(text) ?: return null
        if (m.range.first != 0) return null
        val bracket = m.groupValues[1].lowercase()
        val number = m.groupValues[3].toIntOrNull()
        val group = m.groupValues[4]
        val role = when {
            bracket.isNotEmpty() -> when {
                bracket == "all" || bracket == "both" -> VocalRole.UNISON_CENTER
                bracket == "bg" -> VocalRole.BACKGROUND_SUBLINE
                else -> roleForVoiceNumber(bracket.drop(1).toIntOrNull() ?: 1)
            }

            number != null -> roleForVoiceNumber(number)
            group.isNotEmpty() -> VocalRole.UNISON_CENTER
            else -> return null
        }
        return Label(m.value.length, role)
    }

    private fun roleForVoiceNumber(n: Int): VocalRole = when {
        n >= 1000 -> VocalRole.UNISON_CENTER
        n % 2 == 0 -> VocalRole.DUET_RIGHT
        else -> VocalRole.LEAD_LEFT
    }

    /**
     * Joga fora as linhas suprimidas e transforma rótulo de cantor em papel.
     * Rótulo com nome ("Kendrick: …") só vale quando a música usa o nome de
     * forma consistente (o mesmo nome pelo menos duas vezes, três linhas
     * rotuladas no total) — assim uma letra que só tem uma vírgula nunca é
     * reescrita.
     */
    fun sanitize(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val kept = lines.filterNot { shouldSuppress(it.text) }

        val nameCounts = HashMap<String, Int>()
        for (line in kept) {
            if (explicitLabel(line.text) != null) continue
            val m = NAME_LABEL.find(line.text) ?: continue
            val name = m.groupValues[1].trim().lowercase()
            if (name.split(' ').size > 4) continue
            nameCounts[name] = (nameCounts[name] ?: 0) + 1
        }
        val trustedNames = nameCounts.filterValues { it >= 2 }.keys
        // Dueto de verdade rotula pelo menos DOIS cantores; um "Palavra:"
        // repetido é bem mais provável ser parte do verso.
        val useNames = trustedNames.size >= 2 && nameCounts.filterKeys { it in trustedNames }.values.sum() >= 3
        val nameRoles = LinkedHashMap<String, VocalRole>()
        var soloSeen = 0

        var current: VocalRole? = null
        val out = ArrayList<LyricLine>(kept.size)
        for (line in kept) {
            var label = explicitLabel(line.text)
            if (label == null && useNames) {
                val m = NAME_LABEL.find(line.text)
                val name = m?.groupValues?.get(1)?.trim()?.lowercase()
                if (m != null && name != null && name in trustedNames) {
                    val role = nameRoles.getOrPut(name) {
                        if (GROUP_NAME.containsMatchIn(name)) VocalRole.UNISON_CENTER
                        else (if (soloSeen++ % 2 == 0) VocalRole.LEAD_LEFT else VocalRole.DUET_RIGHT)
                    }
                    label = Label(m.value.length, role)
                }
            }
            if (label == null) {
                // Linha sem rótulo herda o último cantor (o rótulo marca o
                // COMEÇO de uma parte, não cada verso dela).
                val inherited = current
                out += if (inherited != null && line.role == VocalRole.LEAD_LEFT) {
                    line.copy(role = inherited)
                } else {
                    line
                }
                continue
            }
            val stripped = stripLabel(line, label.length)
            if (stripped.text.isBlank() && !stripped.hasSyllables) {
                // Rótulo sozinho na linha ("Both:") define o cantor das
                // linhas que vem depois.
                current = label.role.takeIf { it != VocalRole.BACKGROUND_SUBLINE }
                continue
            }
            if (label.role == VocalRole.BACKGROUND_SUBLINE) {
                out += stripped.copy(syllables = stripped.syllables.map { it.copy(isBackground = true) })
            } else {
                current = label.role
                out += stripped.copy(role = label.role)
            }
        }
        return out
    }

    /**
     * Tira os [labelLength] primeiros caracteres do texto e as sílabas que
     * só escrevem o rótulo. O tempo das sílabas não é tocado.
     */
    private fun stripLabel(line: LyricLine, labelLength: Int): LyricLine {
        val label = line.text.take(labelLength)
        val labelChars = label.filterNot { it.isWhitespace() }
        val newText = line.text.drop(labelLength).trimStart()
        if (!line.hasSyllables) return line.copy(text = newText)
        var consumed = 0
        var dropCount = 0
        for (syl in line.syllables) {
            if (consumed >= labelChars.length) break
            val chars = syl.text.filterNot { it.isWhitespace() }
            if (chars.isEmpty()) {
                dropCount++
                continue
            }
            val remaining = labelChars.substring(consumed)
            if (remaining.startsWith(chars, ignoreCase = true)) {
                consumed += chars.length
                dropCount++
            } else if (chars.startsWith(remaining, ignoreCase = true)) {
                // O rótulo divide uma sílaba com a primeira palavra cantada.
                val rest = syl.text.trimStart().drop(remaining.length).trimStart()
                return line.copy(
                    text = newText,
                    syllables = buildList {
                        if (rest.isNotEmpty()) add(syl.copy(text = rest))
                        addAll(line.syllables.drop(dropCount + 1))
                    },
                )
            } else {
                break
            }
        }
        if (consumed < labelChars.length) return line.copy(text = newText)
        return line.copy(text = newText, syllables = line.syllables.drop(dropCount))
    }

    /** Letra SEM tempo: mesma supressão de linhas, rótulo fica como o autor escreveu. */
    fun sanitizePlain(text: String?): String? {
        if (text.isNullOrBlank()) return text
        val rows = text.lines()
        val cleaned = rows.filterNot { shouldSuppress(it) }
        if (cleaned.size == rows.size) return text
        return cleaned.joinToString("\n").trim('\n').ifBlank { null }
    }

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Texto que vai na tela de uma linha com tempo por palavra. O texto do
     * autor guarda a pontuação que a lista de palavras da fonte perde, mas
     * só quando ele escreve as MESMAS letras — sobra de markup, vocal de
     * fundo ou tradução entrariam na linha principal, e XML indentado
     * deixaria espaço demais entre as palavras.
     */
    fun safeLineText(authored: String?, words: List<String>): String {
        val joined = words.joinToString(" ") { it.trim() }.replace(WHITESPACE, " ").trim()
        val collapsed = authored?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (collapsed.isEmpty()) return joined
        val a = collapsed.filter { it.isLetterOrDigit() }
        val b = joined.filter { it.isLetterOrDigit() }
        return if (a.equals(b, ignoreCase = true)) collapsed else joined
    }
}
