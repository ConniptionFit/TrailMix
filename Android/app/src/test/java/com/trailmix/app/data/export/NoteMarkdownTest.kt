package com.trailmix.app.data.export

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBS-02 (v1.11.0): the exported Markdown has two jobs — scannable on a phone, and
 * pasteable into another model as context. These tests pin both.
 */
class NoteMarkdownTest {

    private val summary = StructuredSummary(
        highlights = emptyList(),
        sections = listOf(
            SummarySection(
                "0:00 – 7:00 · sharding",
                listOf(
                    SummaryBullet("Sharding cut tail latency by two thirds.", Provenance.TRANSCRIPT, timestampLabel = "3:12"),
                    SummaryBullet("Check whether our cluster does this.", Provenance.FRAGMENT),
                ),
            ),
            SummarySection(
                "7:00 – 14:00 · replication",
                listOf(
                    SummaryBullet("Replication lag was the real bottleneck.", Provenance.TRANSCRIPT, timestampLabel = "9:40"),
                ),
            ),
        ),
        actionItems = listOf(
            ActionItem("Benchmark our p99 next sprint.", source = Provenance.FRAGMENT),
            ActionItem("Read the WAL shipping paper.", source = Provenance.TRANSCRIPT, timestampLabel = "12:05"),
        ),
    )

    private fun source(
        summary: StructuredSummary? = this.summary,
        transcript: List<TranscriptLine> = listOf(
            TranscriptLine("0:04", "Thanks for having me."),
            TranscriptLine("3:12", "Sharding cut tail latency by two thirds."),
        ),
        showSources: Boolean = true,
        bodyOverride: String? = null,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
        noteLinkBase: String? = null,
        transcriptLinkBase: String? = null,
    ) = NoteMarkdown.Source(
        title = "Scaling Postgres",
        createdAtEpochMs = 1_785_000_000_000,
        durationMs = 45 * 60_000L,
        meetingTitle = "ScaleConf — Track B",
        attendees = listOf("Sam Reed"),
        template = "PRESENTATION",
        summary = summary,
        bodyOverride = bodyOverride,
        transcript = transcript,
        recipeOutputs = recipeOutputs,
        showSources = showSources,
        noteLinkBase = noteLinkBase,
        transcriptLinkBase = transcriptLinkBase,
    )

    // ── Frontmatter: what another model reads first ──────────────────────────

    @Test
    fun `frontmatter carries queryable metadata`() {
        val md = NoteMarkdown.buildNote(source())
        val fm = md.substringAfter("---").substringBefore("---")
        assertTrue(md.startsWith("---"))
        assertTrue(fm.contains("title: Scaling Postgres"))
        assertTrue(fm.contains("duration: 45m"))
        assertTrue(fm.contains("duration_ms: 2700000"))
        assertTrue(fm.contains("meeting: ScaleConf"))
        assertTrue(fm.contains("attendees: [Sam Reed]"))
        assertTrue(fm.contains("template: PRESENTATION"))
        assertTrue(fm.contains("action_items: 2"))
        assertTrue(fm.contains("transcript_lines: 2"))
        assertTrue(fm.contains("source: trailmix"))
        assertTrue(fm.contains("tags: ["))
    }

    @Test
    fun `frontmatter flags the user's own commitments and links the transcript`() {
        val fm = NoteMarkdown.buildNote(source()).substringAfter("---").substringBefore("---")
        assertTrue(fm.contains("has_own_commitments: true"))
        assertTrue(fm.contains("transcript: \"[[2026-07-25-scaling-postgres.transcript]]\""))
    }

    @Test
    fun `yaml values are quoted only when they need it`() {
        val md = NoteMarkdown.buildNote(source().copy(title = "Q3: pricing", meetingTitle = null))
        assertTrue(md.contains("title: \"Q3: pricing\""))
        // A plain title needs no quoting.
        assertTrue(NoteMarkdown.buildNote(source()).contains("title: Scaling Postgres\n"))
    }

    @Test
    fun `a note with no transcript gets no transcript link`() {
        val md = NoteMarkdown.buildNote(source(transcript = emptyList()))
        assertTrue(md.contains("transcript_lines: 0"))
        assertFalse(md.contains("transcript: \"[["))
        assertFalse(md.contains("Full transcript"))
    }

    // ── Body structure: scannable on a phone ────────────────────────────────

    @Test
    fun `body leads with a TLDR callout then headed sections`() {
        val md = NoteMarkdown.buildNote(source())
        assertTrue(md.contains("# Scaling Postgres"))
        assertTrue(md.contains("> [!summary] TL;DR"))
        assertTrue(md.contains("## Key points"))
        assertTrue(md.contains("### 0:00 – 7:00 · sharding"))
        assertTrue(md.contains("### 7:00 – 14:00 · replication"))
        assertTrue(md.contains("## Action items"))
        // TL;DR precedes the detail it summarizes.
        assertTrue(md.indexOf("TL;DR") < md.indexOf("## Key points"))
        // Action-item count is surfaced in the TL;DR.
        assertTrue(md.contains("**2 action items**"))
    }

    @Test
    fun `sections are H3 under an H2 so the outline nests correctly`() {
        val md = NoteMarkdown.buildNote(source())
        val keyPoints = md.indexOf("## Key points")
        val firstSection = md.indexOf("### 0:00")
        assertTrue(keyPoints in 0 until firstSection)
        // Not promoted to H2, which would flatten the outline.
        assertFalse(md.contains("\n## 0:00 – 7:00"))
    }

    @Test
    fun `action items are checkboxes and keep owner and due date`() {
        val md = NoteMarkdown.buildNote(
            source(
                summary = summary.copy(
                    actionItems = listOf(
                        ActionItem("Send the deck.", owner = "Sam", deadline = "Friday", source = Provenance.TRANSCRIPT),
                    ),
                ),
            ),
        )
        assertTrue(md.contains("- [ ] "))
        assertTrue(md.contains("**Sam**"))
        assertTrue(md.contains("*(due Friday)*"))
    }

    @Test
    fun `provenance tags render and can be switched off`() {
        val on = NoteMarkdown.buildNote(source())
        assertTrue(on.contains("**`[3:12]`** Sharding cut tail latency"))
        assertTrue(on.contains("**`[you]`** Check whether our cluster"))

        val off = NoteMarkdown.buildNote(source(showSources = false))
        assertFalse(off.contains("[you]"))
        assertFalse(off.contains("[3:12]"))
        assertTrue(off.contains("- Sharding cut tail latency by two thirds."))
    }

    @Test
    fun `a hand-edited body replaces the structure verbatim`() {
        val md = NoteMarkdown.buildNote(source(bodyOverride = "My own rewrite."))
        assertTrue(md.contains("My own rewrite."))
        assertFalse(md.contains("## Key points"))
    }

    @Test
    fun `recipe outputs render as named subsections`() {
        val md = NoteMarkdown.buildNote(
            source(recipeOutputs = listOf("Follow-up email" to "Subject: Hi\nBody.")),
        )
        assertTrue(md.contains("## Recipe outputs"))
        assertTrue(md.contains("### Follow-up email"))
        assertTrue(md.contains("Subject: Hi"))
    }

    // ── The split: the reason this format exists ────────────────────────────

    @Test
    fun `the note does NOT inline the transcript`() {
        val md = NoteMarkdown.buildNote(source())
        assertFalse("the transcript must not be inlined", md.contains("Thanks for having me."))
        assertTrue(md.contains("📄 **Full transcript:** [[2026-07-25-scaling-postgres.transcript]]"))
    }

    @Test
    fun `the transcript file stands alone and links back`() {
        val md = NoteMarkdown.buildTranscript(source())
        assertTrue(md.startsWith("---"))
        assertTrue(md.contains("type: transcript"))
        assertTrue(md.contains("note: \"[[2026-07-25-scaling-postgres]]\""))
        assertTrue(md.contains("# Scaling Postgres — transcript"))
        assertTrue(md.contains("| Time | Text |"))
        assertTrue(md.contains("| `0:04` | Thanks for having me. |"))
        assertTrue(md.contains("| `3:12` | Sharding cut tail latency by two thirds. |"))
    }

    @Test
    fun `pipes in transcript text are escaped so the table survives`() {
        val md = NoteMarkdown.buildTranscript(
            source(transcript = listOf(TranscriptLine("0:01", "Use a | pipe here."))),
        )
        assertTrue(md.contains("Use a \\| pipe here."))
    }

    // ── File naming ─────────────────────────────────────────────────────────

    @Test
    fun `file names are date-slugged and the transcript sits beside the note`() {
        val at = 1_785_000_000_000
        assertEquals("2026-07-25-scaling-postgres.md", NoteMarkdown.noteFileName("Scaling Postgres", at))
        assertEquals(
            "2026-07-25-scaling-postgres.transcript.md",
            NoteMarkdown.transcriptFileName("Scaling Postgres", at),
        )
        assertEquals("2026-07-25-note.md", NoteMarkdown.noteFileName("!!!", at))
    }

    @Test
    fun `durations read in human units`() {
        assertEquals("<1m", NoteMarkdown.humanDuration(0))
        assertEquals("45m", NoteMarkdown.humanDuration(45 * 60_000L))
        assertEquals("1h", NoteMarkdown.humanDuration(60 * 60_000L))
        assertEquals("1h 30m", NoteMarkdown.humanDuration(90 * 60_000L))
    }

    // ── OBS-03: links must point at the files that actually exist ────────────
    //
    // The pair is only a pair if the links resolve. Filenames are pinned to whatever the
    // file was first written as, while the title can drift afterwards, so the two must be
    // allowed to disagree — and when they do, the *filename* wins. Found on-device
    // 2026-08-01: a transcript whose back-link pointed at a note file that never existed.

    @Test
    fun `note links to the transcript's real filename, not one derived from the title`() {
        val md = NoteMarkdown.buildNote(
            source(
                noteLinkBase = "2026-08-01-migration-test-on-v1-10-0",
                transcriptLinkBase = "2026-08-01-migration-test-on-v1-10-0.transcript",
            ),
        )
        assertTrue(md.contains("[[2026-08-01-migration-test-on-v1-10-0.transcript]]"))
        // The title-derived name must not leak into either the footer or the frontmatter.
        assertFalse(md.contains("scaling-postgres"))
    }

    @Test
    fun `transcript links back to the note's real filename`() {
        val md = NoteMarkdown.buildTranscript(
            source(
                noteLinkBase = "2026-08-01-migration-test-on-v1-10-0",
                transcriptLinkBase = "2026-08-01-migration-test-on-v1-10-0.transcript",
            ),
        )
        assertTrue(md.contains("note: \"[[2026-08-01-migration-test-on-v1-10-0]]\""))
        assertTrue(md.contains("Summary: [[2026-08-01-migration-test-on-v1-10-0]]"))
        assertFalse(md.contains("scaling-postgres"))
    }

    /** The two documents must name each other — this is the property that actually broke. */
    @Test
    fun `the pair's links resolve to each other`() {
        val noteBase = "2026-08-01-note-aug-1-3-12-pm"
        val transcriptBase = "$noteBase.transcript"
        val src = source(noteLinkBase = noteBase, transcriptLinkBase = transcriptBase)

        assertTrue(NoteMarkdown.buildNote(src).contains("[[$transcriptBase]]"))
        assertTrue(NoteMarkdown.buildTranscript(src).contains("[[$noteBase]]"))
    }

    /** First export / Share: nothing tracked yet, so title-derived names are the right answer. */
    @Test
    fun `falls back to title-derived names when no file is tracked`() {
        val expected = NoteMarkdown.baseName("Scaling Postgres", 1_785_000_000_000)
        val note = NoteMarkdown.buildNote(source())
        val transcript = NoteMarkdown.buildTranscript(source())

        assertTrue(note.contains("[[$expected.transcript]]"))
        assertTrue(transcript.contains("[[$expected]]"))
    }

    /** A re-title must not strand the links: the filename is what the reader can open. */
    @Test
    fun `a retitled note still links to its original filenames`() {
        val md = NoteMarkdown.buildNote(
            source(
                noteLinkBase = "2026-07-15-original-name",
                transcriptLinkBase = "2026-07-15-original-name.transcript",
            ),
        )
        // Title in the body stays current…
        assertTrue(md.contains("# Scaling Postgres"))
        // …while the link keeps pointing at the file on disk.
        assertTrue(md.contains("[[2026-07-15-original-name.transcript]]"))
    }
}
