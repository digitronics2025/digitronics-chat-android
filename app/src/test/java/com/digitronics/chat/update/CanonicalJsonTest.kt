package com.digitronics.chat.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vectors in src/test/resources/vectors were printed by the Worker's own
 * TypeScript `canonicalJson` (see the README there), so this pins byte-for-byte
 * agreement between the signer and the verifier.
 */
class CanonicalJsonTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("vectors/$name")) { "missing vector $name" }.readText(Charsets.UTF_8)

    @Test
    fun `matches the Worker's canonicalJson on tricky input`() {
        val input = Json.parseToJsonElement(resource("canonical-input.json"))
        assertEquals(resource("canonical-expected.txt"), CanonicalJson.encode(input))
    }

    @Test
    fun `matches the Worker's canonicalJson on a release manifest`() {
        val response = Json.parseToJsonElement(resource("signed-update-check.json")).jsonObject
        val manifest = response["data"]!!.jsonObject["offer"]!!.jsonObject["manifest"] as JsonObject
        assertEquals(resource("manifest-canonical-expected.txt"), CanonicalJson.encode(manifest))
    }

    @Test
    fun `numbers print as JavaScript prints them`() {
        assertEquals("1", CanonicalJson.jsNumber("1"))
        assertEquals("-12", CanonicalJson.jsNumber("-12"))
        assertEquals("0", CanonicalJson.jsNumber("-0"))
        assertEquals("100", CanonicalJson.jsNumber("1e2"))
        assertEquals("1.5", CanonicalJson.jsNumber("1.50"))
        assertEquals("0.001", CanonicalJson.jsNumber("0.001"))
        assertEquals("1e-7", CanonicalJson.jsNumber("0.0000001"))
        assertEquals("1e+21", CanonicalJson.jsNumber("1e21"))
        assertEquals("12345678901234567000", CanonicalJson.jsNumber("12345678901234567890"))
    }

    @Test
    fun `whitespace and key order in the input do not matter`() {
        val a = Json.parseToJsonElement("""{ "b" : [1, {"y":2,"x":1}], "a":"x" }""")
        val b = Json.parseToJsonElement("""{"a":"x","b":[1,{"x":1,"y":2}]}""")
        assertEquals("""{"a":"x","b":[1,{"x":1,"y":2}]}""", CanonicalJson.encode(a))
        assertEquals(CanonicalJson.encode(a), CanonicalJson.encode(b))
    }
}
