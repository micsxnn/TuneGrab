package com.tunegrab.app.access

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AccessGateParsingTest {
    private fun b64(size: Int) = Base64.getEncoder().encodeToString(ByteArray(size) { it.toByte() })
    private fun entry() = JSONObject().put("id", "test-invitation").put("salt", b64(16)).put("hash", b64(32)).put("iters", 80_000)
    private fun document(entry: JSONObject) = JSONObject().put("hashes", JSONArray().put(entry)).toString()

    @Test fun legacy_invites_without_device_stay_compatible() {
        val parsed = AccessGate.parseEntries(document(entry()))!!
        assertEquals(1, parsed.size)
        assertNull(parsed[0].binding)
    }

    @Test fun valid_device_binding_is_preserved() {
        val input = entry().put("device", JSONObject().put("salt", b64(16)).put("hash", b64(32)))
        assertNotNull(AccessGate.parseEntries(document(input))!!.single().binding)
    }

    @Test fun malformed_device_must_not_turn_into_an_unbound_invite() {
        listOf<Any>(JSONObject.NULL, "invalid", JSONArray(), 7,
            JSONObject(), JSONObject().put("salt", b64(16)).put("hash", b64(16))).forEach { device ->
            assertTrue(device.toString(), AccessGate.parseEntries(document(entry().put("device", device)))!!.isEmpty())
        }
    }

    @Test fun unreasonable_kdf_parameters_are_rejected_before_hashing() {
        for (iterations in listOf(-1, 0, 1_000_001, Int.MAX_VALUE)) {
            assertTrue(AccessGate.parseEntries(document(entry().put("iters", iterations)))!!.isEmpty())
        }
        assertTrue(AccessGate.parseEntries(document(entry().put("salt", b64(4))))!!.isEmpty())
        assertTrue(AccessGate.parseEntries(document(entry().put("hash", "not base64!")))!!.isEmpty())
    }

    @Test fun oversized_lists_and_iteration_budgets_fail_closed() {
        val entries = JSONArray()
        repeat(257) { entries.put(entry()) }
        assertNull(AccessGate.parseEntries(JSONObject().put("hashes", entries).toString()))
        val expensive = JSONArray()
        repeat(21) { expensive.put(entry().put("iters", 1_000_000)) }
        assertNull(AccessGate.parseEntries(JSONObject().put("hashes", expensive).toString()))
        assertNull(AccessGate.parseEntries(" ".repeat(256 * 1024 + 1)))
    }

    @Test fun malformed_json_is_not_an_acceptance() {
        assertNull(AccessGate.parseEntries("{broken"))
        assertTrue(AccessGate.parseEntries("{\"hashes\":[]}")!!.isEmpty())
    }
}
