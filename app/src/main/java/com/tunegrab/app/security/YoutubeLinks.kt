package com.tunegrab.app.security

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Locale

/** Validate at every trust boundary, not only in Android's intent filters. */
object YoutubeLinks {
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val playlistId = Regex("[A-Za-z0-9_-]{2,128}")

    private fun parse(raw: String): HttpUrl? {
        if (raw.length > 8192 || raw.any { it.isISOControl() } || '\\' in raw) return null
        val text = raw.trim()
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.port != if (url.isHttps) 443 else 80) return null
        return url
    }

    private fun id(url: HttpUrl): String? {
        val segments = url.pathSegments.let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val candidate = when {
            url.host == "youtu.be" && segments.size == 1 -> segments[0]
            url.encodedPath == "/watch" && url.queryParameterValues("v").size == 1 -> url.queryParameter("v")
            segments.size == 2 && segments[0] in setOf("shorts", "live", "embed", "v") -> segments[1]
            else -> null
        }
        return candidate?.takeIf { videoId.matches(it) }
    }

    /** Only supported YouTube video/playlist URLs; legacy HTTP links become HTTPS. */
    fun downloadUrl(raw: String): String? {
        val text = raw.trim()
        val url = parse(if ("://" in text) text else "https://$text") ?: return null
        if (url.host !in hosts) return null
        val isPlaylist = url.host != "youtu.be" && url.encodedPath == "/playlist" &&
            url.queryParameterValues("list").size == 1 &&
            url.queryParameter("list")?.let(playlistId::matches) == true
        if (id(url) == null && !isPlaylist) return null
        return url.newBuilder().scheme("https").port(443).fragment(null).build().toString()
    }

    fun videoIdOf(raw: String?): String? {
        val safe = raw?.let(::downloadUrl) ?: return null
        return id(safe.toHttpUrlOrNull() ?: return null)
    }

    fun canonicalVideoUrl(raw: String): String? = videoIdOf(raw)?.let {
        "https://www.youtube.com/watch?v=$it"
    }

    fun playlistIdOf(raw: String): String? {
        val safe = downloadUrl(raw)?.toHttpUrlOrNull() ?: return null
        if (safe.queryParameterValues("list").size != 1) return null
        return safe.queryParameter("list")?.takeIf(playlistId::matches)
    }

    fun isPlaylistUrl(raw: String): Boolean = downloadUrl(raw)?.toHttpUrlOrNull()?.let {
        it.encodedPath == "/playlist" && playlistIdOf(raw) != null
    } ?: false

    /** Keep Google login/consent inside the browser, but never allow credentials or cleartext. */
    fun browserUrl(raw: String): String? {
        val url = parse(raw) ?: return null
        val host = url.host
        if (host != "youtu.be" && host != "youtube.com" && !host.endsWith(".youtube.com") &&
            host != "google.com" && !host.endsWith(".google.com")) return null
        return url.newBuilder().scheme("https").port(443).build().toString()
    }

    /** External app launches require a user gesture as well; dangerous URI schemes stay blocked. */
    fun mayOpenExternally(raw: String): Boolean {
        if (raw.length > 8192 || raw.any { it.isISOControl() } || '\\' in raw) return false
        val scheme = runCatching { URI(raw).scheme?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return when (scheme) {
            "https", "http" -> parse(raw) != null
            "mailto", "tel", "market" -> true
            else -> false
        }
    }

    /** Remote JavaScript is executable code: accept only Google's HTTPS BotGuard origins. */
    fun botguardInterpreterUrl(raw: String): String? {
        val url = parse(if (raw.startsWith("//")) "https:$raw" else raw) ?: return null
        if (!url.isHttps) return null
        val host = url.host
        if (listOf("youtube.com", "google.com", "gstatic.com").none { host == it || host.endsWith(".$it") }) return null
        return url.toString()
    }
}
