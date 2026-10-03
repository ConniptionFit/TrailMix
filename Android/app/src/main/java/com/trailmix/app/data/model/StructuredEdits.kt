package com.trailmix.app.data.model

/**
 * UX redesign (N5, D1/D2): pure edits to a [StructuredSummary], so hand-editing a note keeps its
 * structure and provenance instead of flattening it to one text blob. Every function returns a
 * copy and treats an out-of-range index as a no-op. A change counts as an edit (and sets the
 * `edited` flag that drives "edited by you" and the rebuild warning) only when the text really
 * differs after trimming.
 */
object StructuredEdits {
    const val HIGHLIGHTS_HEADING = "Highlights"

    /**
     * Legacy HIGHLIGHTS render as the first section, titled "Highlights" (D10). Folding them in
     * is idempotent, and gives the editor one list to work with.
     */
    fun asSections(summary: StructuredSummary): StructuredSummary {
        if (summary.highlights.isEmpty()) return summary
        val highlights = SummarySection(HIGHLIGHTS_HEADING, summary.highlights)
        return summary.copy(highlights = emptyList(), sections = listOf(highlights) + summary.sections)
    }

    /** How many things the user changed by hand: the figure in "This replaces your N edits". */
    fun editCount(summary: StructuredSummary): Int =
        summary.highlights.count { it.edited } +
            summary.sections.sumOf { s -> (if (s.edited) 1 else 0) + s.bullets.count { it.edited } } +
            summary.actionItems.count { it.edited }

    fun renameSection(summary: StructuredSummary, section: Int, heading: String): StructuredSummary =
        updateSection(summary, section) { s ->
            val text = heading.trim()
            if (text == s.heading) s else s.copy(heading = text, edited = true)
        }

    fun editBullet(summary: StructuredSummary, section: Int, bullet: Int, text: String): StructuredSummary =
        updateSection(summary, section) { s ->
            if (bullet !in s.bullets.indices) return@updateSection s
            val old = s.bullets[bullet]
            val new = text.trim()
            if (new == old.text) return@updateSection s
            // The model's supporting details and quote described the old wording, so they go.
            val changed = old.copy(text = new, edited = true, details = emptyList())
            s.copy(bullets = s.bullets.toMutableList().also { it[bullet] = changed })
        }

    /** A new point is the user's own words, so it is a typed (amber) point. */
    fun addBullet(summary: StructuredSummary, section: Int, text: String = ""): StructuredSummary =
        updateSection(summary, section) { s ->
            s.copy(bullets = s.bullets + SummaryBullet(text = text.trim(), source = Provenance.FRAGMENT))
        }

    fun removeBullet(summary: StructuredSummary, section: Int, bullet: Int): StructuredSummary =
        updateSection(summary, section) { s ->
            if (bullet !in s.bullets.indices) s else s.copy(bullets = s.bullets.filterIndexed { i, _ -> i != bullet })
        }

    fun moveBullet(summary: StructuredSummary, section: Int, from: Int, to: Int): StructuredSummary =
        updateSection(summary, section) { s ->
            if (from == to || from !in s.bullets.indices || to !in s.bullets.indices) return@updateSection s
            val list = s.bullets.toMutableList()
            list.add(to, list.removeAt(from))
            s.copy(bullets = list)
        }

    fun addSection(summary: StructuredSummary, heading: String = ""): StructuredSummary =
        summary.copy(sections = summary.sections + SummarySection(heading.trim(), emptyList()))

    fun removeSection(summary: StructuredSummary, section: Int): StructuredSummary =
        if (section !in summary.sections.indices) {
            summary
        } else {
            summary.copy(sections = summary.sections.filterIndexed { i, _ -> i != section })
        }

    fun toggleDone(summary: StructuredSummary, index: Int): StructuredSummary =
        updateAction(summary, index) { it.copy(done = !it.done) }

    fun editAction(
        summary: StructuredSummary,
        index: Int,
        text: String,
        owner: String?,
        deadline: String?,
    ): StructuredSummary = updateAction(summary, index) { a ->
        val newText = text.trim()
        val newOwner = owner?.trim()?.takeIf { it.isNotEmpty() }
        val newDeadline = deadline?.trim()?.takeIf { it.isNotEmpty() }
        if (newText == a.text && newOwner == a.owner && newDeadline == a.deadline) {
            a
        } else {
            a.copy(text = newText, owner = newOwner, deadline = newDeadline, edited = true)
        }
    }

    fun addAction(summary: StructuredSummary): StructuredSummary =
        summary.copy(actionItems = summary.actionItems + ActionItem(text = "", source = Provenance.FRAGMENT))

    fun removeAction(summary: StructuredSummary, index: Int): StructuredSummary =
        if (index !in summary.actionItems.indices) {
            summary
        } else {
            summary.copy(actionItems = summary.actionItems.filterIndexed { i, _ -> i != index })
        }

    /**
     * What gets saved: blank points and steps (the user tapped "Add" and typed nothing) are
     * dropped, and so is a section with no heading and no points. A section that has a heading
     * but no points is kept, since the user may have meant it as a place to add to later.
     */
    fun cleaned(summary: StructuredSummary): StructuredSummary {
        val sections = summary.sections
            .map { s -> s.copy(bullets = s.bullets.filter { it.text.isNotBlank() }) }
            .filterNot { it.heading.isBlank() && it.bullets.isEmpty() }
        return summary.copy(
            highlights = summary.highlights.filter { it.text.isNotBlank() },
            sections = sections,
            actionItems = summary.actionItems.filter { it.text.isNotBlank() },
        )
    }

    private fun updateSection(
        summary: StructuredSummary,
        index: Int,
        change: (SummarySection) -> SummarySection,
    ): StructuredSummary {
        if (index !in summary.sections.indices) return summary
        val list = summary.sections.toMutableList()
        list[index] = change(list[index])
        return summary.copy(sections = list)
    }

    private fun updateAction(
        summary: StructuredSummary,
        index: Int,
        change: (ActionItem) -> ActionItem,
    ): StructuredSummary {
        if (index !in summary.actionItems.indices) return summary
        val list = summary.actionItems.toMutableList()
        list[index] = change(list[index])
        return summary.copy(actionItems = list)
    }
}
