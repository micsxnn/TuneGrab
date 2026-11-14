package com.tunegrab.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLrcTest {

    // ---------- tempos ----------

    @Test
    fun le_centesimos_e_calcula_a_duracao_pela_proxima_linha() {
        val lines = LyricsLrc.parse("[00:01.50] Hello\n[00:03.00] World")
        assertEquals(listOf(1500L, 3000L), lines.map { it.timeMs })
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(1500L, lines[0].durationMs)
    }

    @Test
    fun aceita_milissegundos_e_segundos_sem_fracao() {
        val lines = LyricsLrc.parse("[01:02.345] a\n[02:00] b")
        assertEquals(62_345L, lines[0].timeMs)
        assertEquals(120_000L, lines[1].timeMs)
    }

    @Test
    fun linha_com_varios_carimbos_vira_varias_linhas_em_ordem() {
        val lines = LyricsLrc.parse("[00:10.00][00:20.00] refrao\n[00:05.00] intro")
        assertEquals(listOf(5000L, 10_000L, 20_000L), lines.map { it.timeMs })
        assertEquals(listOf("intro", "refrao", "refrao"), lines.map { it.text })
    }

    @Test
    fun offset_desloca_os_tempos() {
        assertEquals(1500L, LyricsLrc.parse("[offset:500]\n[00:01.00] x").single().timeMs)
    }

    @Test
    fun offset_negativo_nunca_gera_tempo_negativo() {
        assertEquals(0L, LyricsLrc.parse("[offset:-5000]\n[00:01.00] x").single().timeMs)
    }

    @Test
    fun ignora_metadados_e_linhas_sem_tempo() {
        val lines = LyricsLrc.parse("[ar:Artista]\n[ti:Titulo]\n\nsem tempo\n[00:01.00] ok")
        assertEquals(1, lines.size)
        assertEquals("ok", lines.single().text)
    }

    @Test
    fun conteudo_vazio_ou_lixo_nao_quebra() {
        assertTrue(LyricsLrc.parse("").isEmpty())
        assertTrue(LyricsLrc.parse("isto nao e um lrc").isEmpty())
    }

    // ---------- LRC enriquecido (palavra a palavra) ----------

    @Test
    fun lrc_enriquecido_gera_silabas_com_o_tempo_de_cada_palavra() {
        val line = LyricsLrc.parse("[00:01.00] <00:01.00>Ola <00:01.50>mundo").single()
        assertTrue(line.hasSyllables)
        assertEquals(listOf("Ola", "mundo"), line.syllables.map { it.text })
        assertEquals(listOf(1000L, 1500L), line.syllables.map { it.timeMs })
        assertEquals(500L, line.syllables[0].durationMs)
        assertEquals("Ola mundo", line.text)
    }

    @Test
    fun parseEnhanced_so_devolve_algo_quando_ha_tempo_por_palavra() {
        assertTrue(LyricsLrc.parseEnhanced("[00:01.00] simples").isEmpty())
        assertEquals(1, LyricsLrc.parseEnhanced("[00:01.00] <00:01.00>oi").size)
    }

    // ---------- vocal de fundo ----------

    @Test
    fun reconhece_vocal_de_fundo_entre_parenteses_na_linha_inteira() {
        assertTrue(LyricsLrc.isBackgroundVocalText("(oh oh)"))
        assertTrue(LyricsLrc.isBackgroundVocalText("  (yeah (yeah))  "))
        assertFalse(LyricsLrc.isBackgroundVocalText("(a) b (c)"))
        assertFalse(LyricsLrc.isBackgroundVocalText("texto normal"))
        assertFalse(LyricsLrc.isBackgroundVocalText("()"))
    }

    @Test
    fun tira_so_um_par_de_parenteses() {
        assertEquals("oh oh", LyricsLrc.stripBackgroundParens("(oh oh)"))
        assertEquals("(a) b (c)", LyricsLrc.stripBackgroundParens("(a) b (c)"))
    }

    @Test
    fun linha_de_fundo_so_com_tempo_de_linha_ganha_uma_silaba_marcada_como_fundo() {
        val line = LyricsLrc.parse("[00:01.00] (ooh)\n[00:03.00] fim").first()
        assertEquals("ooh", line.text)
        assertTrue(line.isBackgroundLine)
    }

    // ---------- entidades HTML ----------

    @Test
    fun decodifica_entidades_comuns() {
        assertEquals("Tom & Jerry", LyricsLrc.decodeEntities("Tom &amp; Jerry"))
        assertEquals("\"oi\"", LyricsLrc.decodeEntities("&quot;oi&quot;"))
        assertEquals("a<b>c", LyricsLrc.decodeEntities("a&lt;b&gt;c"))
        assertEquals("it's", LyricsLrc.decodeEntities("it&#39;s"))
    }

    @Test
    fun decodifica_entidades_numericas() {
        assertEquals("A", LyricsLrc.decodeEntities("&#65;"))
        assertEquals("A", LyricsLrc.decodeEntities("&#x41;"))
        assertEquals("é", LyricsLrc.decodeEntities("&#233;"))
    }

    @Test
    fun decodifica_entidade_numerica_acima_do_plano_basico() {
        // Emoji e outros code points > U+FFFF: antes eram truncados para 16 bits.
        assertEquals("😀", LyricsLrc.decodeEntities("&#128512;"))
        assertEquals("😀", LyricsLrc.decodeEntities("&#x1F600;"))
    }

    @Test
    fun texto_sem_entidade_passa_intacto() {
        assertEquals("sem nada", LyricsLrc.decodeEntities("sem nada"))
        assertEquals("a & b", LyricsLrc.decodeEntities("a & b"))
    }

    // ---------- pós-processamento de tempo ----------

    @Test
    fun normalizeTiming_encolhe_a_linha_que_passa_do_comeco_da_proxima() {
        val out = LyricsLrc.normalizeTiming(
            listOf(
                LyricLine(timeMs = 0, durationMs = 5000, text = "A"),
                LyricLine(timeMs = 2000, durationMs = 1000, text = "B"),
            )
        )
        assertEquals(2000L, out[0].durationMs)
        assertEquals(1000L, out[1].durationMs)
    }

    @Test
    fun normalizeTiming_remove_carimbo_duplicado_e_ordena() {
        val out = LyricsLrc.normalizeTiming(
            listOf(
                LyricLine(timeMs = 3000, text = "B"),
                LyricLine(timeMs = 1000, text = "A"),
                LyricLine(timeMs = 1000, text = "A"),
            )
        )
        assertEquals(listOf("A", "B"), out.map { it.text })
    }

    @Test
    fun normalizeTiming_e_idempotente() {
        val once = LyricsLrc.normalizeTiming(
            listOf(
                LyricLine(timeMs = 0, durationMs = 9000, text = "A"),
                LyricLine(timeMs = 4000, durationMs = 9000, text = "B"),
            )
        )
        assertEquals(once, LyricsLrc.normalizeTiming(once))
    }

    @Test
    fun normalizeTiming_com_lista_vazia_devolve_vazia() {
        assertTrue(LyricsLrc.normalizeTiming(emptyList()).isEmpty())
    }

    // ---------- plausibilidade da duração ----------

    private fun linhas(vararg tempos: Long) = tempos.map { LyricLine(timeMs = it, text = "x") }

    @Test
    fun duracao_plausivel_quando_a_letra_cabe_na_musica() {
        assertTrue(LyricsLrc.plausibleDuration(linhas(1000, 60_000, 180_000), 200))
    }

    @Test
    fun duracao_inplausivel_quando_a_letra_passa_muito_do_fim_da_musica() {
        assertFalse(LyricsLrc.plausibleDuration(linhas(1000, 60_000, 400_000), 200))
    }

    @Test
    fun duracao_inplausivel_quando_cobre_menos_da_metade_e_falta_muito() {
        assertFalse(LyricsLrc.plausibleDuration(linhas(1000, 20_000, 40_000), 400))
    }

    @Test
    fun sem_duracao_conhecida_ou_com_poucas_linhas_aceita() {
        assertTrue(LyricsLrc.plausibleDuration(linhas(1000, 60_000, 400_000), null))
        assertTrue(LyricsLrc.plausibleDuration(linhas(1000, 400_000), 200))
    }
}
