package com.trailmix.app.data.db

import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OBS-01 (v1.6.0): recipe outputs in the exported Markdown. */
class NoteMarkdownTest {

    private fun note() = NoteEntity(
        id = 1,
        title = "Test Note",
        segmentsJson = SegmentsJson.encode(
            listOf(NoteSegment("Body sentence.", Provenance.TRANSCRIPT)),
        ),
        transcriptJson = TranscriptJson.encode(
            listOf(TranscriptLine("0:05", "Raw line.")),
        ),
        typedFragments = "",
        durationMs = 60_000,
        createdAtEpochMs = 1_700_000_000_000,
    )

    @Test
    fun `no recipe outputs - no Recipe Outputs section`() {
        val md = note().toMarkdown()
        assertFalse(md.contains("## Recipe Outputs"))
        assertTrue(md.contains("## Transcript"))
    }

    @Test
    fun `recipe outputs render as named subsections before the transcript`() {
        val md = note().toMarkdown(
            recipeOutputs = listOf(
                "Follow-up email" to "Subject: Hello\nBody text.",
                "Action items" to "- [Sam] do the thing",
            ),
        )
        assertTrue(md.contains("## Recipe Outputs"))
        assertTrue(md.contains("### Follow-up email"))
        assertTrue(md.contains("Subject: Hello"))
        assertTrue(md.contains("### Action items"))
        // Recipe outputs come before the raw transcript appendix.
        assertTrue(md.indexOf("## Recipe Outputs") < md.indexOf("## Transcript"))
    }

    @Test
    fun `recipe output text is trimmed`() {
        val md = note().toMarkdown(recipeOutputs = listOf("Summarize" to "  padded  \n"))
        assertTrue(md.contains("### Summarize\npadded"))
        assertEquals(-1, md.indexOf("  padded"))
    }
}
