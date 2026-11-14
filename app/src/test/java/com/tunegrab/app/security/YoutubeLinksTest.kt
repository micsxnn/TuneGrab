package com.tunegrab.app.security

import org.junit.Assert.*
import org.junit.Test

class YoutubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun supported_video_links_keep_working() {
        listOf("https://www.youtube.com/watch?v=$id", "http://m.youtube.com/watch?v=$id&list=PL1234567890",
            "https://music.youtube.com/watch?v=$id", "https://youtu.be/$id?t=4", "https://youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id", "https://www.youtube.com/embed/$id", "https://youtube.com/v/$id").forEach {
            assertEquals(it, "https://www.youtube.com/watch?v=$id", YoutubeLinks.canonicalVideoUrl(it))
        }
        assertTrue(YoutubeLinks.downloadUrl("http://youtube.com/watch?v=$id")!!.startsWith("https://"))
        assertNotNull(YoutubeLinks.downloadUrl("youtube.com/watch?v=$id"))
    }

    @Test fun reject_foreign_origins_even_if_the_path_looks_like_youtube() {
        listOf("https://evil.example/watch?v=$id", "https://youtube.com.evil.example/watch?v=$id",
            "https://notyoutube.com/watch?v=$id", "https://youtube.com@evil.example/watch?v=$id",
            "https://evil.example@youtube.com/watch?v=$id", "https://youtube.com:8443/watch?v=$id",
            "https://youtube.com\\@evil.example/watch?v=$id", "file:///watch?v=$id", "http://127.0.0.1/watch?v=$id",
            "https://youtube.com\u0000.evil.example/watch?v=$id").forEach { assertNull(it, YoutubeLinks.downloadUrl(it)) }
    }

    @Test fun reject_injected_or_ambiguous_video_ids() {
        listOf("https://youtube.com/watch?v=bad", "https://youtube.com/watch?v=$id&v=aaaaaaaaaaa",
            "https://youtube.com/watch?v=${id}%26list%3DPL1234567890", "https://youtu.be/../../etc/passwd",
            "https://youtube.com/redirect?q=https://evil.example/", "https://youtube.com/channel/$id").forEach {
            assertNull(it, YoutubeLinks.downloadUrl(it))
        }
    }

    @Test fun playlist_validation_checks_the_origin_too() {
        assertTrue(YoutubeLinks.isPlaylistUrl("https://www.youtube.com/playlist?list=PL1234567890"))
        assertFalse(YoutubeLinks.isPlaylistUrl("https://evil.example/playlist?list=PL1234567890"))
        assertFalse(YoutubeLinks.isPlaylistUrl("https://youtube.com/watch?v=$id&list=PL1234567890"))
        assertEquals("PL1234567890", YoutubeLinks.playlistIdOf("https://youtube.com/watch?v=$id&list=PL1234567890"))
        assertNull(YoutubeLinks.playlistIdOf("https://evil.example/watch?v=$id&list=PL1234567890"))
        assertNull(YoutubeLinks.downloadUrl("https://youtube.com/playlist?list=PL123&list=PL456"))
    }

    @Test fun browser_login_is_preserved_without_trusting_lookalike_domains() {
        assertNotNull(YoutubeLinks.browserUrl("https://accounts.google.com/ServiceLogin"))
        assertNotNull(YoutubeLinks.browserUrl("https://consent.youtube.com/"))
        assertEquals("https://m.youtube.com/", YoutubeLinks.browserUrl("http://m.youtube.com/"))
        assertNull(YoutubeLinks.browserUrl("https://accounts.google.com.evil.example/"))
        assertNull(YoutubeLinks.browserUrl("https://user:password@accounts.google.com/"))
    }

    @Test fun external_intents_cannot_read_files_or_inject_javascript() {
        listOf("file:///data/data/com.tunegrab.app/files/cookies.txt", "content://private/secret", "javascript:alert(1)",
            "data:text/html,evil", "intent://evil#Intent;scheme=file;end", "https://example.com/\nattack").forEach {
            assertFalse(it, YoutubeLinks.mayOpenExternally(it))
        }
        assertTrue(YoutubeLinks.mayOpenExternally("https://example.com/"))
        assertTrue(YoutubeLinks.mayOpenExternally("mailto:test@example.com"))
        assertTrue(YoutubeLinks.mayOpenExternally("tel:123"))
    }

    @Test fun botguard_code_only_comes_from_official_https_origins() {
        assertEquals("https://www.google.com/js/bg/test.js", YoutubeLinks.botguardInterpreterUrl("//www.google.com/js/bg/test.js"))
        assertNotNull(YoutubeLinks.botguardInterpreterUrl("https://www.gstatic.com/bg/test.js"))
        listOf("http://www.google.com/test.js", "https://google.com.evil.example/test.js", "https://evil.example/test.js",
            "file:///sdcard/evil.js", "https://user@www.google.com/test.js").forEach {
            assertNull(it, YoutubeLinks.botguardInterpreterUrl(it))
        }
    }
}
