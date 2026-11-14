package com.tunegrab.app.security

import java.security.MessageDigest

/** CookieManager returns an HTTP header, not a ready-to-trust Netscape cookie file. */
object CookieHeaders {
    private val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val loginNames = setOf("SID", "HSID", "SSID", "SAPISID", "APISID", "__Secure-1PSID", "__Secure-3PSID")

    fun parse(header: String?): List<Pair<String, String>> {
        if (header.isNullOrBlank() || header.length > 65_536 || header.any { it.isISOControl() }) return emptyList()
        return header.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (!token.matches(name) || value.isEmpty()) null else name to value
        }
    }

    fun hasLogin(header: String?): Boolean = parse(header).any { it.first in loginNames }

    fun loginSignature(header: String): String {
        val seed = parse(header).filter { it.first in loginNames }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString(";") { "${it.first}=${it.second}" }
        // SHA-256 is mandatory on Android. Never fall back to storing raw session cookies in prefs.
        return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
