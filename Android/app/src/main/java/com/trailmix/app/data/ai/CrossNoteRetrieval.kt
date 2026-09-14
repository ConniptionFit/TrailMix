package com.trailmix.app.data.ai

import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.model.TranscriptLine

/**
 * Cross-note chat (AI-10) context assembly. [OnDeviceAiProcessor.chat]'s single-note path does
 * a naive `.take(MAX_CONTEXT_CHARS)` prefix-truncation, which is already lossy for one long
 * note — for several notes at once it would silently favor whichever note happens to sort
 * first and drop the rest. This ranks every candidate note's transcript lines by relevance to
 * the question (reusing [QuoteMatch]'s word-overlap scoring) and assembles a char-budgeted
 * context from the best matches across ALL notes, so a question about the third note doesn't
 * lose to the first note's transcript just because it's longer.
 */
object CrossNoteRetrieval {

    data class RankedLine(val note: NoteEntity, val line: TranscriptLine, val score: Double)

    /**
     * Every non-blank transcript line across [notes] that shares at least one word with
     * [question], best match first. Empty when [question] has no meaningful tokens (all
     * stopword-length) or nothing matches at all — callers should treat that as "found nothing",
     * not "notes have no content".
     */
    fun rank(question: String, notes: List<NoteEntity>): List<RankedLine> {
        val questionWords = QuoteMatch.tokenize(question)
        if (questionWords.isEmpty()) return emptyList()
        return notes.flatMap { note ->
            note.transcript.filter { it.text.isNotBlank() }.map { line ->
                RankedLine(note, line, QuoteMatch.overlapScore(questionWords, QuoteMatch.tokenize(line.text)))
            }
        }.filter { it.score > 0.0 }.sortedByDescending { it.score }
    }

    /**
     * Assembles the top-ranked lines into one prompt-ready string, grouped by note (each under
     * a `### <title>` heading, lines restored to chronological order within the note) and
     * capped at [maxChars] total — the same char-budget discipline [TranscriptCoverage] uses,
     * just relevance-ranked here instead of evenly sampled. Always admits at least one line
     * (even if it alone exceeds [maxChars]) so a very tight budget can't return nothing.
     */
    fun buildContext(question: String, notes: List<NoteEntity>, maxChars: Int): String {
        val byNote = linkedMapOf<Long, MutableList<RankedLine>>()
        var used = 0
        for (candidate in rank(question, notes)) {
            val cost = candidate.line.text.length + candidate.note.title.length + 8
            if (used > 0 && used + cost > maxChars) break
            byNote.getOrPut(candidate.note.id) { mutableListOf() }.add(candidate)
            used += cost
        }
        return byNote.values.joinToString("\n\n") { lines ->
            val note = lines.first().note
            val body = lines
                .sortedBy { TranscriptCoverage.parseLabelSeconds(it.line.label) ?: Int.MAX_VALUE }
                .joinToString("\n") { "[${it.line.label}] ${it.line.text}" }
            "### ${note.title}\n$body"
        }
    }
}
