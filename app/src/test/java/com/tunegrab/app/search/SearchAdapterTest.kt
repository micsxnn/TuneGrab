package com.tunegrab.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchAdapterTest {

    @Test
    fun `formata duracao valida`() {
        assertEquals("3:05", formatDuration(185))
        assertEquals("1:02:03", formatDuration(3723))
    }

    @Test
    fun `formata duracao desconhecida como vazio`() {
        assertEquals("", formatDuration(null))
        assertEquals("", formatDuration(0))
    }
}
