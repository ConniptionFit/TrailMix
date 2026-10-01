package com.trailmix.app.data.model

/**
 * AI-19: one section of a section-based summary template — a heading plus an optional one-line
 * instruction saying what belongs under it (Granola's "structure via sections").
 */
data class SectionSpec(val heading: String, val instruction: String = "")

/**
 * AI-19: the whole shape a template asks of a summary — [meetingContext] (purpose / style, 1-3
 * compact sentences: Gemini Nano's context is small) plus the required [sections], in order.
 * "Next Steps" is never a section here: the renderer always appends the action-items list.
 *
 * A spec with no [sections] is a *context-only* template and behaves exactly as templates did
 * before AI-19: the context is spliced into the prompt and the model chooses its own headings.
 * With [omitEmptySections] a template section with nothing under it is dropped; without it,
 * the section is kept and reads "Not discussed".
 *
 * Pure and Android-free.
 */
data class TemplateSpec(
    val meetingContext: String,
    val sections: List<SectionSpec> = emptyList(),
    val omitEmptySections: Boolean = true,
) {
    val hasSections: Boolean get() = sections.isNotEmpty()

    /** Read-only description for Settings' viewer: the context, then the numbered sections. */
    fun describe(): String = buildString {
        append(meetingContext)
        if (sections.isNotEmpty()) {
            append("\n\nSections:")
            sections.forEachIndexed { i, s ->
                append("\n${i + 1}. ${s.heading}")
                if (s.instruction.isNotBlank()) append(" — ${s.instruction}")
            }
        }
    }
}
