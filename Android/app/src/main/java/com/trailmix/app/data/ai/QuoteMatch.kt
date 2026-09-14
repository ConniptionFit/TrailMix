package com.trailmix.app.data.ai

import java.util.Locale

/**
 * AI-09: word-overlap quote matching, pulled out of [OnDeviceAiProcessor.classify] so the logic
 * that finds a structured-summary bullet's best-supporting transcript line has one home and one
 * set of tests, rather than living only inside a class that needs ML Kit to instantiate.
 */
object QuoteMatch {

    /** Best-matching candidate for a pre-tokenized [words] set among pre-tokenized [candidates],
     *  or null when nothing shares a word with it. Candidates are tokenized once by the caller
     *  because the same set is typically scored against many pieces of text (every produced
     *  bullet, against the whole transcript). */
    fun <T> bestOf(words: Set<String>, candidates: List<Pair<T, Set<String>>>): T? =
        candidates
            .map { it.first to overlapScore(words, it.second) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0.0 }
            ?.first

    fun overlapScore(words: Set<String>, against: Set<String>): Double =
        if (against.isEmpty() || words.isEmpty()) 0.0 else words.count { it in against } / words.size.toDouble()

    fun tokenize(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9']+"))
            .filter { it.length > 2 }
            .toSet()
}
