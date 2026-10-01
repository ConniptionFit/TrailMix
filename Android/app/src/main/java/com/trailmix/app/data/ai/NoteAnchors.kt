package com.trailmix.app.data.ai

import java.util.Locale

/**
 * AI-17: the user's typed notes as an ordered list of **anchors** — the backbone of the merged
 * note. Granola's core behavior is that what the user wrote down decides the note's structure
 * and the transcript only enriches it; before this, the merge prompt merely said "prefer typed
 * notes when they conflict" and nothing guaranteed a typed point survived into the result.
 *
 * Pure and Android-free (same idiom as [SummaryText]) so the parsing is unit tested and so the
 * deterministic (no-AI) path can use exactly the same anchors the model prompt does.
 *
 * Parsing rules:
 *  - Typed notes with line breaks are read **one line per anchor**; typed notes with no line
 *    break at all (a pasted paragraph, dictated text) are split into sentences with
 *    [SummaryText.splitSentences].
 *  - Blank lines and lines that are only list markers are ignored.
 *  - Text is cleaned: list/heading markers stripped, whitespace collapsed, trimmed.
 */
object NoteAnchors {

    enum class Kind {
        /** A line starting with `#` — the user named a topic. Text is Title Cased. */
        HEADING,

        /** A bullet (`-`, `*`, `•`) or plain line: something the user wants covered. */
        POINT,

        /** Ends with `?` — the user wants an answer; answered from the transcript or left open. */
        QUESTION,

        /** The user's own opinion ([JUDGMENT_CUES]); attributed to "you", never stated as fact. */
        JUDGMENT,
    }

    data class Anchor(val kind: Kind, val text: String)

    /**
     * True when the typed text has deliberate structure — more than one line, or a heading /
     * bullet marker on its first line. The deterministic path only builds anchor sections for
     * such notes: a single pasted paragraph keeps the legacy flat "Your notes" section there
     * (its sentences are not separate user intentions). The model prompt and [AnchorCoverage]
     * use [parse] regardless, since there a sentence-level anchor is cheap and harmless.
     */
    fun isStructured(typedFragments: String): Boolean {
        val t = typedFragments.trim()
        return '\n' in t || t.startsWith("#") || LIST_MARKER.containsMatchIn(t)
    }

    /** Ordered anchors for [typedFragments]; empty when there is nothing typed. */
    fun parse(typedFragments: String): List<Anchor> {
        if (typedFragments.isBlank()) return emptyList()
        val rawLines = if ('\n' in typedFragments.trim()) {
            typedFragments.lines()
        } else {
            SummaryText.splitSentences(typedFragments)
        }
        return rawLines.mapNotNull { anchorFor(it) }
    }

    private fun anchorFor(rawLine: String): Anchor? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null
        val isHeading = trimmed.startsWith("#")
        val text = trimmed
            .let { if (isHeading) it.trimStart('#') else it.replace(LIST_MARKER, "") }
            .replace(WHITESPACE, " ")
            .trim()
        if (text.isEmpty() || text.none { it.isLetterOrDigit() }) return null
        return when {
            isHeading -> Anchor(Kind.HEADING, titleCase(text))
            text.endsWith("?") -> Anchor(Kind.QUESTION, text)
            isJudgment(text) -> Anchor(Kind.JUDGMENT, text)
            else -> Anchor(Kind.POINT, text)
        }
    }

    /** True when [text] contains one of [JUDGMENT_CUES] as a word (or word prefix for stems). */
    fun isJudgment(text: String): Boolean =
        JUDGMENT_PATTERN.containsMatchIn(text.lowercase(Locale.ROOT).replace('’', '\''))

    /** The cue words themselves, lowercased — exposed so coverage matching can ignore them. */
    fun cueWords(): Set<String> = CUE_WORDS

    /**
     * Title Case for a heading: first letter of each word upper-cased, minor words ("of", "the",
     * ...) left lower unless first, and any word that already has an interior capital (`ARR`,
     * `iOS`, `OKRs`) left exactly as typed.
     */
    fun titleCase(text: String): String =
        text.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, word ->
            when {
                word.drop(1).any { it.isUpperCase() } -> word
                i > 0 && word.lowercase(Locale.ROOT) in MINOR_WORDS -> word.lowercase(Locale.ROOT)
                else -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }.joinToString(" ")

    private val LIST_MARKER = Regex("^\\s*(?:[-*•]+|\\d+[.)])\\s+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Opinion cues. Deliberately conservative: only phrases that are unambiguous first-person
     * doubt or concern. "I think" and "maybe" are left out on purpose — they hedge facts as
     * often as they voice opinions, and a false JUDGMENT would demote a factual note to
     * "You noted: ...". (The prompt also tells the model to attribute opinions, so a missed
     * cue is not fatal.)
     */
    private val JUDGMENT_CUES = listOf(
        "don't buy", "do not buy", "don't believe", "not convinced", "unconvinced",
        "skeptical", "sceptical", "seems", "concern", "worried", "red flag", "doubt",
    )

    private val CUE_WORDS: Set<String> = JUDGMENT_CUES.flatMap { it.split(' ') }
        .filter { it.length > 2 }.toSet()

    private val JUDGMENT_PATTERN = Regex(
        JUDGMENT_CUES.joinToString("|", prefix = "\\b(?:", postfix = ")\\w*\\b") { Regex.escape(it) },
    )

    private val MINOR_WORDS = setOf("a", "an", "the", "of", "in", "on", "to", "and", "or", "for", "vs", "at", "by")
}
