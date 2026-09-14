package com.trailmix.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-08: dictionary-only jargon/vocabulary correction, applied over ASR output. */
class VocabularyCorrectionTest {

    @Test
    fun `replaces a whole-word match case-insensitively`() {
        val terms = listOf(VocabularyTerm("cooper netties", "Kubernetes"))
        assertEquals(
            "We migrated to Kubernetes last quarter.",
            VocabularyCorrection.apply("We migrated to Cooper Netties last quarter.", terms),
        )
    }

    @Test
    fun `does not replace inside a larger word`() {
        val terms = listOf(VocabularyTerm("ai", "AI"))
        assertEquals("She said hello.", VocabularyCorrection.apply("She said hello.", terms))
    }

    @Test
    fun `applies every term in order`() {
        val terms = listOf(
            VocabularyTerm("gronk", "Grok"),
            VocabularyTerm("clod", "Claude"),
        )
        assertEquals(
            "Grok and Claude shipped a release.",
            VocabularyCorrection.apply("Gronk and Clod shipped a release.", terms),
        )
    }

    @Test
    fun `blank text or no terms returns the text unchanged`() {
        assertEquals("", VocabularyCorrection.apply("", listOf(VocabularyTerm("a", "b"))))
        assertEquals("unchanged text", VocabularyCorrection.apply("unchanged text", emptyList()))
    }

    @Test
    fun `text with no matching terms is untouched`() {
        val terms = listOf(VocabularyTerm("kubernetes", "Kubernetes"))
        assertEquals("Nothing to correct here.", VocabularyCorrection.apply("Nothing to correct here.", terms))
    }

    @Test
    fun `vocabulary terms round-trip through JSON`() {
        val terms = listOf(VocabularyTerm("cooper netties", "Kubernetes"), VocabularyTerm("clod", "Claude"))
        assertEquals(terms, VocabularyJson.decode(VocabularyJson.encode(terms)))
    }

    @Test
    fun `decode of null, blank, or malformed JSON returns empty`() {
        assertTrue(VocabularyJson.decode(null).isEmpty())
        assertTrue(VocabularyJson.decode("").isEmpty())
        assertTrue(VocabularyJson.decode("not json").isEmpty())
        assertTrue(VocabularyJson.decode("{\"broken\":").isEmpty())
    }

    @Test
    fun `entries missing wrong or correct are skipped, not crashed on`() {
        val json = """[{"w":"Valid","c":"Fixed"},{"w":"","c":"orphan"},{"w":"orphan","c":""},{"x":1}]"""
        assertEquals(listOf(VocabularyTerm("Valid", "Fixed")), VocabularyJson.decode(json))
    }

    @Test
    fun `encoded terms survive whitespace trimming on decode`() {
        val decoded = VocabularyJson.decode("""[{"w":"  spaced  ","c":"  fixed  "}]""")
        assertEquals(listOf(VocabularyTerm("spaced", "fixed")), decoded)
    }
}
