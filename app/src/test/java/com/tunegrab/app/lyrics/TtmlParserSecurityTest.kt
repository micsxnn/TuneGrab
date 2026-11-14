package com.tunegrab.app.lyrics

import org.junit.Assert.*
import org.junit.Test

class TtmlParserSecurityTest {
    private val verse = "<tt><body><p begin=\"1s\" end=\"2s\">Hello</p></body></tt>"

    @Test fun ordinary_timed_lyrics_still_parse() {
        val lines = TtmlParser.parse(verse)
        assertEquals(1, lines.size)
        assertEquals(1000L, lines.single().timeMs)
    }

    @Test fun external_entities_are_rejected_even_if_a_parser_ignores_security_features() {
        val xml = "<!DOCTYPE tt [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" + verse.replace("Hello", "&xxe;")
        assertTrue(TtmlParser.parse(xml).isEmpty())
    }

    @Test fun nested_entity_expansion_is_rejected() {
        val xml = "<!DOCTYPE tt [<!ENTITY a \"test\"><!ENTITY b \"&a;&a;&a;&a;\">]>" + verse.replace("Hello", "&b;")
        assertTrue(TtmlParser.parse(xml).isEmpty())
    }

    @Test fun excessive_nesting_is_rejected_before_recursive_word_collection() {
        val xml = "<tt><body><p begin=\"1s\">" + "<span>".repeat(128) + "Hello" +
            "</span>".repeat(128) + "</p></body></tt>"
        assertTrue(TtmlParser.parse(xml).isEmpty())
    }

    @Test fun too_many_elements_are_rejected_before_dom_allocation() {
        val xml = "<tt><body><p begin=\"1s\">" + "<span>x</span>".repeat(33_000) + "</p></body></tt>"
        assertTrue(TtmlParser.parse(xml).isEmpty())
    }

    @Test fun oversized_xml_is_rejected_before_dom_allocation() {
        assertTrue(TtmlParser.parse(verse + " ".repeat(2 * 1024 * 1024)).isEmpty())
    }
}
