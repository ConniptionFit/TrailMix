package com.trailmix.app.data.speech

import com.trailmix.app.data.ai.TranscriptCoverage
import com.trailmix.app.data.model.TranscriptLine

/** One speaker turn from a [SpeakerDiarizer] — a time range plus the backend's raw speaker id. */
data class SpeakerSegment(val startSeconds: Float, val endSeconds: Float, val speakerTag: Int)

/**
 * AI-01: maps a [SpeakerDiarizer]'s speaker segments (time ranges) onto the app's transcript
 * lines (single `mm:ss` points), and turns the backend's raw non-negative `speakerTag`
 * integers — which carry no guaranteed meaning beyond "these are the same speaker within this
 * one diarization pass" — into stable "Speaker 1"/"Speaker 2" labels ordered by first
 * appearance, since that's what a human reading the transcript actually finds legible. Backend
 * was Picovoice Falcon until the sherpa-onnx migration (2026-09-13); this file never changed.
 */
object SpeakerLabels {
    fun apply(lines: List<TranscriptLine>, segments: List<SpeakerSegment>): List<TranscriptLine> {
        if (segments.isEmpty()) return lines
        val displayNumberByTag = LinkedHashMap<Int, Int>()
        fun labelFor(tag: Int): String = "Speaker ${displayNumberByTag.getOrPut(tag) { displayNumberByTag.size + 1 }}"

        return lines.map { line ->
            val atSeconds = TranscriptCoverage.parseLabelSeconds(line.label)?.toFloat() ?: return@map line
            val segment = segments.firstOrNull { atSeconds >= it.startSeconds && atSeconds < it.endSeconds }
                ?: segments.minBy { kotlin.math.abs(it.startSeconds - atSeconds) }
            line.copy(speakerLabel = labelFor(segment.speakerTag))
        }
    }
}
