package com.trailmix.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AI-06: a re-merge must never downgrade a real note title back to the placeholder.
 *
 * The regression these pin was found on-device 2026-08-01: a note titled
 * "Migration Test on v1.10.0" reverted to "Note — Aug 1, 3:12 PM" because the AI call failed
 * and the deterministic fallback always emits a fresh default. That also desynchronised the
 * exported filenames from the wiki-links inside them (OBS-03), so it was not cosmetic.
 */
class NoteTitleTest {

    private val noon = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .parse("2026-08-01 15:12")!!
        .time

    @Test
    fun `default title is recognised as default`() {
        assertTrue(NoteTitle.isDefault(NoteTitle.default(noon)))
    }

    @Test
    fun `a content-derived title is not default`() {
        assertFalse(NoteTitle.isDefault("Migration Test on v1.10.0"))
        assertFalse(NoteTitle.isDefault("Notes on the roadmap review"))
    }

    /** "Note — " is the placeholder; "Notes ..." is a real title that merely starts similarly. */
    @Test
    fun `a real title starting with Note is not mistaken for the placeholder`() {
        assertFalse(NoteTitle.isDefault("Notebook migration plan"))
        assertFalse(NoteTitle.isDefault("Note taking app teardown"))
    }

    /** Recognition must not depend on the clock: a default from another day still counts. */
    @Test
    fun `default from a different timestamp is still recognised`() {
        val otherDay = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .parse("2025-01-09 07:45")!!
            .time
        assertTrue(NoteTitle.isDefault(NoteTitle.default(otherDay)))
    }

    @Test
    fun `blank counts as default`() {
        assertTrue(NoteTitle.isDefault(""))
        assertTrue(NoteTitle.isDefault("   "))
    }

    // ── preferExisting ──────────────────────────────────────────────────────

    /** The bug: deterministic fallback emits a default over a real title. Keep the real one. */
    @Test
    fun `placeholder does not overwrite a real existing title`() {
        assertEquals(
            "Migration Test on v1.10.0",
            NoteTitle.preferExisting(
                incoming = NoteTitle.default(noon),
                existing = "Migration Test on v1.10.0",
            ),
        )
    }

    /** A genuine re-title must still land — this guard is narrow on purpose. */
    @Test
    fun `a real incoming title replaces a real existing one`() {
        assertEquals(
            "Roadmap review",
            NoteTitle.preferExisting(incoming = "Roadmap review", existing = "Migration Test"),
        )
    }

    @Test
    fun `a real incoming title replaces a placeholder`() {
        assertEquals(
            "Roadmap review",
            NoteTitle.preferExisting(incoming = "Roadmap review", existing = NoteTitle.default(noon)),
        )
    }

    /** Two placeholders: nothing to preserve, so the fresh one wins and the clock advances. */
    @Test
    fun `placeholder over placeholder keeps the incoming one`() {
        val incoming = NoteTitle.default(noon)
        assertEquals(incoming, NoteTitle.preferExisting(incoming, NoteTitle.default(noon - 60_000)))
    }

    @Test
    fun `blank incoming never wipes a real title`() {
        assertEquals("Roadmap review", NoteTitle.preferExisting(incoming = "", existing = "Roadmap review"))
    }

    // ── AI-07: cleaning the model's first line ──────────────────────────────

    private val createdAt = 1_785_000_000_000L

    /**
     * The exact string a real merge produced on the Pixel 9 Pro (2026-08-01): the model
     * ignored "max 8 words" and echoed the transcript's opening, and the old blind
     * `.take(80)` sliced the second sentence mid-clause. This is the regression test.
     */
    @Test
    fun `the observed run-on title is cut at the sentence, not at 80 characters`() {
        val raw = "Crash recovery verification for trail mix. The identity platform keynote begins with token lifetimes."

        assertEquals("Crash recovery verification for trail mix", NoteTitle.clean(raw, createdAt))
    }

    @Test
    fun `a good title is passed through untouched`() {
        assertEquals("Identity platform keynote", NoteTitle.clean("Identity platform keynote", createdAt))
    }

    /** Markdown, labels, list markers and quotes are all things a model plausibly emits. */
    @Test
    fun `model formatting noise is stripped`() {
        listOf(
            "# Q3 planning",
            "## Q3 planning",
            "**Q3 planning**",
            "Title: Q3 planning",
            "title - Q3 planning",
            "1. Q3 planning",
            "- Q3 planning",
            "> Q3 planning",
            "\"Q3 planning\"",
            "“Q3 planning”",
            "`Q3 planning`",
            "  Q3 planning  ",
            "Q3 planning.",
        ).forEach { raw ->
            assertEquals(raw, "Q3 planning", NoteTitle.clean(raw, createdAt))
        }
    }

    /** A blanket strip of `_`/`*` would mangle real identifiers — only paired markers go. */
    @Test
    fun `underscores inside a word survive`() {
        assertEquals("user_id mapping rollout", NoteTitle.clean("user_id mapping rollout", createdAt))
    }

    /**
     * The sentence split must require whitespace after the period, or every version number
     * and decimal in a title becomes a truncation point.
     */
    @Test
    fun `decimals and version numbers are not sentence boundaries`() {
        assertEquals("99.9% uptime regressions", NoteTitle.clean("99.9% uptime regressions", createdAt))
        assertEquals("v1.13.0 release notes", NoteTitle.clean("v1.13.0 release notes", createdAt))
    }

    /** A long clause with no punctuation still has to be capped — on a word boundary. */
    @Test
    fun `a run-on with no punctuation is capped at whole words`() {
        val raw = "quarterly identity governance and provisioning latency review with the platform team and partners"

        val title = NoteTitle.clean(raw, createdAt)

        assertTrue(title, title.endsWith("…"))
        assertTrue("must not end mid-word: $title", raw.startsWith(title.removeSuffix("…")))
        assertTrue("$title is still too long", title.length <= 62)
    }

    /** One pathological unbroken token must still terminate rather than pass through. */
    @Test
    fun `a single enormous token is still cut`() {
        val title = NoteTitle.clean("x".repeat(200), createdAt)

        assertTrue(title, title.endsWith("…"))
        assertTrue("$title is still too long", title.length <= 62)
    }

    /**
     * Nothing usable must fall back to the placeholder — not to an empty string, which would
     * leave the note, the Home row, and the exported *filename* unnamed.
     */
    @Test
    fun `nothing usable falls back to the default title`() {
        listOf("", "   ", "#", "**", "\"\"", ".", "-").forEach { raw ->
            assertTrue(raw, NoteTitle.isDefault(NoteTitle.clean(raw, createdAt)))
        }
    }

    /** Multi-line model output: only the first non-blank line is the title. */
    @Test
    fun `only the first non-blank line is used`() {
        val raw = "\n\n  Session revocation design  \nThe team agreed to ship it next sprint."

        assertEquals("Session revocation design", NoteTitle.clean(raw, createdAt))
    }

    /** clean() must keep producing values isDefault can classify, or AI-06 silently breaks. */
    @Test
    fun `a cleaned real title is never mistaken for a placeholder`() {
        assertFalse(NoteTitle.isDefault(NoteTitle.clean("Identity platform keynote", createdAt)))
        assertEquals(
            "Identity platform keynote",
            NoteTitle.preferExisting(
                incoming = NoteTitle.clean("", createdAt),
                existing = NoteTitle.clean("Identity platform keynote", createdAt),
            ),
        )
    }
}
