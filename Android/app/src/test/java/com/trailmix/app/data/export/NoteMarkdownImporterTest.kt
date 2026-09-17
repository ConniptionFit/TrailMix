package com.trailmix.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grounded directly against real files pulled from the recovery device (2026-09-12 incident —
 * see `NoteMarkdownImporter`'s doc comment) rather than synthetic fixtures, because the whole
 * point of this importer is correctness against exactly what actually exists on disk, format
 * drift included.
 */
class NoteMarkdownImporterTest {

    // ── Current format (OBS-02): frontmatter + companion transcript table ────

    private val currentNote = """
        ---
        title: Claude transcription and local processing
        date: 2026-09-01
        time: 10:49
        created: 2026-09-01T10:49:29
        duration: <1m
        duration_ms: 35548
        template: NONE
        topics: [claude, create, device, model, specify, template]
        action_items: 0
        transcript_lines: 5
        transcript: "[[2026-09-01-claude-transcription-and-local-processing.transcript]]"
        source: trailmix
        tags: [trailmix/note]
        ---

        # Claude transcription and local processing

        > [!summary] TL;DR
        > - Claude was used to create a copy of the transcript.
        > - The transcription is done on-device using an AI model.

        *Tue 1 Sep 2026 · 10:49 AM · <1m*

        ## Highlights

        - **`[0:05]`** Claude was used to create a copy of the transcript.
        - **`[0:24]`** The transcription is done on-device using an AI model.

        ## Key points

        ### Transcription Method

        - **`[0:05]`** Claude was used to create a copy of the transcript.
        - **`[0:24]`** The transcription is done on-device using an AI model.

        ---

        📄 **Full transcript:** [[2026-09-01-claude-transcription-and-local-processing.transcript]]
    """.trimIndent()

    private val currentTranscript = """
        ---
        title: Claude transcription and local processing — transcript
        date: 2026-09-01
        duration: <1m
        note: "[[2026-09-01-claude-transcription-and-local-processing]]"
        type: transcript
        source: trailmix
        tags: [trailmix/transcript]
        ---

        # Claude transcription and local processing — transcript

        *Verbatim, on-device. Summary: [[2026-09-01-claude-transcription-and-local-processing]]*

        | Time | Text |
        |---|---|
        | `0:05` | Literally just told Claude to make a copy of it for myself. |
        | `0:24` | Because I didn't want to pay 15 bucks a month\| really. |
    """.trimIndent()

    @Test
    fun `current format recovers title, timing, and transcript`() {
        val imported = NoteMarkdownImporter.parseNote(currentNote, currentTranscript)
        assertNotNull(imported)
        imported!!
        assertEquals("Claude transcription and local processing", imported.title)
        assertEquals(35548L, imported.durationMs)
        assertNull(imported.template)
        assertEquals(2, imported.transcript.size)
        assertEquals("0:05", imported.transcript[0].label)
        assertEquals("Literally just told Claude to make a copy of it for myself.", imported.transcript[0].text)
        // Escaped pipe round-trips back to a literal pipe.
        assertTrue(imported.transcript[1].text.contains("15 bucks a month| really"))
    }

    @Test
    fun `current format body keeps real content and drops derived chrome`() {
        val imported = NoteMarkdownImporter.parseNote(currentNote, currentTranscript)!!
        assertTrue(imported.bodyOverride.contains("Claude was used to create a copy"))
        assertTrue(imported.bodyOverride.contains("Transcription Method"))
        // TL;DR callout, meta line, and the transcript footer are all derived — not source data.
        assertTrue("no leftover blockquote marker", !imported.bodyOverride.contains("[!summary]"))
        assertTrue("no leftover meta line", !imported.bodyOverride.contains("· 10:49 AM ·"))
        assertTrue("no leftover footer", !imported.bodyOverride.contains("Full transcript"))
        assertTrue("no leftover H1", !imported.bodyOverride.contains("# Claude transcription"))
    }

    // ── Early format: transcript inlined under "## Transcript", no title: field ──

    private val earlyNote = """
        ---
        created: 2026-07-17T22:54:55
        source: trailmix
        duration_ms: 233952
        ---

        # Song by Milo

        - **Date:** Jul 17, 2026 · 10:54 PM

        The artist is Milo.

        ## Transcript
        - **3:30** First line of the song.
        - **3:40** Second line of the song.
    """.trimIndent()

    @Test
    fun `early format recovers title from the H1 heading, not frontmatter`() {
        val imported = NoteMarkdownImporter.parseNote(earlyNote, null)
        assertNotNull(imported)
        assertEquals("Song by Milo", imported!!.title)
        assertEquals(233952L, imported.durationMs)
    }

    @Test
    fun `early format recovers the inline transcript when there is no companion file`() {
        val imported = NoteMarkdownImporter.parseNote(earlyNote, null)!!
        assertEquals(2, imported.transcript.size)
        assertEquals("3:30", imported.transcript[0].label)
        assertEquals("First line of the song.", imported.transcript[0].text)
    }

    @Test
    fun `early format body keeps the flat text and drops the old Date bullet and inline transcript`() {
        val imported = NoteMarkdownImporter.parseNote(earlyNote, null)!!
        assertTrue(imported.bodyOverride.contains("The artist is Milo."))
        assertTrue("no leftover Date bullet", !imported.bodyOverride.contains("**Date:**"))
        assertTrue("no leftover inline transcript", !imported.bodyOverride.contains("First line of the song"))
    }

    // ── Intermediate format: old Highlights/custom-H2, no title, no transcript at all ──

    private val intermediateNote = """
        ---
        created: 2026-07-17T22:32:15
        source: trailmix
        duration_ms: 34045
        ---

        # 4a Pricing and Perception

        - **Date:** Jul 17, 2026 · 10:32 PM

        ## Highlights
        - Disagreement on pricing tier (budget vs. mid-range)
        - General discussion about positioning.

        ## Nothing 4a Pricing & Positioning
        - Participants expressed differing opinions.
        - The perceived value proposition was discussed.
    """.trimIndent()

    @Test
    fun `intermediate format with no transcript at all imports cleanly with an empty transcript`() {
        val imported = NoteMarkdownImporter.parseNote(intermediateNote, null)
        assertNotNull(imported)
        imported!!
        assertEquals("4a Pricing and Perception", imported.title)
        assertTrue(imported.transcript.isEmpty())
        assertTrue(imported.bodyOverride.contains("Disagreement on pricing tier"))
        assertTrue(imported.bodyOverride.contains("Nothing 4a Pricing & Positioning"))
    }

    // ── Old-style meeting/attendees metadata bullets (pre-frontmatter-fields) ──

    private val meetingNote = """
        ---
        created: 2026-07-21T09:16:08
        source: trailmix
        duration_ms: 619594
        ---

        # Quarterly vendor policy update requires notification.

        - **Date:** Jul 21, 2026 · 9:16 AM
        - **Meeting:** Vendor Transition Call
        - **Attendees:** Jordan Lee, Casey Park, sam@example.com

        The vendor needs assurance of oversight for actions.
        Automated event notifications are a potential solution.

        ## Transcript
        - **0:12** Some transcript line.
    """.trimIndent()

    @Test
    fun `old-style Meeting and Attendees bullets are recovered and stripped from the body`() {
        val imported = NoteMarkdownImporter.parseNote(meetingNote, null)!!
        assertEquals("Vendor Transition Call", imported.meetingTitle)
        assertEquals(listOf("Jordan Lee", "Casey Park", "sam@example.com"), imported.attendees)
        assertTrue("no leftover Meeting bullet", !imported.bodyOverride.contains("**Meeting:**"))
        assertTrue("no leftover Attendees bullet", !imported.bodyOverride.contains("**Attendees:**"))
        assertTrue(imported.bodyOverride.contains("The vendor needs assurance"))
    }

    // ── Non-TrailMix content must not be silently "imported" as garbage ──────

    @Test
    fun `a file with no frontmatter block is not a TrailMix export`() {
        assertNull(NoteMarkdownImporter.parseNote("# Just a random markdown file\n\nHello.", null))
    }
}
