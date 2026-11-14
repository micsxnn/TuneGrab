package com.tunegrab.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadHelpersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- nome de arquivo ----------

    @Test
    fun troca_os_caracteres_proibidos_por_underline() {
        assertEquals("a_b_c_d_e_f_g_h_i.mp3", DownloadHelpers.sanitizeFileName("a/b\\c:d*e?f\"g<h>i.mp3"))
    }

    @Test
    fun nome_normal_passa_intacto_inclusive_com_acento_e_espaco() {
        assertEquals("Coração Selvagem (ao vivo).mp3", DownloadHelpers.sanitizeFileName("Coração Selvagem (ao vivo).mp3"))
    }

    @Test
    fun nome_vazio_ou_so_espacos_vira_audio() {
        assertEquals("audio", DownloadHelpers.sanitizeFileName(""))
        assertEquals("audio", DownloadHelpers.sanitizeFileName("   "))
    }

    @Test
    fun nome_so_com_pontos_nao_pode_apontar_pro_diretorio_pai() {
        assertEquals("audio", DownloadHelpers.sanitizeFileName(".."))
        assertEquals("audio", DownloadHelpers.sanitizeFileName("."))
    }

    @Test
    fun tentativa_de_path_traversal_perde_as_barras() {
        assertEquals(".._.._etc_passwd", DownloadHelpers.sanitizeFileName("../../etc/passwd"))
    }

    @Test
    fun caracteres_de_controle_sao_trocados() {
        assertEquals("a_b_c.mp3", DownloadHelpers.sanitizeFileName("a\nb\u0000c.mp3"))
    }

    // ---------- nome único ----------

    @Test
    fun uniqueFile_devolve_o_proprio_nome_quando_livre() {
        assertEquals("x.mp3", DownloadHelpers.uniqueFile(tmp.root, "x.mp3").name)
    }

    @Test
    fun uniqueFile_numera_quando_ja_existe() {
        tmp.newFile("x.mp3")
        assertEquals("x (1).mp3", DownloadHelpers.uniqueFile(tmp.root, "x.mp3").name)
        tmp.newFile("x (1).mp3")
        assertEquals("x (2).mp3", DownloadHelpers.uniqueFile(tmp.root, "x.mp3").name)
    }

    @Test
    fun uniqueFile_funciona_sem_extensao() {
        tmp.newFile("faixa")
        assertEquals("faixa (1)", DownloadHelpers.uniqueFile(tmp.root, "faixa").name)
    }

    @Test
    fun uniqueFile_so_quebra_na_ultima_extensao() {
        tmp.newFile("a.b.mp3")
        assertEquals("a.b (1).mp3", DownloadHelpers.uniqueFile(tmp.root, "a.b.mp3").name)
    }

    // ---------- porcentagem ----------

    @Test
    fun percent_calcula_e_limita_entre_0_e_100() {
        assertEquals(50, DownloadHelpers.percent(50, 100))
        assertEquals(33, DownloadHelpers.percent(1, 3))
        assertEquals(100, DownloadHelpers.percent(150, 100))
        assertEquals(0, DownloadHelpers.percent(-5, 100))
    }

    @Test
    fun percent_com_total_desconhecido_e_zero() {
        assertEquals(0, DownloadHelpers.percent(1234, 0))
        assertEquals(0, DownloadHelpers.percent(1234, -1))
    }

    @Test
    fun percent_nao_estoura_com_arquivos_grandes() {
        val total = 5L * 1024 * 1024 * 1024 // 5 GiB
        assertEquals(50, DownloadHelpers.percent(total / 2, total))
    }

    // ---------- velocidade do yt-dlp ----------

    @Test
    fun le_velocidade_em_MiB_da_linha_do_ytdlp() {
        val bps = DownloadHelpers.parseSpeedBytesPerSec("[download]  45.3% of 123.45MiB at  2.50MiB/s ETA 01:23")
        assertEquals(2_621_440L, bps)
    }

    @Test
    fun le_velocidade_em_KiB_e_GiB() {
        assertEquals(512L * 1024, DownloadHelpers.parseSpeedBytesPerSec("at 512KiB/s"))
        assertEquals(1024L * 1024 * 1024, DownloadHelpers.parseSpeedBytesPerSec("at 1GiB/s"))
    }

    @Test
    fun le_velocidade_em_bytes_sem_prefixo() {
        assertEquals(800L, DownloadHelpers.parseSpeedBytesPerSec("at 800iB/s"))
    }

    @Test
    fun linha_sem_velocidade_devolve_nulo() {
        assertNull(DownloadHelpers.parseSpeedBytesPerSec(null))
        assertNull(DownloadHelpers.parseSpeedBytesPerSec(""))
        assertNull(DownloadHelpers.parseSpeedBytesPerSec("[download] Destination: x.webm"))
        assertNull(DownloadHelpers.parseSpeedBytesPerSec("at Unknown B/s"))
    }

    @Test
    fun velocidade_zero_ou_invalida_devolve_nulo() {
        assertNull(DownloadHelpers.parseSpeedBytesPerSec("at 0.00MiB/s"))
        assertNotNull(DownloadHelpers.parseSpeedBytesPerSec("at 0.01MiB/s"))
    }
}
