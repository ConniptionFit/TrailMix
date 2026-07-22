package com.trailmix.app.data.ai

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection

/**
 * Deterministic structurer (v1.9.0): turns raw typed notes + transcript into a sectioned
 * [StructuredSummary] — Key Topics with bullets plus a separate Action Items list — using
 * zero AI. It is the safety net behind the on-device model: whenever structuring is
 * unavailable (no Gemini Nano) or the small model returns non-JSON, the note still renders
 * as readable sections instead of one flat wall of text, which was the previous fallback.
 *
 * Fully offline and rule-based (no network, no model). Grouping is by provenance — the
 * user's own typed notes vs. what was said on the call — plus a cue-based pass that lifts
 * tasks and commitments into their own Action Items list.
 */
object DeterministicSummary {

    /** Lowercased substrings that mark a sentence as a task/commitment → Action Items. */
    private val ACTION_CUES = listOf(
        "action item", "todo", "to-do", "to do", "follow up", "follow-up", "next step",
        "need to", "needs to", "have to", "i'll ", "we'll ", "let's ", "should ",
        "make sure", "don't forget", "circle back", "take care of", "assign",
        "responsible for", "by end of", "deadline", "due ",
        "send ", "email ", "schedule ", "set up ",
    )

    private const val MAX_SECTION_BULLETS = 25
    private const val MAX_ACTION_ITEMS = 15
    private const val MIN_SENTENCES = 3

    /**
     * Build a deterministic summary, or return null when there is too little content to
     * benefit (a one- or two-sentence note reads fine flat and is not the "massive chunk"
     * this exists to break up). A null result leaves the note on its flat body, unchanged.
     */
    fun from(typedFragments: String, transcriptText: String): StructuredSummary? {
        val fragmentSentences = splitSentences(typedFragments)
        val transcriptSentences = splitSentences(transcriptText)
        if (fragmentSentences.size + transcriptSentences.size < MIN_SENTENCES) return null

        val sections = buildList {
            val yourNotes = fragmentSentences.filterNot(::isAction)
                .take(MAX_SECTION_BULLETS)
                .map { SummaryBullet(it, Provenance.FRAGMENT) }
            if (yourNotes.isNotEmpty()) add(SummarySection("Your notes", yourNotes))

            val keyTopics = transcriptSentences.filterNot(::isAction)
                .take(MAX_SECTION_BULLETS)
                .map { SummaryBullet(it, Provenance.TRANSCRIPT) }
            if (keyTopics.isNotEmpty()) add(SummarySection("Key topics", keyTopics))
        }

        val actionItems = (
            fragmentSentences.filter(::isAction).map { it to Provenance.FRAGMENT } +
                transcriptSentences.filter(::isAction).map { it to Provenance.TRANSCRIPT }
            )
            .distinctBy { it.first.lowercase() }
            .take(MAX_ACTION_ITEMS)
            .map { (text, source) -> ActionItem(text = text, source = source) }

        if (sections.isEmpty() && actionItems.isEmpty()) return null
        return StructuredSummary(highlights = emptyList(), sections = sections, actionItems = actionItems)
    }

    private fun isAction(sentence: String): Boolean {
        val lower = sentence.lowercase()
        return ACTION_CUES.any { it in lower }
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
}
