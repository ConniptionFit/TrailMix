package com.trailmix.app.data.ai

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.SummarySection
import java.util.Locale

/**
 * AI-28: structuring a long session in parts that each fit the model's reply cap.
 *
 * A reply is capped at 256 tokens (about 900 characters of the structured JSON, see
 * [StructuredJson]), so one structuring call can only cover a few minutes of dense speech. Before
 * this, a 13-minute session produced a note whose sections came from its first ~1.5 minutes and
 * silently dropped the rest, defeating AI-05's whole-session coverage. The processor now splits the
 * text it would have structured into parts of at most [PART_CHARS], structures each, and merges
 * the results with these pure helpers (which have unit tests; the model calls do not).
 */
object StructuredParts {

    /**
     * Input per structuring call. The reply is roughly as long as its input is dense, and 256
     * tokens fit about 8 bullets plus headings, so a part is sized to produce no more than that.
     */
    const val PART_CHARS = 900

    /**
     * Split [text] into parts of at most [maxChars], on line boundaries. A single line longer than
     * [maxChars] stays whole (cutting it would lose its timestamp and its sentence). Always at
     * least one part, so a blank transcript still produces one (empty) call's worth of work.
     */
    fun split(text: String, maxChars: Int = PART_CHARS): List<String> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + 1 + line.length > maxChars) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /**
     * One section per heading, in order of first appearance, bullets concatenated in order.
     * Headings match ignoring case and punctuation, so "Pricing" from one part and "pricing."
     * from the next become one section. [NoteShape] then dedupes bullets and caps the topic count.
     */
    fun mergeSections(sections: List<SummarySection>): List<SummarySection> {
        val merged = LinkedHashMap<String, SummarySection>()
        for (section in sections) {
            val key = normalize(section.heading)
            val existing = merged[key]
            merged[key] = if (existing == null) section else existing.copy(bullets = existing.bullets + section.bullets)
        }
        return merged.values.toList()
    }

    /** Action items with the same text (ignoring case and punctuation) once, the first occurrence kept. */
    fun mergeActions(actions: List<ActionItem>): List<ActionItem> {
        val seen = HashSet<String>()
        return actions.filter { seen.add(normalize(it.text)) }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
}
