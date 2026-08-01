package com.trailmix.app.data.ai

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
