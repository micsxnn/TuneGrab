package com.tunegrab.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchersTest {

    // ---------- título ----------

    @Test
    fun titulo_igual_ignora_caixa() {
        assertTrue(LyricsMatchers.titlesMatch("Hello", "hello"))
    }

    @Test
    fun titulo_aceita_o_prefixo_artista_traco() {
        assertTrue(LyricsMatchers.titlesMatch("Artista - Faixa", "Faixa"))
    }

    @Test
    fun titulo_curto_nao_casa_com_titulo_longo_que_so_o_contem() {
        // O bug que a regra dos 70% corrige: "Love" não é "Love Me Like You Do".
        assertFalse(LyricsMatchers.titlesMatch("Love", "Love Me Like You Do"))
    }

    @Test
    fun titulo_vazio_nunca_casa() {
        assertFalse(LyricsMatchers.titlesMatch("", "Faixa"))
        assertFalse(LyricsMatchers.titlesMatch("Faixa", "  "))
    }

    // ---------- artista ----------

    @Test
    fun artista_igual_casa() {
        assertTrue(LyricsMatchers.artistMatches("Queen", "queen"))
    }

    @Test
    fun artista_nome_curto_nao_casa_com_nome_parecido() {
        assertFalse(LyricsMatchers.artistMatches("Ann", "Annie"))
    }

    @Test
    fun artista_dentro_de_um_nome_bem_maior_nao_casa() {
        assertFalse(LyricsMatchers.artistMatches("John", "John Lennon covers"))
    }

    @Test
    fun artista_vazio_nunca_casa() {
        assertFalse(LyricsMatchers.artistMatches("", "Queen"))
    }

    // ---------- versão (live, remix, ...) ----------

    @Test
    fun detecta_marcas_de_versao() {
        assertTrue("live" in LyricsMatchers.versionTags("Faixa (Live at Wembley)"))
        assertTrue("remix" in LyricsMatchers.versionTags("Faixa [Remix]"))
        assertTrue(LyricsMatchers.versionTags("Faixa").isEmpty())
    }

    @Test
    fun versoes_diferentes_nao_sao_a_mesma_gravacao() {
        assertFalse(LyricsMatchers.sameVersion("Faixa", "Faixa (Live)"))
        assertTrue(LyricsMatchers.sameVersion("Faixa (Live)", "Faixa - Live"))
    }

    @Test
    fun remaster_nao_conta_como_outra_versao() {
        assertTrue(LyricsMatchers.sameVersion("Faixa (Remastered 2011)", "Faixa"))
    }

    // ---------- limpeza de nomes ----------

    @Test
    fun limpa_feat_e_embrulho_do_titulo() {
        assertEquals("Faixa", LyricsMatchers.cleanTrackTitle("Faixa (feat. Fulano) [Official Video]"))
        assertEquals("Faixa", LyricsMatchers.cleanTrackTitle("Faixa ft. Fulano"))
    }

    @Test
    fun limpeza_de_titulo_mantem_a_marca_de_versao() {
        assertEquals("Faixa (Live)", LyricsMatchers.cleanTrackTitle("Faixa (Live)"))
    }

    @Test
    fun limpeza_de_titulo_nunca_devolve_vazio() {
        assertEquals("(Official Video)", LyricsMatchers.cleanTrackTitle("(Official Video)"))
    }

    @Test
    fun limpa_feat_do_artista() {
        assertEquals("Artista", LyricsMatchers.cleanArtistName("Artista ft. Outro"))
        assertEquals("Artista", LyricsMatchers.cleanArtistName("Artista (feat. Outro)"))
    }

    @Test
    fun artista_de_busca_perde_o_sufixo_topic() {
        assertEquals("Artista", LyricsMatchers.forSearchArtist("Artista - Topic"))
        assertEquals("Artista", LyricsMatchers.forSearchArtist("Artista"))
    }

    @Test
    fun titulo_de_busca_tira_credito_mas_nao_fica_vazio() {
        assertEquals("Faixa", LyricsMatchers.forSearchTitle("Faixa (feat. Fulano)"))
        assertTrue(LyricsMatchers.forSearchTitle("(Official Video)").isNotBlank())
    }

    // ---------- similaridade ----------

    @Test
    fun similaridade_por_palavras_em_comum() {
        assertTrue(LyricsMatchers.isSimilar("the dark side", "dark side the"))
        assertFalse(LyricsMatchers.isSimilar("alpha beta", "gamma delta"))
        assertFalse(LyricsMatchers.isSimilar("", "algo"))
    }
}
