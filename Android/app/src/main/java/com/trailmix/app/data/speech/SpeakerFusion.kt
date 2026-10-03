package com.trailmix.app.data.speech

import com.trailmix.app.data.ai.TranscriptLabels
import com.trailmix.app.data.model.TranscriptLine

/**
 * SPK-03: puts real names on the anonymous speakers ("Speaker 2", "Them") by fusing the
 * evidence that needs no voiceprint: the calendar roster, what people say ([NameCues]), and
 * the Me/Them lane. Pure and Android-free; every decision is a unit-tested rule.
 *
 * Precision beats recall. A wrong name on an action item is worse than "Speaker 2", so a name
 * is shown plainly only when it clears [CONFIDENT_SCORE]; a weaker, still-unopposed match is
 * shown as "Speaker 2 (Priya?)" and anything less stays anonymous. The note-taker's own
 * cluster is never renamed, and no name is given to two speakers.
 *
 * It works on the labels the lines already carry ([TranscriptLabels.speakerOf]), so it needs
 * diarization for "Speaker N" clusters but also works from the lane alone ("Them"). Lines that
 * already carry a real name are left as they are, which makes it safe to run twice.
 */
object SpeakerFusion {
    const val CONFIDENT_SCORE = 3.0
    const val SUGGESTED_SCORE = 1.5

    /** The score margin the best name needs over the runner-up for the same speaker. */
    private const val MARGIN = 1.5

    /** How far from a cue's line we look for the neighbouring speaker it points at. */
    private const val NEIGHBOUR_LINES = 3

    /** Whether a two-person meeting with a known note-taker names the other speaker outright. */
    val ONE_ON_ONE_SCORE = CONFIDENT_SCORE

    private const val ME = "Me"
    private const val THEM = "Them"
    private val ANONYMOUS = Regex("""Speaker \d+""")

    data class Assignment(val label: String, val name: String, val score: Double) {
        val confident: Boolean get() = score >= CONFIDENT_SCORE

        /** How the speaker reads in the transcript. */
        val display: String get() = if (confident) name else "$label ($name?)"
    }

    fun apply(
        lines: List<TranscriptLine>,
        rosterEntries: List<String>,
        userName: String?,
        voice: Map<String, VoiceMatch> = emptyMap(),
    ): List<TranscriptLine> {
        val assignments = assign(lines, rosterEntries, userName, voice)
        if (assignments.isEmpty()) return lines
        return lines.map { line ->
            val label = TranscriptLabels.speakerOf(line)
            val assignment = assignments[label] ?: return@map line
            line.copy(speakerLabel = assignment.display)
        }
    }

    /**
     * [voice] holds voiceprint matches (SPK-04) keyed by the speaker label they were measured
     * for ("Speaker 2"); a voice match for a name on the roster adds to that name's score, and
     * one for somebody not on the roster (a guest the user enrolled earlier) names the speaker
     * on its own strength.
     */
    fun assign(
        lines: List<TranscriptLine>,
        rosterEntries: List<String>,
        userName: String?,
        voice: Map<String, VoiceMatch> = emptyMap(),
    ): Map<String, Assignment> {
        val roster = Roster.parse(rosterEntries, userName)
        if (roster.isEmpty && voice.isEmpty()) return emptyMap()
        val labels = lines.map { TranscriptLabels.speakerOf(it) }
        val clusters = labels.filterNotNull().filter { it != ME && (it == THEM || ANONYMOUS.matches(it)) }.distinct()
        if (clusters.isEmpty()) return emptyMap()

        // Evidence per cluster per person.
        val score = HashMap<String, HashMap<Int, Double>>()
        fun vote(label: String?, person: Int, weight: Double) {
            if (label == null || label !in clusters) return
            val byPerson = score.getOrPut(label) { HashMap() }
            byPerson[person] = (byPerson[person] ?: 0.0) + weight
        }
        for (cue in NameCues.extract(lines, roster)) {
            val target = when (cue.kind) {
                NameCues.Kind.SELF_INTRO -> labels[cue.lineIndex]
                NameCues.Kind.ADDRESS_NEXT -> neighbour(labels, cue.lineIndex, +1)
                NameCues.Kind.THANKED_PREVIOUS -> neighbour(labels, cue.lineIndex, -1)
            }
            vote(target, cue.personIndex, cue.kind.weight)
        }
        // A voice is not "Them" (the whole far end) and never the note-taker's own cluster.
        val offRoster = HashMap<String, VoiceMatch>()
        for ((label, match) in voice) {
            if (label !in clusters || label == THEM || match.isMe) continue
            val person = roster.indexOfName(match.name)
            when {
                person == null -> offRoster[label] = match
                person != roster.meIndex -> vote(label, person, match.tierScore)
            }
        }

        // "Them" is the whole far end: one name fits only when there is exactly one other person.
        val pool = roster.people.indices.filter { it != roster.meIndex }
        val result = LinkedHashMap<String, Assignment>()
        val taken = HashSet<Int>()

        val candidates = clusters.flatMap { label -> score[label].orEmpty().map { Triple(label, it.key, it.value) } }
            .sortedByDescending { it.third }
        for ((label, person, value) in candidates) {
            if (value < SUGGESTED_SCORE || label in result || person in taken) continue
            if (label == THEM && pool.size != 1) continue
            val runnerUp = score[label].orEmpty().filterKeys { it != person }.values.maxOrNull() ?: 0.0
            if (value - runnerUp < MARGIN) continue
            result[label] = Assignment(label, roster.people[person].display, value)
            taken += person
        }

        // The note-taker recognised by voice in a capture the lane could not label (speakerphone).
        if (!labels.contains(ME)) {
            for ((label, match) in voice) {
                if (match.isMe && label in clusters && label != THEM && label !in result) {
                    result[label] = Assignment(label, ME, match.tierScore)
                    break
                }
            }
        }

        // An enrolled guest who is not on the calendar: the voice alone has to carry the name.
        for ((label, match) in offRoster) {
            if (label in result || result.values.any { it.name.equals(match.name, ignoreCase = true) }) continue
            result[label] = Assignment(label, match.name, match.tierScore)
        }

        // A two-person meeting with a known note-taker: whoever is left is the other person.
        val me = roster.meIndex
        if (me != null && labels.contains(ME)) {
            val open = clusters.filter { it !in result }
            val left = pool.filter { it !in taken }
            if (open.size == 1 && left.size == 1 && roster.people.size == 2) {
                result[open.single()] = Assignment(open.single(), roster.people[left.single()].display, ONE_ON_ONE_SCORE)
            }
        }
        return result
    }

    /** The label of the nearest line in [direction] that a different speaker owns. */
    private fun neighbour(labels: List<String?>, from: Int, direction: Int): String? {
        val own = labels[from]
        for (step in 1..NEIGHBOUR_LINES) {
            val i = from + direction * step
            if (i !in labels.indices) return null
            val other = labels[i] ?: continue
            if (other != own) return other
        }
        return null
    }
}
