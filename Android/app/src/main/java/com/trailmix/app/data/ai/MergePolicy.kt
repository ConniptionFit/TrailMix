package com.trailmix.app.data.ai

import com.trailmix.app.data.model.TranscriptLine

/**
 * CAP-11 (v1.8.0): what End & Merge should do with what the session captured.
 * - Nothing typed AND nothing transcribed → save nothing at all.
 * - Typed notes but no transcript → save the typed notes verbatim; no AI merge and no
 *   structured summary — summarization only ever runs when there's a transcript to parse.
 * - Any transcript → the normal AI merge/summarize path.
 */
object MergePolicy {
    fun nothingToSave(typedFragments: String, transcript: List<TranscriptLine>): Boolean =
        typedFragments.isBlank() && !hasTranscript(transcript)

    fun hasTranscript(transcript: List<TranscriptLine>): Boolean =
        transcript.any { it.text.isNotBlank() }
}
