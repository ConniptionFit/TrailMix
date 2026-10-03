package com.trailmix.app.ui.transcript

import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptLine

/** T1: which lines the list shows. */
enum class TranscriptFilter { ALL, FLAGGED, ME, THEM }

/** One row of the flattened transcript list: a speaker's name, or one line under it. */
sealed class TranscriptItem {
    /** [firstIndex] is the index of the first line in the block, which keeps the row key stable. */
    data class Header(val speaker: String, val firstIndex: Int) : TranscriptItem()
    data class Line(val index: Int, val line: TranscriptLine, val flagged: Boolean) : TranscriptItem()
}

/** The name shown above a line: a diarized speaker wins over the Me/Them lane (CAP-31). */
fun speakerName(line: TranscriptLine): String? = line.speakerLabel ?: line.speechSource?.displayName

/** Indices of the lines [filter] keeps, in transcript order. [flagged] holds line indices. */
fun visibleIndices(lines: List<TranscriptLine>, filter: TranscriptFilter, flagged: Set<Int>): List<Int> =
    lines.indices.filter { index ->
        when (filter) {
            TranscriptFilter.ALL -> true
            TranscriptFilter.FLAGGED -> index in flagged
            TranscriptFilter.ME -> lines[index].speechSource == SpeechSource.ME
            TranscriptFilter.THEM -> lines[index].speechSource == SpeechSource.THEM
        }
    }

/**
 * T1: consecutive lines by one speaker collapse into a block under a single name, like a
 * script. A line with no speaker at all (a mic-only note with no labels) gets no header, so a
 * plain transcript is just lines. A speaker change, or a gap left by a filter, starts a new block.
 */
fun buildTranscriptItems(
    lines: List<TranscriptLine>,
    indices: List<Int>,
    flagged: Set<Int>,
): List<TranscriptItem> {
    val items = ArrayList<TranscriptItem>(indices.size + indices.size / 3)
    var currentSpeaker: String? = null
    var previousIndex = -2
    indices.forEach { index ->
        val line = lines[index]
        val speaker = speakerName(line)
        val continues = speaker == currentSpeaker && index == previousIndex + 1
        if (speaker != null && !continues) items += TranscriptItem.Header(speaker, index)
        items += TranscriptItem.Line(index, line, index in flagged)
        currentSpeaker = speaker
        previousIndex = index
    }
    return items
}

/** Line indices whose text contains [query] (case-insensitive), in order. Blank query matches nothing. */
fun searchHits(lines: List<TranscriptLine>, query: String): List<Int> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return lines.indices.filter { lines[it].text.contains(needle, ignoreCase = true) }
}

/** Ranges of [text] that match [query], for tinting. Empty when the query is blank. */
fun matchRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        ranges += at until at + needle.length
        from = at + needle.length
    }
    return ranges
}
