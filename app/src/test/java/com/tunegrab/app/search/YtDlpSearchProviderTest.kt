package com.tunegrab.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpSearchProviderTest {

    @Test
    fun `alvo usa ytsearch com limite exato`() {
        assertEquals(
            "ytsearch25:música teste",
            buildSearchTarget("música teste", 25)
        )
    }

    @Test
    fun `alvo limita resultado a vinte e cinco`() {
        assertTrue(buildSearchTarget("música", 100).startsWith("ytsearch25:"))
        assertTrue(buildSearchTarget("música", 0).startsWith("ytsearch1:"))
    }

    @Test
    fun `alvo preserva consulta sem executar comando`() {
        val target = buildSearchTarget("música --exec rm", 25)

        assertEquals("ytsearch25:música --exec rm", target)
        assertTrue(target.startsWith("ytsearch25:"))
    }

    @Test
    fun `alvo recusa prefixo ytsearch malicioso`() {
        val error = runCatching { buildSearchTarget("ytsearch10:termo", 25) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
