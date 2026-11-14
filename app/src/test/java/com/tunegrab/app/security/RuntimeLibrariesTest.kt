package com.tunegrab.app.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.io.FileUtils
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Exercise API paths used by youtubedl-android after patching its old transitive libraries. */
class RuntimeLibrariesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun jackson_still_reads_the_existing_ytdlp_model() {
        val info = ObjectMapper().readValue(
            """{"id":"dQw4w9WgXcQ","title":"Olá!","webpage_url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","formats":[{"format_id":"140","ext":"m4a"}],"future_unknown_field":true}""",
            VideoInfo::class.java,
        )
        assertEquals("Olá!", info.title)
        assertEquals("140", info.formats!!.single().formatId)
    }

    @Test fun commons_io_still_copies_and_closes_the_runtime_source() {
        val output = File(temporary.root, "yt-dlp")
        val content = "synthetic runtime payload".toByteArray()
        FileUtils.copyInputStreamToFile(ByteArrayInputStream(content), output)
        assertArrayEquals(content, output.readBytes())
        FileUtils.deleteDirectory(temporary.root)
        assertFalse(output.exists())
    }

    @Test fun commons_compress_still_reads_the_runtime_archive_format() {
        val content = "synthetic archive payload".toByteArray()
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { output ->
            val entry = TarArchiveEntry("usr/runtime-test").apply { size = content.size.toLong() }
            output.putArchiveEntry(entry)
            output.write(content)
            output.closeArchiveEntry()
        }
        TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            assertEquals("usr/runtime-test", input.nextTarEntry!!.name)
            assertArrayEquals(content, input.readBytes())
            assertNull(input.nextTarEntry)
        }
    }
}
