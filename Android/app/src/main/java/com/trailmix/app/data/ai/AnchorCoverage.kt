package com.trailmix.app.data.ai

import com.trailmix.app.data.ai.NoteAnchors.Anchor
import com.trailmix.app.data.ai.NoteAnchors.Kind
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TranscriptLine

/**
 * AI-17: the guarantee that **no typed note is dropped**. A prompt instruction ("one section
 * per anchor") is a request to a small model, not a constraint — so after the model's JSON is
 * parsed this checks every [NoteAnchors.Anchor] against the result and injects whatever is
 * missing, backed by the best-matching transcript lines. The same routine builds the whole
 * anchored structure on the deterministic path (apply it to an empty summary), which is why the
 * two paths cannot disagree about what "covered" means.
 *
 * Matching is word overlap ([QuoteMatch]) with a stop-word list, since [QuoteMatch.tokenize]
 * alone treats "the"/"and" as content. An anchor counts as represented when its content words
 * are mostly present in some section heading, bullet, detail, highlight or action item.
 *
 * Pure and Android-free.
 */
object AnchorCoverage {

    const val OPEN_QUESTIONS = "Open Questions"
    const val YOUR_TAKE = "Your Take"

    /** Fraction of an anchor's content words that must appear in a target to count as covered. */
    private const val REPRESENTED_MIN = 0.5

    /** Minimum overlap for a transcript sentence to count as evidence for an anchor. */
    private const val SUPPORT_MIN = 0.3

    /** Evidence lines pulled per injected section. */
    private const val MAX_SUPPORT = 3

    /** Weaker bar for hanging a judgment bullet on an existing topic section. */
    private const val ATTACH_MIN = 0.34

    /**
     * Return [summary] with every anchor represented. Existing content is never reordered or
     * removed; injected sections are placed right after the section that covers the previous
     * anchor (anchor order), and unanswered questions / unplaceable opinions collect in trailing
     * [OPEN_QUESTIONS] / [YOUR_TAKE] sections.
     */
    fun ensure(
        summary: StructuredSummary,
        anchors: List<Anchor>,
        transcript: List<TranscriptLine>,
    ): StructuredSummary {
        if (anchors.isEmpty()) return summary
        val units = transcript.flatMap { line ->
            SummaryText.splitSentences(line.text).map { TranscriptLine(line.label, it) }
        }
            .filterNot { it.text.trimEnd().endsWith("?") } // a question is not evidence
            .map { it to contentTokens(it.text) }

        val sections = summary.sections.toMutableList()
        val openQuestions = mutableListOf<SummaryBullet>()
        val judgments = mutableListOf<Anchor>()
        var lastIdx = -1

        for (anchor in anchors) {
            if (anchor.kind == Kind.JUDGMENT) {
                judgments += anchor
                continue
            }
            val covered = coveringSection(sections, anchor)
            if (covered != null) {
                lastIdx = covered
                continue
            }
            if (isCoveredElsewhere(summary, anchor)) continue

            val support = supportFor(anchor, units)
            if (anchor.kind == Kind.QUESTION && support.isEmpty()) {
                openQuestions += SummaryBullet(anchor.text, Provenance.FRAGMENT)
                continue
            }
            val bullets = support.ifEmpty { listOf(SummaryBullet(anchor.text, Provenance.FRAGMENT)) }
            lastIdx += 1
            sections.add(lastIdx.coerceAtMost(sections.size), SummarySection(headingFor(anchor), bullets))
        }

        // Opinions are the user's own view: hang them on the topic they are about, as an
        // explicit "You noted" bullet, or collect them under their own heading.
        val yourTake = mutableListOf<SummaryBullet>()
        for (anchor in judgments) {
            if (isRepresented(summary, sections, anchor)) continue
            val bullet = SummaryBullet("You noted: ${anchor.text}", Provenance.FRAGMENT)
            val target = bestSection(sections, anchor)
            if (target != null) {
                sections[target] = sections[target].copy(bullets = sections[target].bullets + bullet)
            } else {
                yourTake += bullet
            }
        }
        appendTo(sections, YOUR_TAKE, yourTake)
        appendTo(sections, OPEN_QUESTIONS, openQuestions)
        return summary.copy(sections = sections)
    }

    /** True when [anchor] is represented anywhere in [summary] (sections, highlights, actions). */
    fun isCovered(summary: StructuredSummary, anchor: Anchor): Boolean =
        isRepresented(summary, summary.sections, anchor)

    // ── matching ─────────────────────────────────────────────────────────────

    private fun isRepresented(summary: StructuredSummary, sections: List<SummarySection>, anchor: Anchor): Boolean {
        if (anchor.kind != Kind.JUDGMENT) {
            return coveringSection(sections, anchor) != null || isCoveredElsewhere(summary, anchor)
        }
        // An opinion is only represented when it is *attributed*: a bullet that merely shares the
        // topic ("Logo churn is 4%") states the fact, not that the user doubts it.
        val words = anchorWords(anchor)
        if (words.isEmpty()) return false
        val texts = sections.flatMap { s -> s.bullets.flatMap { listOf(it.text) + it.details } } +
            summary.highlights.map { it.text } + summary.actionItems.map { it.text }
        return texts.any { isAttributed(it) && QuoteMatch.overlapScore(words, contentTokens(it)) >= REPRESENTED_MIN }
    }

    private fun isAttributed(text: String): Boolean =
        NoteAnchors.isJudgment(text) || ATTRIBUTION.containsMatchIn(text.lowercase())

    /** Index of the first section whose heading (preferred) or bullet covers [anchor], else null. */
    private fun coveringSection(sections: List<SummarySection>, anchor: Anchor): Int? {
        val words = anchorWords(anchor)
        val lower = anchor.text.lowercase()
        fun matches(text: String): Boolean =
            if (words.isEmpty()) lower.trimEnd('?') in text.lowercase()
            else QuoteMatch.overlapScore(words, contentTokens(text)) >= REPRESENTED_MIN
        sections.indexOfFirst { matches(it.heading) }.takeIf { it >= 0 }?.let { return it }
        return sections.indexOfFirst { s -> s.bullets.any { b -> matches(b.text) || b.details.any(::matches) } }
            .takeIf { it >= 0 }
    }

    private fun isCoveredElsewhere(summary: StructuredSummary, anchor: Anchor): Boolean {
        val words = anchorWords(anchor)
        val lower = anchor.text.lowercase()
        fun matches(text: String): Boolean =
            if (words.isEmpty()) lower.trimEnd('?') in text.lowercase()
            else QuoteMatch.overlapScore(words, contentTokens(text)) >= REPRESENTED_MIN
        return summary.highlights.any { matches(it.text) } || summary.actionItems.any { matches(it.text) }
    }

    private fun bestSection(sections: List<SummarySection>, anchor: Anchor): Int? {
        val words = anchorWords(anchor)
        if (words.isEmpty()) return null
        val scored = sections.mapIndexed { i, s ->
            val target = contentTokens(s.heading + " " + s.bullets.joinToString(" ") { it.text })
            i to QuoteMatch.overlapScore(words, target)
        }
        return scored.maxByOrNull { it.second }?.takeIf { it.second >= ATTACH_MIN }?.first
    }

    /** The anchor's content words; for opinions the cue words are dropped so the *topic* matches. */
    private fun anchorWords(anchor: Anchor): Set<String> {
        val all = contentTokens(anchor.text)
        if (anchor.kind != Kind.JUDGMENT) return all
        return (all - NoteAnchors.cueWords()).ifEmpty { emptySet() }
    }

    /** Best transcript sentences for [anchor], as timestamped TRANSCRIPT bullets in transcript order. */
    private fun supportFor(anchor: Anchor, units: List<Pair<TranscriptLine, Set<String>>>): List<SummaryBullet> {
        val words = contentTokens(anchor.text)
        if (words.isEmpty()) return emptyList()
        val candidates = units.indices
            .filter { QuoteMatch.overlapScore(words, units[it].second) >= SUPPORT_MIN }
            .toMutableList()
        val picked = mutableListOf<Int>()
        while (picked.size < MAX_SUPPORT && candidates.isNotEmpty()) {
            val best = QuoteMatch.bestOf(words, candidates.map { it to units[it].second }) ?: break
            picked += best
            candidates.remove(best)
        }
        return picked.sorted().map {
            val line = units[it].first
            SummaryBullet(line.text, Provenance.TRANSCRIPT, timestampLabel = line.label.ifBlank { null })
        }
    }

    private fun headingFor(anchor: Anchor): String = when (anchor.kind) {
        Kind.QUESTION -> anchor.text.trimEnd('?', ' ')
        else -> anchor.text.replaceFirstChar { it.uppercase() }
    }

    private fun appendTo(sections: MutableList<SummarySection>, heading: String, bullets: List<SummaryBullet>) {
        if (bullets.isEmpty()) return
        val i = sections.indexOfFirst { it.heading.trim().equals(heading, ignoreCase = true) }
        if (i >= 0) sections[i] = sections[i].copy(bullets = sections[i].bullets + bullets)
        else sections += SummarySection(heading, bullets)
    }

    private fun contentTokens(text: String): Set<String> = QuoteMatch.tokenize(text) - STOP_WORDS

    private val ATTRIBUTION = Regex("\\byou\\b|\\byour\\b|flagged|skeptic|sceptic|doubt|worr|unsure")

    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "was", "were", "with", "that", "this", "from", "have", "has",
        "had", "but", "not", "you", "your", "our", "its", "any", "can", "will", "about", "into",
        "than", "then", "them", "they", "their", "what", "when", "who", "how", "why", "which",
    )
}
