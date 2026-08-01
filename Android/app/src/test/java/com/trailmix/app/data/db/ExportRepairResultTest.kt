package com.trailmix.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBS-04: the message that tells someone whether their notes are actually backed up.
 *
 * TrailMix is `allowBackup=false` and local-only, so an exported note is the only copy that
 * survives a wipe — this wording is the difference between knowing that and assuming it.
 * These tests exist mostly to pin one property: **a partial success must never read as a
 * success.** Exports were previously fire-and-forget, and the resulting silent failure is
 * exactly the shape of problem that already cost this user notes once.
 */
class ExportRepairResultTest {

    @Test
    fun `nothing to do says so rather than claiming success`() {
        assertEquals("Nothing to export", ExportRepairResult(exported = 0, failures = 0).summary())
    }

    @Test
    fun `clean success reports the count`() {
        assertEquals("Exported 3 notes", ExportRepairResult(exported = 3, failures = 0).summary())
    }

    @Test
    fun `total failure names the likely cause instead of a bare number`() {
        val msg = ExportRepairResult(exported = 0, failures = 2).summary()
        assertTrue(msg, msg.contains("Couldn't export 2 notes"))
        assertTrue(msg, msg.contains("folder is still available"))
    }

    /** The one that matters: 4 succeeded and 1 didn't is NOT "Exported 4 notes". */
    @Test
    fun `partial success still surfaces the failures`() {
        val msg = ExportRepairResult(exported = 4, failures = 1).summary()
        assertTrue(msg, msg.contains("Exported 4 notes"))
        assertTrue(msg, msg.contains("1 note"))
        assertTrue(msg, msg.contains("couldn't be written"))
    }

    @Test
    fun `singular and plural both read correctly`() {
        assertEquals("Exported 1 note", ExportRepairResult(exported = 1, failures = 0).summary())
        assertTrue(ExportRepairResult(0, 1).summary().contains("Couldn't export 1 note —"))
    }

    /** No count is ever silently dropped, whatever the combination. */
    @Test
    fun `every non-zero count appears in the message`() {
        listOf(
            ExportRepairResult(1, 0),
            ExportRepairResult(0, 1),
            ExportRepairResult(2, 3),
            ExportRepairResult(10, 7),
        ).forEach { r ->
            val msg = r.summary()
            if (r.exported > 0) assertTrue(msg, msg.contains(r.exported.toString()))
            if (r.failures > 0) assertTrue(msg, msg.contains(r.failures.toString()))
        }
    }
}
