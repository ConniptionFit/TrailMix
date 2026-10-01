package com.trailmix.app.data.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-23: salvaging the structured-summary reply when Gemini Nano's 256-token cap cuts it off. */
class StructuredJsonTest {

    /**
     * The real reply that made every long Auto note fall back to the deterministic summary on the
     * Pixel 9 Pro (v1.22.0 live test, case C1): fenced, cut off after ~900 characters mid-key, and
     * with each later section nested inside a stray `{"sections": [...]}`.
     */
    private val truncatedReply = "```json\n" +
        """{"sections": [{"heading": "Pricing", "bullets": [{"text": "[1:06] Enterprise pricing: 15% discount for over 200 seats.", "details": null}, {"text": "[1:06] Pricing page draft in two weeks.", "details": null}]}, """ +
        """{"sections": [{"heading": "Launch Date", "bullets": [{"text": "[1:06] March 15th is still the launch date.", "details": null}]}, """ +
        """{"sections": [{"heading": "Business Case Template", "bullets": [{"text": "[1:06] Build a business case template.", "details": null}, {"text": "[1:06] Rob to scope template by Tuesday.", "details": null}]}, """ +
        """{"sections": [{"heading"""

    @Test
    fun `the real truncated reply yields every complete section`() {
        val parsed = parse(truncatedReply)

        assertTrue(parsed.truncated)
        val sections = StructuredJson.sectionObjects(parsed.root.opt("sections"))
        assertEquals(listOf("Pricing", "Launch Date", "Business Case Template"), sections.map { it.getString("heading") })
        assertEquals(listOf(2, 1, 2), sections.map { it.getJSONArray("bullets").length() })
    }

    @Test
    fun `a complete reply passes through untouched and is not truncated`() {
        val reply = """{"sections": [{"heading": "A", "bullets": []}], "actionItems": []}"""
        val parsed = parse(reply)

        assertFalse(parsed.truncated)
        assertEquals(reply, StructuredJson.repair(reply))
    }

    @Test
    fun `a code fence and prose around a complete object are dropped`() {
        val reply = "Here is the note:\n```json\n{\"sections\": []}\n```\nHope that helps."

        assertEquals("""{"sections": []}""", StructuredJson.repair(reply))
        assertFalse(parse(reply).truncated)
    }

    @Test
    fun `a reply cut mid-string is cut back to the last complete element`() {
        val reply = """{"sections": [{"heading": "A", "bullets": [{"text": "one"}, {"text": "two, half writ"""
        val parsed = parse(reply)

        assertTrue(parsed.truncated)
        val bullets = parsed.root.getJSONArray("sections").getJSONObject(0).getJSONArray("bullets")
        assertEquals(1, bullets.length())
        assertEquals("one", bullets.getJSONObject(0).getString("text"))
    }

    @Test
    fun `braces and escaped quotes inside strings do not confuse the scan`() {
        val reply = """{"sections": [{"heading": "A \"quoted\" } ] {", "bullets": [{"text": "x ] }"}]}], "actionItems": [{"text": "cut"""
        val parsed = parse(reply)

        val heading = StructuredJson.sectionObjects(parsed.root.opt("sections")).single().getString("heading")
        assertEquals("A \"quoted\" } ] {", heading)
    }

    @Test
    fun `nothing salvageable is null rather than an exception`() {
        assertNull(StructuredJson.parse("no json here"))
        assertNull(StructuredJson.parse(""))
        assertNull(StructuredJson.parse("{\"sections\": [{\"heading"))
        assertEquals("", StructuredJson.repair("no json here"))
    }

    @Test
    fun `sections are found in the normal shape and ignore objects without a heading`() {
        val root = JSONObject("""{"sections": [{"heading": "A", "bullets": []}, {"note": "x"}, {"heading": "B", "bullets": []}]}""")

        assertEquals(listOf("A", "B"), StructuredJson.sectionObjects(root.opt("sections")).map { it.getString("heading") })
        assertTrue(StructuredJson.sectionObjects(null).isEmpty())
    }

    private fun parse(raw: String): StructuredJson.Parsed {
        val parsed = StructuredJson.parse(raw)
        assertNotNull("expected a salvageable object in: $raw", parsed)
        return parsed!!
    }
}
