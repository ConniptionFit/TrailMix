package com.trailmix.app.data.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REL-15: the short-write check. The rest of [MarkdownExportWriter] is SAF, which no JVM
 * test can reach — this is the piece with a judgement call in it, and the judgement is that
 * a provider's silence must never be mistaken for a failed write.
 */
class MarkdownExportWriterTest {

    @Test
    fun `a file shorter than the note it should hold is a failed write`() {
        assertTrue(MarkdownExportWriter.isShortWrite(reported = 40, expected = 4_096))
        assertTrue(MarkdownExportWriter.isShortWrite(reported = 4_095, expected = 4_096))
    }

    @Test
    fun `a complete write passes`() {
        assertFalse(MarkdownExportWriter.isShortWrite(reported = 4_096, expected = 4_096))
    }

    @Test
    fun `a provider that does not report a size is not a failure`() {
        // The one-sided rule. Read the other way, every export to such a provider would be
        // rejected, retried forever, and reported to the user as never backed up.
        assertFalse(MarkdownExportWriter.isShortWrite(reported = 0, expected = 4_096))
        assertFalse(MarkdownExportWriter.isShortWrite(reported = -1, expected = 4_096))
    }

    @Test
    fun `a longer file is not a failure either`() {
        // Some providers pad or report block-rounded sizes; the note is fully there.
        assertFalse(MarkdownExportWriter.isShortWrite(reported = 8_192, expected = 4_096))
    }

    @Test
    fun `an empty note is never treated as a short write`() {
        assertFalse(MarkdownExportWriter.isShortWrite(reported = 0, expected = 0))
    }
}
