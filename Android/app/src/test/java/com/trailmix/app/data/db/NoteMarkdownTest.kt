package com.trailmix.app.data.db

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OBS-01 (v1.6.0): recipe outputs in the exported Markdown.
 *  AI-05 (v1.10.0): per-bullet source annotation in the exported Markdown. */
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

    // ── AI-05: source annotation ─────────────────────────────────────────────

    private val summary = StructuredSummary(
        highlights = emptyList(),
        sections = listOf(
            SummarySection(
                heading = "0:00 – 7:00 · embeddings",
                bullets = listOf(
                    SummaryBullet("Retrieval quality hinges on chunk size.", Provenance.TRANSCRIPT, timestampLabel = "3:12"),
                    SummaryBullet("Ask the team about our chunking.", Provenance.FRAGMENT),
                    SummaryBullet("Untraced spoken point.", Provenance.TRANSCRIPT),
                ),
            ),
        ),
        actionItems = listOf(
            ActionItem("Benchmark this on our dataset.", source = Provenance.FRAGMENT),
        ),
    )

    private fun structuredNote(showSources: Boolean = true) = note().copy(
        summaryJson = StructuredSummaryJson.encode(summary),
        showSources = showSources,
    )

    @Test
    fun `structured export tags each bullet with its source`() {
        val md = structuredNote().toMarkdown()
        assertTrue("spoken bullets carry their capture offset", md.contains("**`[3:12]`** Retrieval quality"))
        assertTrue("typed bullets are marked as the user's own", md.contains("**`[you]`** Ask the team"))
        assertTrue("untraceable spoken bullets still say where they came from", md.contains("**`[transcript]`** Untraced"))
        assertTrue("action items are annotated too", md.contains("- [ ] **`[you]`** Benchmark this"))
    }

    @Test
    fun `an annotated export explains its own markers`() {
        val md = structuredNote().toMarkdown()
        assertTrue(md.contains("**Sources:**"))
        assertTrue(md.contains("`[you]`"))
        // The legend precedes the body it describes.
        assertTrue(md.indexOf("**Sources:**") < md.indexOf("## 0:00 – 7:00"))
    }

    @Test
    fun `turning sources off removes the tags and the legend`() {
        val md = structuredNote(showSources = false).toMarkdown()
        assertFalse(md.contains("**Sources:**"))
        assertFalse(md.contains("[you]"))
        assertFalse(md.contains("[3:12]"))
        // The content itself is untouched — only the annotation is suppressed.
        assertTrue(md.contains("- Retrieval quality hinges on chunk size."))
        assertTrue(md.contains("- [ ] Benchmark this on our dataset."))
    }

    @Test
    fun `a hand-edited body is exported verbatim, with no annotation`() {
        val md = structuredNote().copy(bodyOverride = "My own rewritten note.").toMarkdown()
        assertTrue(md.contains("My own rewritten note."))
        assertFalse(md.contains("**Sources:**"))
        assertFalse(md.contains("[3:12]"))
    }
}
