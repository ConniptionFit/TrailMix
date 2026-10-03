package com.trailmix.app.ui.chat

import com.trailmix.app.data.model.StructuredEdits
import com.trailmix.app.data.model.StructuredSummary

/**
 * K3: "Add to note" keeps a chat answer by appending it to the note's structured summary as a
 * section of the user's own points. The user chose to keep it, so the points are typed (amber),
 * not spoken (teal): the transcript never said them.
 */
object ChatToNote {
    const val DEFAULT_HEADING = "From chat"

    /** The reply's content lines, markers stripped; headings are dropped (the section has one). */
    fun points(reply: String): List<String> = ChatMarkdown.parse(reply)
        .filterNot { it is ChatBlock.Heading }
        .map { block -> ChatMarkdown.spans(block.text).joinToString("") { it.text }.trim() }
        .filter { it.isNotEmpty() }

    fun append(summary: StructuredSummary, reply: String, heading: String = DEFAULT_HEADING): StructuredSummary {
        val points = points(reply)
        if (points.isEmpty()) return summary
        val withSection = StructuredEdits.addSection(StructuredEdits.asSections(summary), heading)
        val index = withSection.sections.lastIndex
        return points.fold(withSection) { acc, point -> StructuredEdits.addBullet(acc, index, point) }
    }
}
