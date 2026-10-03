package com.trailmix.app.data.speech

import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptLine

/**
 * SPK-04: decides whether a finished session can teach the app the note-taker's voice without
 * being asked. Pure.
 *
 * The evidence is the CAP-31 lane: lines the microphone clearly dominated while device audio
 * was attached are the person holding the phone. The "Me" cluster is only trusted as a
 * training sample when nearly all of its lane-labelled lines say so ([MIN_PURITY]) and there
 * are enough of them ([MIN_LINES]); a contaminated sample would teach the app a blend of two
 * voices and make every later match worse, so being strict here costs only a slower start.
 */
object MeEnrollment {
    const val MIN_LINES = 4
    const val MIN_PURITY = 0.8

    /** One session cannot outweigh a person's whole history by length alone. */
    const val MAX_WEIGHT = 20.0

    /** The sample weight to enroll the "Me" cluster with, or 0 when it is not trustworthy enough. */
    fun weight(lines: List<TranscriptLine>): Double {
        val meLines = lines.filter { it.speakerLabel == SpeakerLabels.ME_LABEL }
        if (meLines.size < MIN_LINES) return 0.0
        val laneLabelled = meLines.mapNotNull { it.speechSource }
        if (laneLabelled.size < MIN_LINES) return 0.0
        val purity = laneLabelled.count { it == SpeechSource.ME }.toDouble() / laneLabelled.size
        return if (purity >= MIN_PURITY) minOf(meLines.size.toDouble(), MAX_WEIGHT) else 0.0
    }
}
