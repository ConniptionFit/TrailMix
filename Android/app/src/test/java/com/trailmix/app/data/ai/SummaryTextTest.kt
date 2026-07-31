package com.trailmix.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-05: the text-quality pass that makes dictated speech readable as bullets. */
class SummaryTextTest {

    @Test
    fun `strips filler words and doubled words`() {
        assertEquals(
            "The latency dropped by half.",
            SummaryText.stripFiller("The latency, you know, dropped by half."),
        )
        assertEquals(
            "We measured the the throughput.".let { SummaryText.stripFiller(it) },
            "We measured the throughput.",
        )
    }

    @Test
    fun `strips leading discourse markers and re-capitalizes`() {
        assertEquals("The index is the bottleneck.", SummaryText.stripFiller("So, the index is the bottleneck."))
        assertEquals("We shipped it.", SummaryText.stripFiller("Okay so, um, we shipped it."))
    }

    @Test
    fun `leaves substantive hedges alone`() {
        val sentence = "Roughly forty percent of requests were about search."
        assertEquals(sentence, SummaryText.stripFiller(sentence))
    }

    @Test
    fun `low-content backchannel and stage directions are recognized`() {
        assertTrue(SummaryText.isLowContent("Right."))
        assertTrue(SummaryText.isLowContent("Next slide."))
        assertTrue(SummaryText.isLowContent("Can everyone hear me?"))
        assertFalse(SummaryText.isLowContent("Vector search cut our p99 latency substantially."))
    }

    @Test
    fun `near-duplicates collapse to the first occurrence`() {
        val kept = SummaryText.dedupe(
            listOf(
                "The cache hit rate improved dramatically.",
                "The cache hit rate improved dramatically!",
                "Deployment moved to a weekly cadence.",
            ),
        )
        assertEquals(2, kept.size)
        assertTrue(kept[0].endsWith("."))
        assertTrue(kept.any { it.contains("weekly cadence") })
    }

    @Test
    fun `selectDistinct favors coverage over position and preserves chronology`() {
        val sentences = listOf(
            "Sharding reduced the write contention.",
            "Sharding reduced the write contention again.",
            "Caching improved read throughput.",
            "Observability tooling caught the regression.",
        )
        val picked = SummaryText.selectDistinct(sentences, limit = 3)
        assertEquals(3, picked.size)
        // The redundant near-copy loses its slot to genuinely new material...
        assertTrue(picked.any { it.contains("Caching") })
        assertTrue(picked.any { it.contains("Observability") })
        // ...and the survivors stay in their original order.
        assertEquals(picked, picked.sortedBy { sentences.indexOf(it) })
    }

    @Test
    fun `selectDistinct is a no-op below the limit`() {
        val sentences = listOf("One distinct thought here.", "Another separate matter entirely.")
        assertEquals(sentences, SummaryText.selectDistinct(sentences, limit = 5))
    }

    @Test
    fun `keywords surface repeated distinctive terms and honor exclusions`() {
        val text = "Embeddings power retrieval. Embeddings are cheap. Retrieval quality depends on embeddings."
        val terms = SummaryText.keywords(text, limit = 3)
        assertTrue(terms.contains("embeddings"))

        val excluded = SummaryText.keywords(text, limit = 3, exclude = setOf("embeddings"))
        assertFalse(excluded.contains("embeddings"))
    }

    @Test
    fun `commonTerms finds words shared across most windows`() {
        val common = SummaryText.commonTerms(
            listOf(
                "kubernetes scheduling latency",
                "kubernetes networking latency",
                "kubernetes storage latency",
            ),
        )
        assertTrue(common.contains("kubernetes"))
        assertTrue(common.contains("latency"))
        assertFalse(common.contains("networking"))
    }
}
