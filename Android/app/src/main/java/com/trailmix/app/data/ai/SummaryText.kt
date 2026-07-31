package com.trailmix.app.data.ai

import java.util.Locale

/**
 * Pure text helpers shared by the deterministic structurer and the AI processor (AI-05).
 *
 * These exist because a conference talk is a very different text than a 20-minute 1:1: it is
 * long, one-sided, dictated rather than written, and full of ASR disfluency. Selecting the
 * *first* N sentences — what TrailMix did through v1.9.0 — produces a note about the speaker
 * clearing their throat. Everything here is deterministic and offline; no model is involved.
 */
object SummaryText {

    /** Split into sentences on terminal punctuation or hard line breaks. */
    fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_BREAK)
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * Remove ASR disfluency: standalone filler tokens, doubled words ("the the"), and leading
     * discourse markers ("So, ...", "Okay, so ..."). Deliberately conservative — it only drops
     * tokens that carry no meaning, never hedges like "roughly" or "about" that change a claim.
     */
    fun stripFiller(sentence: String): String {
        var s = sentence
        FILLER_PATTERNS.forEach { pattern -> s = s.replace(pattern, " ") }
        s = s.replace(DOUBLED_WORD) { it.groupValues[1] }
        s = s.replace(LEADING_MARKERS, "")
        s = s.replace(MULTI_SPACE, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()
        // Re-capitalize if stripping a leading marker left a lowercase opener.
        return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    /** Lowercased content words (stopwords and very short tokens removed). */
    fun contentWords(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(NON_WORD)
            .filter { it.length > 2 && it !in STOP_WORDS }

    /**
     * True when a sentence carries too little substance to be worth a bullet — backchannel
     * ("right", "exactly"), stage directions ("next slide"), or anything under
     * [MIN_CONTENT_WORDS] distinct content words.
     */
    fun isLowContent(sentence: String): Boolean {
        val lower = sentence.lowercase(Locale.ROOT).trim()
        if (lower.length < MIN_SENTENCE_CHARS) return true
        if (STAGE_DIRECTIONS.any { lower.startsWith(it) || lower == it.trim() }) return true
        return contentWords(sentence).distinct().size < MIN_CONTENT_WORDS
    }

    /** Drop near-duplicates, keeping the first occurrence. Two sentences count as duplicates
     *  when their content-word sets overlap by [DUPLICATE_OVERLAP] or more in both directions. */
    fun dedupe(sentences: List<String>): List<String> {
        val kept = mutableListOf<String>()
        val keptWords = mutableListOf<Set<String>>()
        sentences.forEach { sentence ->
            val words = contentWords(sentence).toSet()
            if (words.isEmpty()) return@forEach
            val duplicate = keptWords.any { prior ->
                val shared = words.count { it in prior }.toDouble()
                shared / words.size >= DUPLICATE_OVERLAP && shared / prior.size >= DUPLICATE_OVERLAP
            }
            if (!duplicate) {
                kept += sentence
                keptWords += words
            }
        }
        return kept
    }

    /**
     * Pick up to [limit] sentences that between them cover the most distinct content — a greedy
     * maximal-marginal-coverage pass, so a talk that circles one point three times contributes
     * one bullet and spends the rest of the budget elsewhere. The result is returned in the
     * original order: chronology is what makes a talk readable, the scoring only chooses *which*
     * sentences survive, never their sequence.
     */
    fun selectDistinct(sentences: List<String>, limit: Int): List<String> {
        if (limit <= 0 || sentences.isEmpty()) return emptyList()
        if (sentences.size <= limit) return sentences
        val words = sentences.map { contentWords(it).toSet() }
        val covered = mutableSetOf<String>()
        val chosen = sortedSetOf<Int>()
        while (chosen.size < limit) {
            var bestIndex = -1
            var bestGain = 0
            sentences.indices.forEach { i ->
                if (i in chosen) return@forEach
                val gain = words[i].count { it !in covered }
                if (gain > bestGain) {
                    bestGain = gain
                    bestIndex = i
                }
            }
            // No remaining sentence adds anything new — stop rather than pad with redundancy.
            if (bestIndex < 0) break
            chosen += bestIndex
            covered += words[bestIndex]
        }
        return chosen.map { sentences[it] }
    }

    /**
     * The most distinctive terms in [text], used to label a time window. [exclude] carries the
     * transcript-wide common terms so every window doesn't get labelled with the talk's subject.
     */
    fun keywords(text: String, limit: Int, exclude: Set<String> = emptySet()): List<String> =
        contentWords(text)
            .filter { it.length >= MIN_KEYWORD_CHARS && it !in exclude }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .filter { it.value >= MIN_KEYWORD_HITS }
            .take(limit)
            .map { it.key }

    /**
     * Terms appearing in several of [texts] — too generic to distinguish one window from
     * another, so they're excluded from generated headings. Requires a term to occur in at
     * least two texts *and* clear the ratio: with only two windows, a term in one of them is
     * 50% of the corpus yet still perfectly distinguishing, and dropping it would leave every
     * short session's headings bare.
     */
    fun commonTerms(texts: List<String>, threshold: Double = COMMON_TERM_THRESHOLD): Set<String> {
        if (texts.size < 2) return emptySet()
        val perText = texts.map { contentWords(it).toSet() }
        return perText.flatten().distinct()
            .filter { term ->
                val hits = perText.count { term in it }
                hits >= 2 && hits.toDouble() / perText.size >= threshold
            }
            .toSet()
    }

    // Deliberately permissive: `selectDistinct` is the real quality filter, so this only has
    // to catch obvious backchannel. A higher bar here silently drops short but substantive
    // lines ("Performance improved."), which is the worse failure.
    private const val MIN_CONTENT_WORDS = 2
    private const val MIN_SENTENCE_CHARS = 12
    // Headings are scanned, not read — a 4-letter word is rarely the subject of a section,
    // so the bar for appearing in one is higher than the bar for being a content word.
    private const val MIN_KEYWORD_CHARS = 5
    private const val MIN_KEYWORD_HITS = 2
    private const val DUPLICATE_OVERLAP = 0.8
    private const val COMMON_TERM_THRESHOLD = 0.4

    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+|\\n+")
    private val NON_WORD = Regex("[^a-z0-9']+")
    private val MULTI_SPACE = Regex("\\s{2,}")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([,.!?;:])")
    private val DOUBLED_WORD = Regex("\\b(\\w+)(\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
    private val LEADING_MARKERS =
        Regex("^(?:(?:so|and|but|okay|ok|alright|right|well|now|yeah|uh|um)\\b[,\\s]+)+", RegexOption.IGNORE_CASE)

    /**
     * Disfluencies only — never hedges. "sort of" / "kind of" are deliberately absent: removing
     * them ("it sort of works" → "it works") changes the claim, which is worse than leaving a
     * clumsy sentence intact. Each pattern eats the punctuation on either side, so
     * "the latency, you know, dropped" closes up cleanly instead of leaving a stranded comma.
     */
    private val FILLER_PATTERNS = listOf(
        "um", "uh", "erm", "uhm", "ahem", "mm-hmm", "uh-huh", "you know", "i mean",
    ).map { Regex(",?\\s*\\b$it\\b\\s*,?", RegexOption.IGNORE_CASE) }

    /** Openers that are talk logistics, not content. */
    private val STAGE_DIRECTIONS = listOf(
        "next slide", "previous slide", "back to the slide", "can everyone hear",
        "is this on", "testing one two", "let me share my screen", "any questions so far",
        "we'll take questions", "thanks for having me", "thank you all for coming",
    )

    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "her", "was",
        "one", "our", "out", "day", "get", "has", "him", "his", "how", "its", "new", "now",
        "old", "see", "two", "way", "who", "boy", "did", "she", "use", "her", "many", "then",
        "them", "these", "some", "would", "make", "like", "into", "time", "look", "more",
        "come", "could", "just", "over", "also", "back", "after", "your", "work", "first",
        "well", "even", "want", "because", "very", "thing", "things", "really", "going",
        "know", "think", "that", "this", "with", "have", "from", "they", "been", "were",
        "said", "each", "which", "their", "will", "about", "there", "what", "when", "where",
        "here", "than", "much", "such", "only", "other", "should", "still", "being", "does",
        "doing", "done", "goes", "gets", "lot", "kind", "sort", "okay", "yeah", "right",
        "actually", "basically", "literally", "obviously", "maybe", "probably", "sure",
        // Prepositions, connectives and time words: frequent, long enough to clear the keyword
        // length bar, and completely uninformative as a section heading ("· across, today").
        "across", "today", "before", "during", "within", "around", "between", "without",
        "through", "against", "toward", "towards", "along", "among", "since", "until",
        "while", "above", "below", "under", "again", "always", "never", "often", "usually",
        "another", "though", "however", "something", "anything", "everything", "little",
        "entire", "whole", "turned", "means", "meant", "using", "used", "gives", "given",
    )
}
