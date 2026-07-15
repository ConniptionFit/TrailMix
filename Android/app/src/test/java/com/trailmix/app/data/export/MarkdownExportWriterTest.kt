package com.trailmix.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportWriterTest {

    @Test
    fun `file name is date-prefixed and slugified`() {
        val name = MarkdownExportWriter.buildFileName("Q3 Planning: Budget & Scope!", 1_700_000_000_000)
        assertTrue(name.endsWith(".md"))
        assertTrue(name.contains("q3-planning-budget-scope"))
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}-.*\.md$""").matches(name))
    }

    @Test
    fun `blank title falls back to a generic slug`() {
        val name = MarkdownExportWriter.buildFileName("   ", 1_700_000_000_000)
        assertTrue(name.contains("-note.md"))
    }

    @Test
    fun `frontmattered body wraps markdown with created source and duration`() {
        val body = MarkdownExportWriter.frontmatteredBody(
            markdown = "# Title\n\nBody text.",
            createdAtEpochMs = 1_700_000_000_000,
            durationMs = 65_000,
            source = "trailmix",
        )
        assertTrue(body.startsWith("---"))
        assertTrue(body.contains("source: trailmix"))
        assertTrue(body.contains("duration_ms: 65000"))
        assertTrue(body.contains("# Title"))
        assertTrue(body.contains("Body text."))
    }

    @Test
    fun `file names built from the same title and timestamp are stable`() {
        val a = MarkdownExportWriter.buildFileName("Weekly Sync", 1_700_000_000_000)
        val b = MarkdownExportWriter.buildFileName("Weekly Sync", 1_700_000_000_000)
        assertEquals(a, b)
    }
}
