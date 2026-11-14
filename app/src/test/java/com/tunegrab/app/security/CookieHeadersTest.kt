package com.tunegrab.app.security

import org.junit.Assert.*
import org.junit.Test

class CookieHeadersTest {
    @Test fun login_uses_exact_cookie_names_not_substrings_in_values() {
        assertTrue(CookieHeaders.hasLogin("VISITOR_INFO=x; SID=session"))
        assertTrue(CookieHeaders.hasLogin("__Secure-1PSID=session"))
        assertFalse(CookieHeaders.hasLogin("NOTSID=x; value=SID=not-a-cookie"))
        assertFalse(CookieHeaders.hasLogin("__Secure-1PSIDCC=x"))
        assertFalse(CookieHeaders.hasLogin("SID="))
    }

    @Test fun cookie_file_line_injection_is_rejected() {
        for (character in listOf('\r', '\n', '\t', '\u0000')) {
            assertTrue(CookieHeaders.parse("SID=token${character}.evil.example").isEmpty())
        }
        assertTrue(CookieHeaders.parse("SID=" + "x".repeat(65_536)).isEmpty())
    }

    @Test fun valid_cookie_values_preserve_equals_signs() {
        assertEquals(listOf("SID" to "abc==", "YSC" to "123"), CookieHeaders.parse("SID=abc==; YSC=123"))
    }

    @Test fun signature_is_order_independent_and_never_raw_credentials() {
        val first = CookieHeaders.loginSignature("SID=super-secret; HSID=other; YSC=one")
        val second = CookieHeaders.loginSignature("YSC=two; HSID=other; SID=super-secret")
        assertEquals(first, second)
        assertTrue(Regex("[0-9a-f]{64}").matches(first))
        assertFalse(first.contains("super-secret"))
        assertNotEquals(first, CookieHeaders.loginSignature("SID=a-different-session; HSID=other"))
    }
}
