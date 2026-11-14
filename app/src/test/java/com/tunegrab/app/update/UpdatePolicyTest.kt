package com.tunegrab.app.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val release = "https://github.com/micsxnn/TuneGrab/releases/tag/v0.22.28"
    private val apk = "https://github.com/micsxnn/TuneGrab/releases/download/v0.22.28/TuneGrab-v0.22.28.apk"

    @Test fun stable_versions_cannot_be_used_for_path_traversal() {
        assertTrue(UpdatePolicy.isVersion("0.22.28"))
        listOf("../secret", "/tmp/a", "0.22.28/../a", "0.22.28-beta1", "", "v0.22.28", "0.22.28\n").forEach {
            assertFalse(it, UpdatePolicy.isVersion(it))
        }
    }

    @Test fun releases_and_apks_must_belong_to_the_exact_project_and_version() {
        assertTrue(UpdatePolicy.isRelease(release, "0.22.28"))
        assertTrue(UpdatePolicy.isApkUrl(apk, "0.22.28"))
        assertFalse(UpdatePolicy.isApkUrl(apk, "0.22.29"))
        listOf(apk.replace("micsxnn", "attacker"), apk.replace("github.com", "github.com.evil.example"),
            apk.replace("https:", "http:"), apk.replace(".apk", "-debug.apk"), apk + "?redirect=evil",
            apk.replace("TuneGrab-v0.22.28.apk", "TuneGrab-a%2Fb.apk")).forEach {
            assertFalse(it, UpdatePolicy.isApkUrl(it, "0.22.28"))
        }
        assertFalse(UpdatePolicy.isRelease(release.replace("micsxnn", "attacker"), "0.22.28"))
        assertFalse(UpdatePolicy.isRelease(release + "/extra", "0.22.28"))
    }

    @Test fun only_github_release_cdn_redirects_are_allowed() {
        assertTrue(UpdatePolicy.allowedTransport("https://release-assets.githubusercontent.com/signed?token=x"))
        assertFalse(UpdatePolicy.allowedTransport("https://evil.example/file.apk"))
        assertFalse(UpdatePolicy.allowedTransport("http://release-assets.githubusercontent.com/file.apk"))
        assertFalse(UpdatePolicy.allowedTransport("https://user@github.com/file.apk"))
    }

    @Test fun checksum_is_bound_to_one_exact_filename() {
        val hash = "a".repeat(64)
        assertEquals(hash, UpdatePolicy.checksumFor("$hash  TuneGrab-v0.22.28.apk\n", "TuneGrab-v0.22.28.apk"))
        assertEquals(hash, UpdatePolicy.checksumFor("$hash *TuneGrab-v0.22.28.apk\r\n", "TuneGrab-v0.22.28.apk"))
        assertNull(UpdatePolicy.checksumFor("$hash  TuneGrab-v0.22.28-debug.apk", "TuneGrab-v0.22.28.apk"))
        assertNull(UpdatePolicy.checksumFor("bad  TuneGrab-v0.22.28.apk", "TuneGrab-v0.22.28.apk"))
        assertNull(UpdatePolicy.checksumFor("$hash  app.apk\n$hash  app.apk", "app.apk"))
        assertEquals("https://github.com/micsxnn/TuneGrab/releases/download/v0.22.28/SHA256SUMS", UpdatePolicy.checksumUrl(release))
    }

    @Test fun signer_can_stay_the_same_or_rotate_forward_but_not_backward() {
        assertTrue(UpdatePolicy.trustsSigner(setOf("old"), setOf("old"), setOf("old")))
        assertTrue(UpdatePolicy.trustsSigner(setOf("old"), setOf("new"), setOf("old", "new")))
        assertFalse(UpdatePolicy.trustsSigner(setOf("new"), setOf("old"), setOf("old")))
        assertFalse(UpdatePolicy.trustsSigner(setOf("old"), setOf("attacker"), setOf("attacker")))
        assertFalse(UpdatePolicy.trustsSigner(emptySet(), setOf("new"), setOf("old", "new")))
        assertFalse(UpdatePolicy.trustsSigner(setOf("old"), emptySet(), setOf("old")))
    }

    @Test fun multiple_signers_require_an_exact_match() {
        assertTrue(UpdatePolicy.trustsSigner(setOf("a", "b"), setOf("b", "a"), emptySet()))
        assertFalse(UpdatePolicy.trustsSigner(setOf("a", "b"), setOf("b"), setOf("a", "b")))
    }
}
