package com.trailmix.app.data.speech

import com.trailmix.app.data.ai.TranscriptCoverage
import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptLine
import kotlin.math.abs

/** One speaker turn from a [SpeakerDiarizer] — a time range plus the backend's raw speaker id. */
data class SpeakerSegment(val startSeconds: Float, val endSeconds: Float, val speakerTag: Int)

/**
 * AI-01: maps a [SpeakerDiarizer]'s speaker segments (time ranges) onto the app's transcript
 * lines, and turns the backend's raw non-negative `speakerTag` integers — which carry no
 * guaranteed meaning beyond "these are the same speaker within this one diarization pass" —
 * into stable "Speaker 1"/"Speaker 2" labels ordered by first appearance, since that's what a
 * human reading the transcript actually finds legible.
 *
 * SPK-01: three changes over the original single-timestamp mapping.
 *  - A line's label is stamped when its utterance *finalizes* (end of speech plus recognizer
 *    latency), so the point can sit just inside the next speaker's segment. A line is now
 *    matched over its estimated span (the stretch since the previous line, capped at
 *    [MAX_UTTERANCE_SECONDS]) and takes the speaker with the most overlap.
 *  - The cluster that the CAP-31 lane evidence says is the local user is named "Me", for every
 *    line in that cluster, and the remaining speakers are numbered from 1. Before this a
 *    diarization label simply hid the Me/Them split.
 *  - [shift] re-bases segments onto the transcript's timeline when audio retention started
 *    partway through it (a resumed or recovered session).
 */
object SpeakerLabels {
    const val ME_LABEL = "Me"

    /** Longest stretch of a gap attributed to one line; a long silence is not one utterance. */
    const val MAX_UTTERANCE_SECONDS = 12

    /** The share of a cluster's lane-labelled lines that must be Me for the cluster to be Me. */
    private const val ME_CLUSTER_SHARE = 0.6

    /** Fewest Me-lane lines that can name a cluster Me; one stray line proves nothing. */
    private const val ME_CLUSTER_MIN_LINES = 2

    fun apply(lines: List<TranscriptLine>, segments: List<SpeakerSegment>): List<TranscriptLine> =
        applyDetailed(lines, segments).lines

    /** [lines] labelled, plus which label each raw speaker tag ended up with. */
    class Labelled(val lines: List<TranscriptLine>, val labelByTag: Map<Int, String>)

    fun applyDetailed(lines: List<TranscriptLine>, segments: List<SpeakerSegment>): Labelled {
        if (segments.isEmpty()) return Labelled(lines, emptyMap())
        val tags = clusterTags(lines, segments)
        val meTag = meCluster(lines, tags)

        val displayNumberByTag = LinkedHashMap<Int, Int>()
        fun labelFor(tag: Int): String =
            if (tag == meTag) ME_LABEL else "Speaker ${displayNumberByTag.getOrPut(tag) { displayNumberByTag.size + 1 }}"

        val labelled = lines.mapIndexed { i, line ->
            val tag = tags[i] ?: return@mapIndexed line
            line.copy(speakerLabel = labelFor(tag))
        }
        return Labelled(labelled, tags.filterNotNull().distinct().associateWith { labelFor(it) })
    }

    /** Segments moved [offsetSeconds] later, for audio that started after the timeline's zero. */
    fun shift(segments: List<SpeakerSegment>, offsetSeconds: Float): List<SpeakerSegment> =
        if (offsetSeconds == 0f) {
            segments
        } else {
            segments.map { it.copy(startSeconds = it.startSeconds + offsetSeconds, endSeconds = it.endSeconds + offsetSeconds) }
        }

    /** The raw speaker tag for each line, or null where the line has no usable timestamp. */
    internal fun clusterTags(lines: List<TranscriptLine>, segments: List<SpeakerSegment>): List<Int?> {
        var previousSeconds: Float? = null
        return lines.map { line ->
            val end = TranscriptCoverage.parseLabelSeconds(line.label)?.toFloat()
            if (end == null) {
                null
            } else {
                val start = maxOf(previousSeconds ?: (end - MAX_UTTERANCE_SECONDS), end - MAX_UTTERANCE_SECONDS, 0f)
                previousSeconds = end
                tagForSpan(start, end, segments)
            }
        }
    }

    private fun tagForSpan(start: Float, end: Float, segments: List<SpeakerSegment>): Int {
        val overlap = HashMap<Int, Float>()
        for (s in segments) {
            val o = minOf(end, s.endSeconds) - maxOf(start, s.startSeconds)
            if (o > 0f) overlap[s.speakerTag] = (overlap[s.speakerTag] ?: 0f) + o
        }
        if (overlap.isNotEmpty()) {
            val best = overlap.values.max()
            val leaders = overlap.filterValues { it == best }.keys
            if (leaders.size == 1) return leaders.single()
            // A tie: the speaker who is talking at the line's end is the likelier owner.
            segments.firstOrNull { it.speakerTag in leaders && end >= it.startSeconds && end < it.endSeconds }
                ?.let { return it.speakerTag }
            return segments.first { it.speakerTag in leaders }.speakerTag
        }
        // Nobody overlaps the span (a gap in the diarization): the nearest segment wins.
        return segments.minBy { minOf(abs(it.startSeconds - end), abs(it.endSeconds - end)) }.speakerTag
    }

    /** The cluster the Me-lane lines overwhelmingly fall in, or null when the evidence is thin. */
    private fun meCluster(lines: List<TranscriptLine>, tags: List<Int?>): Int? {
        val meLines = HashMap<Int, Int>()
        val laneLines = HashMap<Int, Int>()
        lines.forEachIndexed { i, line ->
            val tag = tags[i] ?: return@forEachIndexed
            val source = line.speechSource ?: return@forEachIndexed
            laneLines[tag] = (laneLines[tag] ?: 0) + 1
            if (source == SpeechSource.ME) meLines[tag] = (meLines[tag] ?: 0) + 1
        }
        val candidate = meLines.maxByOrNull { it.value } ?: return null
        val share = candidate.value.toDouble() / (laneLines[candidate.key] ?: return null)
        // Another cluster must not be just as Me-heavy: two Me clusters means the lane evidence
        // and the diarization disagree, and a wrong "Me" is worse than "Speaker N".
        val runnerUp = meLines.filterKeys { it != candidate.key }.values.maxOrNull() ?: 0
        return candidate.key.takeIf {
            candidate.value >= ME_CLUSTER_MIN_LINES && share >= ME_CLUSTER_SHARE && runnerUp * 2 <= candidate.value
        }
    }
}
