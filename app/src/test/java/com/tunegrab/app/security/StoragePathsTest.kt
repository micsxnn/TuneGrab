package com.tunegrab.app.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StoragePathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun only_the_empty_key_is_the_root_not_a_whitespace_folder() {
        assertTrue(StoragePaths.isRoot(""))
        for (key in listOf(" ", "  ", "A/ ", "Forró")) assertFalse(StoragePaths.isRoot(key))
    }

    @Test fun root_and_legitimate_nested_names_are_allowed() {
        for (key in listOf("", "Forró", "Forró/2026", "Meu %20 Acústico", " ")) {
            assertTrue(key, StoragePaths.isKey(key))
        }
    }

    @Test fun traversal_absolute_paths_and_empty_segments_are_rejected() {
        for (key in listOf("..", ".", "../private", "A/../../B", "/Download", "A//B", "A/", "A\\B")) {
            assertFalse(key, StoragePaths.isKey(key))
        }
    }

    @Test fun provider_names_cannot_inject_paths_or_controls() {
        for (name in listOf("", ".", "..", "a/b", "a\\b", "nul\u0000", "line\nfeed", "tab\t")) {
            assertFalse(StoragePaths.isName(name))
        }
    }

    @Test fun resolution_stays_inside_the_root() {
        val root = temporary.newFolder("root")
        assertEquals(root.canonicalFile, StoragePaths.resolve(root, ""))
        assertEquals(File(root, "A/B").canonicalFile, StoragePaths.resolve(root, "A/B"))
        assertNull(StoragePaths.resolve(root, "../outside"))
    }

    @Test fun symlink_to_the_root_cannot_turn_a_child_delete_into_a_root_delete() {
        val root = temporary.newFolder("root")
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        assertNull(StoragePaths.resolve(root, "loop"))
        assertNull(StoragePaths.resolve(root, "loop/loop"))
    }

    @Test fun symlink_to_a_sibling_cannot_escape_even_with_a_shared_prefix() {
        val root = temporary.newFolder("music")
        val other = temporary.newFolder("music-private")
        Files.createSymbolicLink(File(root, "link").toPath(), other.toPath())
        assertNull(StoragePaths.resolve(root, "link"))
        assertNull(StoragePaths.resolve(root, "link/not-created.mp3"))
    }
}
