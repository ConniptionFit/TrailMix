package com.trailmix.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteMatchTest {

    @Test
    fun `tokenize lowercases and drops short and non-alphanumeric tokens`() {
        assertEquals(setOf("hello", "world"), QuoteMatch.tokenize("Hello, WORLD! a an"))
    }

    @Test
    fun `overlapScore is the fraction of words found in the other set`() {
        assertEquals(0.5, QuoteMatch.overlapScore(setOf("a", "b"), setOf("a")), 0.0001)
    }

    @Test
    fun `overlapScore is zero when either set is empty`() {
        assertEquals(0.0, QuoteMatch.overlapScore(emptySet(), setOf("a")), 0.0001)
        assertEquals(0.0, QuoteMatch.overlapScore(setOf("a"), emptySet()), 0.0001)
    }

    @Test
    fun `bestOf returns the candidate with the highest overlap`() {
        val candidates = listOf(
            "low" to setOf("x"),
            "high" to setOf("a", "b", "c"),
        )
        assertEquals("high", QuoteMatch.bestOf(setOf("a", "b", "c", "d"), candidates))
    }

    @Test
    fun `bestOf returns null when nothing overlaps`() {
        val candidates = listOf("a" to setOf("x"), "b" to setOf("y"))
        assertNull(QuoteMatch.bestOf(setOf("z"), candidates))
    }

    @Test
    fun `bestOf returns null for empty candidates`() {
        assertNull(QuoteMatch.bestOf(setOf("a"), emptyList()))
    }
}
