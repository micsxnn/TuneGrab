package com.tunegrab.app.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure, testable validation for release metadata and signing-key succession. */
object UpdatePolicy {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val versionPattern = Regex("[0-9]{1,9}(?:\\.[0-9]{1,9}){1,3}")
    private val sha256Pattern = Regex("[a-fA-F0-9]{64}")
    private val transportHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    fun isVersion(value: String): Boolean = versionPattern.matches(value)
    fun isSha256(value: String): Boolean = sha256Pattern.matches(value)

    private fun parse(raw: String): HttpUrl? {
        if (raw.length > 8192 || raw.any { it.isISOControl() } || '\\' in raw) return null
        return raw.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
        }
    }

    fun allowedTransport(raw: String): Boolean = parse(raw)?.host in transportHosts

    fun releaseTag(raw: String): String? {
        val url = parse(raw) ?: return null
        if (url.host != "github.com" || url.query != null || url.fragment != null) return null
        val p = url.pathSegments
        if (p.size != 5 || !p[0].equals("micsxnn", true) || !p[1].equals("TuneGrab", true) ||
            p[2] != "releases" || p[3] != "tag") return null
        return p[4].takeIf { isVersion(it.removePrefix("v").removePrefix("V")) }
    }

    fun isRelease(raw: String, version: String): Boolean = isVersion(version) &&
        releaseTag(raw)?.removePrefix("v")?.removePrefix("V") == version

    fun isApkUrl(raw: String, version: String): Boolean {
        val url = parse(raw) ?: return false
        val p = url.pathSegments
        return url.host == "github.com" && url.query == null && url.fragment == null && p.size == 6 &&
            p[0].equals("micsxnn", true) && p[1].equals("TuneGrab", true) && p[2] == "releases" &&
            p[3] == "download" && p[4].removePrefix("v").removePrefix("V") == version && isVersion(version) &&
            p[5].startsWith("TuneGrab-") && p[5].endsWith(".apk") && !p[5].contains("debug", true) &&
            '/' !in p[5] && '\\' !in p[5]
    }

    fun checksumUrl(releaseUrl: String): String? = releaseTag(releaseUrl)?.let {
        "https://github.com/micsxnn/TuneGrab/releases/download/$it/SHA256SUMS"
    }

    fun checksumFor(text: String, filename: String): String? {
        if (text.length > 65_536) return null
        val matches = text.lineSequence().mapNotNull { line ->
            val match = Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(line.trimEnd('\r')) ?: return@mapNotNull null
            if (match.groupValues[2] == filename) match.groupValues[1].lowercase() else null
        }.toList()
        return matches.singleOrNull()
    }

    /** Permit forward rotation, never an old-key-only APK over an already-rotated installation. */
    fun trustsSigner(installed: Set<String>, candidate: Set<String>, candidateHistory: Set<String>): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return false
        return if (installed.size > 1 || candidate.size > 1) installed == candidate
        else candidate == installed || installed.single() in candidateHistory
    }
}
