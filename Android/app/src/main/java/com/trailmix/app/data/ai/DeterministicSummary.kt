package com.trailmix.app.data.ai

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.SummaryStyle
import com.trailmix.app.data.model.TranscriptLine

/**
 * Deterministic structurer (v1.9.0, reworked for long sessions in v1.10.0 / AI-05): turns raw
 * typed notes + transcript into a sectioned [StructuredSummary] using zero AI. It is the
 * safety net behind the on-device model — whenever structuring is unavailable (no Gemini
 * Nano) or the small model returns non-JSON, the note still renders as readable sections.
 *
 * What AI-05 changed, and why:
 *
 * 1. **Whole-session coverage.** v1.9.0 took the first 25 transcript sentences, which is
 *    roughly three minutes of speech. On a 45-minute conference talk the note described the
 *    speaker's introduction and silently dropped everything after it. The transcript is now
 *    divided into time windows ([TranscriptCoverage.windows]) and each window becomes its own
 *    section with its own bullet budget, so the end of a talk is represented as well as the
 *    start. Sessions too short to slice still produce the single "Key topics" section they
 *    always did — this only engages where the old behavior was actually losing content.
 *
 * 2. **Selection instead of truncation.** Within a window, bullets are chosen by greedy
 *    coverage of distinct content ([SummaryText.selectDistinct]) rather than by position, and
 *    near-duplicates and ASR disfluency are removed first.
 *
 * 3. **[SummaryStyle] awareness.** A presenter's instructional phrasing ("you should index
 *    that", "let's look at the next slide") is content, not a task. See [SummaryStyle].
 *
 * 4. **Timestamps.** Transcript bullets carry the `mm:ss` offset of their source line, which
 *    is what makes a claim in a long talk findable again in the raw transcript.
 *
 * Bullets here deliberately leave `sourceExcerpt` null: these bullets are the source sentence,
 * verbatim, so an excerpt would just repeat the bullet. The excerpt affordance exists for
 * AI-distilled bullets, where the bullet and its origin genuinely differ.
 */
object DeterministicSummary {

    /**
     * Build a deterministic summary, or return null when there is too little content to
     * benefit (a one- or two-sentence note reads fine flat and is not the "massive chunk"
     * this exists to break up). A null result leaves the note on its flat body, unchanged.
     */
    fun from(
        typedFragments: String,
        transcript: List<TranscriptLine>,
        style: SummaryStyle = SummaryStyle.DISCUSSION,
    ): StructuredSummary? {
        val fragmentSentences = SummaryText.dedupe(SummaryText.splitSentences(typedFragments))
        val windows = TranscriptCoverage.windows(transcript)
        val utterances = windows.map { window -> window to cleanedUtterances(window.lines, style) }
        val transcriptCount = utterances.sumOf { it.second.size }
        if (fragmentSentences.size + transcriptCount < MIN_SENTENCES) return null

        val isPresentation = style == SummaryStyle.PRESENTATION

        // ── Action items ────────────────────────────────────────────────────
        // Typed fragments are the user's own words, so their commitments count in both
        // styles. Spoken commitments only count in a discussion: in a talk, the imperative
        // voice belongs to the speaker teaching, not to the listener taking on work.
        val fragmentActions = fragmentSentences.filter { isAction(it, fragmentCues(style)) }
        val transcriptActions = utterances.flatMap { (_, lines) ->
            lines.filter { isAction(it.text, transcriptCues(style)) }
        }

        val actionItems = (
            fragmentActions.map { Triple(it, Provenance.FRAGMENT, null as String?) } +
                transcriptActions.map { Triple(it.text, Provenance.TRANSCRIPT, it.label.ifBlank { null }) }
            )
            .distinctBy { it.first.lowercase() }
            .take(MAX_ACTION_ITEMS)
            .map { (text, source, label) ->
                ActionItem(text = text, source = source, timestampLabel = label)
            }

        val actionTexts = actionItems.mapTo(mutableSetOf()) { it.text.lowercase() }

        // ── Sections ────────────────────────────────────────────────────────
        val sections = buildList {
            val yourNotes = fragmentSentences
                .filterNot { it.lowercase() in actionTexts }
                .take(MAX_SECTION_BULLETS)
                .map { SummaryBullet(it, Provenance.FRAGMENT) }
            if (yourNotes.isNotEmpty()) add(SummarySection("Your notes", yourNotes))

            val windowsWithContent = utterances.filter { (_, lines) ->
                lines.any { it.text.lowercase() !in actionTexts }
            }
            val budget = bulletBudget(windowsWithContent.size)
            // Terms that show up in nearly every window can't distinguish one from another,
            // so they're excluded from the generated headings.
            val generic = SummaryText.commonTerms(windowsWithContent.map { it.first.text })

            windowsWithContent.forEach { (window, lines) ->
                val candidates = lines.filterNot { it.text.lowercase() in actionTexts }
                val chosen = SummaryText.selectDistinct(candidates.map { it.text }, budget).toSet()
                val bullets = candidates
                    .filter { it.text in chosen }
                    .map {
                        SummaryBullet(
                            text = it.text,
                            source = Provenance.TRANSCRIPT,
                            timestampLabel = it.label.ifBlank { null },
                        )
                    }
                if (bullets.isNotEmpty()) {
                    add(SummarySection(headingFor(window, generic, single = windowsWithContent.size == 1), bullets))
                }
            }
        }

        if (sections.isEmpty() && actionItems.isEmpty()) return null
        return StructuredSummary(highlights = emptyList(), sections = sections, actionItems = actionItems)
    }

    /** Text-only convenience (and the pre-AI-05 signature): no timestamps, never windowed. */
    fun from(
        typedFragments: String,
        transcriptText: String,
        style: SummaryStyle = SummaryStyle.DISCUSSION,
    ): StructuredSummary? = from(
        typedFragments = typedFragments,
        transcript = if (transcriptText.isBlank()) emptyList() else listOf(TranscriptLine("", transcriptText)),
        style = style,
    )

    /**
     * Split each transcript line into sentences that keep their line's `mm:ss` label, clean
     * ASR disfluency, and drop near-duplicates. Low-content filtering (backchannel, stage
     * directions) applies only to [SummaryStyle.PRESENTATION]: in a short discussion a
     * two-word answer can be the whole point, whereas in a 45-minute talk it is noise.
     */
    private fun cleanedUtterances(lines: List<TranscriptLine>, style: SummaryStyle): List<TranscriptLine> {
        val expanded = lines.flatMap { line ->
            SummaryText.splitSentences(line.text)
                .map { SummaryText.stripFiller(it) }
                .filter { it.isNotBlank() }
                .filterNot { style == SummaryStyle.PRESENTATION && SummaryText.isLowContent(it) }
                .map { TranscriptLine(label = line.label, text = it) }
        }
        val kept = SummaryText.dedupe(expanded.map { it.text }).toSet()
        val seen = mutableSetOf<String>()
        return expanded.filter { it.text in kept && seen.add(it.text) }
    }

    /** One section per window, so the per-window budget shrinks as the session grows. */
    private fun bulletBudget(windowCount: Int): Int =
        if (windowCount <= 1) MAX_SECTION_BULLETS
        else (MAX_TRANSCRIPT_BULLETS / windowCount).coerceIn(MIN_WINDOW_BULLETS, MAX_SECTION_BULLETS)

    private fun headingFor(
        window: TranscriptCoverage.Window,
        generic: Set<String>,
        single: Boolean,
    ): String {
        if (single || window.rangeLabel.isEmpty()) return "Key topics"
        val terms = SummaryText.keywords(window.text, limit = KEYWORDS_PER_HEADING, exclude = generic)
        return if (terms.isEmpty()) window.rangeLabel else "${window.rangeLabel} · ${terms.joinToString(", ")}"
    }

    private fun isAction(sentence: String, cues: List<String>): Boolean {
        val lower = sentence.lowercase()
        return cues.any { it in lower }
    }

    private fun fragmentCues(style: SummaryStyle): List<String> =
        if (style == SummaryStyle.PRESENTATION) FIRST_PERSON_CUES else DISCUSSION_CUES

    private fun transcriptCues(style: SummaryStyle): List<String> =
        if (style == SummaryStyle.PRESENTATION) SPOKEN_ACTION_MARKERS else DISCUSSION_CUES

    /**
     * Lowercased substrings that mark a sentence as a task/commitment in a two-way
     * conversation, where imperative language really is someone taking on work.
     */
    private val DISCUSSION_CUES = listOf(
        "action item", "todo", "to-do", "to do", "follow up", "follow-up", "next step",
        "need to", "needs to", "have to", "i'll ", "we'll ", "let's ", "should ",
        "make sure", "don't forget", "circle back", "take care of", "assign",
        "responsible for", "by end of", "deadline", "due ",
        "send ", "email ", "schedule ", "set up ",
    )

    /**
     * Commitments the *listener* made in their own typed notes. Deliberately first-person:
     * during a talk the only person who can be taking on work in the note-taker's notes is
     * the note-taker.
     */
    private val FIRST_PERSON_CUES = listOf(
        "action item", "todo", "to-do", "i'll ", "i will ", "i need to", "i should ",
        "i have to", "my action", "note to self", "remind me", "follow up on", "follow-up on",
    )

    /**
     * The only spoken phrases that survive as action items during a presentation — an
     * explicitly announced task, not the speaker's ordinary instructional voice.
     */
    private val SPOKEN_ACTION_MARKERS = listOf(
        "action item", "to-do list", "your homework", "homework is", "your assignment",
        "assignment is", "take this back to", "one thing to do",
    )

    private const val MAX_SECTION_BULLETS = 25
    private const val MAX_TRANSCRIPT_BULLETS = 40
    private const val MIN_WINDOW_BULLETS = 4
    private const val MAX_ACTION_ITEMS = 15
    private const val MIN_SENTENCES = 3
    private const val KEYWORDS_PER_HEADING = 3
}
