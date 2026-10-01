package com.trailmix.app.data.ai

import com.trailmix.app.data.model.TranscriptLine

/**
 * AI-22: renders transcript lines for a model prompt as `[12:30] Them: text`, so the model can
 * tell who said what (Granola's "Me"/"Them" split). The speaker is the diarization label
 * ([TranscriptLine.speakerLabel], "Speaker 2") when there is one, else the CAP-31 lane
 * ([TranscriptLine.speechSource], "Me"/"Them"), else nothing — and a line with no label and no
 * speaker is just its text. Pure and Android-free.
 */
object TranscriptLabels {

    fun speakerOf(line: TranscriptLine): String? =
        line.speakerLabel?.takeIf { it.isNotBlank() } ?: line.speechSource?.displayName

    fun render(lines: List<TranscriptLine>): String = lines.joinToString("\n") { line ->
        val speaker = speakerOf(line)
        val body = if (speaker == null) line.text else "$speaker: ${line.text}"
        if (line.label.isBlank()) body else "[${line.label}] $body"
    }
}
