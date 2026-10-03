package com.trailmix.app.ui.capture

/**
 * C2: the typed-note shortcut chips, as pure text edits so they can be unit tested.
 *
 * The syntax is exactly what the merge already understands (see `NoteAnchors`): a line starting
 * with `#` is a topic, a line ending with `?` is an open question. There is deliberately no
 * "Action:" shortcut. The merge does not treat that prefix specially, and changing how it reads
 * typed notes is a prompt/AI change, which this pass does not make.
 */
object NoteShortcuts {
    data class Edit(val text: String, val cursor: Int)

    private const val HEADING_PREFIX = "# "

    /** Toggles `# ` at the start of the line holding [cursor]. */
    fun heading(text: String, cursor: Int): Edit {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        return if (text.startsWith(HEADING_PREFIX, lineStart)) {
            Edit(text.removeRange(lineStart, lineStart + HEADING_PREFIX.length), (at - HEADING_PREFIX.length).coerceAtLeast(lineStart))
        } else {
            Edit(text.substring(0, lineStart) + HEADING_PREFIX + text.substring(lineStart), at + HEADING_PREFIX.length)
        }
    }

    /** Ends the line holding [cursor] with `?`. A blank line, or one that already ends in `?`, is left alone. */
    fun question(text: String, cursor: Int): Edit {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val newline = text.indexOf('\n', at)
        val lineEnd = if (newline < 0) text.length else newline
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || trimmed == "#" || trimmed.endsWith("?")) return Edit(text, at)
        val insertAt = lineStart + trimmed.length
        return Edit(text.substring(0, insertAt) + "?" + text.substring(insertAt), insertAt + 1)
    }
}
