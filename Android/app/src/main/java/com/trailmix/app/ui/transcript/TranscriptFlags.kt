package com.trailmix.app.ui.transcript

import com.trailmix.app.data.ai.TranscriptCoverage
import com.trailmix.app.data.model.TranscriptLine

/**
 * CAP-24: which transcript line a flagged moment belongs next to. A flag is just a timestamp,
 * not a line reference (the user tapped a button, not a specific sentence), so it's placed at
 * the last line at or before the flag's own `mm:ss` — the line that was being said when the tap
 * happened — falling back to the first line for a flag that landed before any transcript line
 * (a typed-only start, or the recognizer still warming up). Pure and Android-free, like
 * [com.trailmix.app.ui.home.HomeDayGrouping]'s grouping function, so the matching logic is
 * unit-tested independent of Compose.
 */
fun flagLineIndex(lines: List<TranscriptLine>, flagLabel: String): Int? {
    if (lines.isEmpty()) return null
    val flagSeconds = TranscriptCoverage.parseLabelSeconds(flagLabel) ?: return null
    var best: Int? = null
    for ((index, line) in lines.withIndex()) {
        val lineSeconds = TranscriptCoverage.parseLabelSeconds(line.label) ?: continue
        if (lineSeconds <= flagSeconds) best = index else break
    }
    return best ?: 0
}
