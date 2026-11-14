package com.tunegrab.app.security

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HttpBodiesTest {
    @Test fun normal_utf8_text_is_preserved() {
        assertEquals("Olá!", "Olá!".toResponseBody("text/plain; charset=utf-8".toMediaType()).boundedText(16))
    }

    @Test(expected = IOException::class) fun known_length_is_bounded() {
        "123456789".toResponseBody().boundedText(8)
    }

    @Test(expected = IOException::class) fun chunked_or_decompressed_body_is_bounded_too() {
        val body = object : ResponseBody() {
            private val input = Buffer().writeUtf8("123456789")
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L
            override fun source() = input
        }
        body.boundedText(8)
    }
}
