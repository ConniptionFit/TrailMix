package com.trailmix.app.data.speech

import com.trailmix.app.data.model.TranscriptLine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * REL-13: the *file* side of the crash journal.
 *
 * [CaptureJournalTest] covers the replay fold, which was always pure and always tested. This
 * covers the half that decides whether the file replay reads is still there and still intact
 * — the part that actually deletes things. The cases are written against what a crash leaves
 * on disk rather than the happy path, and the two rules being pinned are:
 *
 * - **nothing is deleted on the strength of a failure.** "I could not read this" and "this is
 *   empty" are different answers and only one of them permits a delete;
 * - **damage costs at most the record it happened in** — including across a reopen, which is
 *   the seam where a partial record used to take the next one down with it.
 *
 * The store's directory is a constructor parameter precisely so all of this runs on a temp
 * dir with no device involved.
 */
class CaptureJournalStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: CaptureJournalStore

    @Before
    fun setUp() {
        dir = temp.newFolder("capture-journal")
        store = CaptureJournalStore(dir)
    }

    private fun journalFile(startedAt: Long) = File(dir, "session-$startedAt.jsonl")

    /** Write a journal straight to disk, byte for byte — [tail] is appended unterminated. */
    private fun writeJournal(startedAt: Long, records: List<String>, tail: String = "") {
        journalFile(startedAt).writeText(
            records.joinToString(separator = "") { "$it\n" } + tail,
        )
    }

    private fun line(label: String, text: String) =
        CaptureJournal.lineRecord(TranscriptLine(label, text))

    // ── What may be deleted ────────────────────────────────────────────────

    @Test
    fun `a journal with a transcript in it is offered and left on disk`() = runBlocking {
        writeJournal(
            1_785_000_000_000,
            listOf(
                CaptureJournal.sessionRecord(1_785_000_000_000, resumeNoteId = -1L),
                line("0:04", "Token lifetimes are the first thing we cut."),
            ),
        )

        val pending = store.pending()

        assertEquals(1, pending.size)
        assertEquals("session-1785000000000.jsonl", pending.first().id)
        assertEquals(1, pending.first().session.transcript.size)
        assertTrue("a recoverable journal must survive being listed", journalFile(1_785_000_000_000).exists())
    }

    @Test
    fun `a journal that replayed to nothing is deleted rather than reported`() = runBlocking {
        writeJournal(
            1_785_000_000_000,
            listOf(CaptureJournal.sessionRecord(1_785_000_000_000, resumeNoteId = -1L)),
        )

        assertTrue(store.pending().isEmpty())
        assertFalse(journalFile(1_785_000_000_000).exists())
    }

    @Test
    fun `a cleanly ended session whose delete failed is litter, not a recovery prompt`() = runBlocking {
        writeJournal(
            1_785_000_000_000,
            listOf(
                CaptureJournal.sessionRecord(1_785_000_000_000, resumeNoteId = -1L),
                line("0:04", "This one was already saved."),
                CaptureJournal.endRecord(),
            ),
        )

        assertTrue(
            "offering a note the user already has back as a crash would look like data loss",
            store.pending().isEmpty(),
        )
        assertFalse(journalFile(1_785_000_000_000).exists())
    }

    /**
     * The REL-13 defect. An unreadable file used to fall into the same branch as an empty one
     * and be deleted — so the failures that scale with size (an I/O error, replay running out
     * of memory on a very long transcript) destroyed the longest session on the device.
     */
    @Test
    fun `a journal that cannot be read is kept, not deleted`() = runBlocking {
        val file = journalFile(1_785_000_000_000)
        writeJournal(
            1_785_000_000_000,
            listOf(
                CaptureJournal.sessionRecord(1_785_000_000_000, resumeNoteId = -1L),
                line("0:04", "An hour of a keynote lives in here."),
            ),
        )
        // Skip rather than fail where the filesystem won't honour this (e.g. running as root).
        assumeTrue("filesystem must support revoking read permission", file.setReadable(false))

        try {
            assertTrue("an unreadable journal cannot be offered", store.pending().isEmpty())
            assertTrue("...but it must still be there", file.exists())
            assertEquals(
                "and its bytes must be untouched",
                2,
                file.also { it.setReadable(true) }.readLines().size,
            )
        } finally {
            file.setReadable(true)
        }
    }

    // ── Record boundaries across a reopen ──────────────────────────────────

    /**
     * The second REL-13 defect, and the more insidious one: `adopt` appended straight onto
     * whatever the crash left behind. A journal cut mid-record therefore had the *next*
     * record glued onto the fragment, and the combined line parses as neither — so choosing
     * "Continue" silently destroyed the first thing said after the recovery.
     */
    @Test
    fun `adopting a journal cut mid-record does not destroy the record that follows it`() = runBlocking {
        writeJournal(
            1_785_000_000_000,
            listOf(
                CaptureJournal.sessionRecord(1_785_000_000_000, resumeNoteId = -1L),
                line("0:04", "Before the crash."),
            ),
            // A write that landed part way: a real record, with its terminator missing.
            tail = line("0:31", "Cut off half way").dropLast(12),
        )

        store.adopt("session-1785000000000.jsonl")
        store.line(TranscriptLine("1:02", "The first thing said after recovery."))
        store.awaitIdle()

        val replayed = journalFile(1_785_000_000_000).useLines { CaptureJournal.replay(it) }
        val texts = replayed.transcript.map { it.text }
        assertTrue("the record written before the crash must survive", "Before the crash." in texts)
        assertTrue(
            "the record written after adopting must survive the fragment ahead of it",
            "The first thing said after recovery." in texts,
        )
    }

    @Test
    fun `adopting an intact journal appends without disturbing it`() = runBlocking {
        val records = listOf(
            CaptureJournal.sessionRecord(1_785_000_000_000, resumeNoteId = 7L),
            line("0:04", "Before the crash."),
        )
        writeJournal(1_785_000_000_000, records)

        store.adopt("session-1785000000000.jsonl")
        store.line(TranscriptLine("1:02", "After."))
        store.awaitIdle()

        val lines = journalFile(1_785_000_000_000).readLines()
        assertEquals("no blank line should have been introduced", 3, lines.size)
        assertEquals(records, lines.take(2))
        val replayed = journalFile(1_785_000_000_000).useLines { CaptureJournal.replay(it) }
        assertEquals(7L, replayed.resumeNoteId)
        assertEquals(listOf("Before the crash.", "After."), replayed.transcript.map { it.text })
    }

    // ── Session lifecycle on disk ──────────────────────────────────────────

    @Test
    fun `records land in the order they were submitted`() = runBlocking {
        store.begin(1_785_000_000_000, resumeNoteId = -1L)
        repeat(50) { store.line(TranscriptLine("0:0$it", "utterance $it")) }
        store.delta(CaptureJournal.deltaRecord(durationMs = 50_000, noteTitle = "Identity keynote"))
        store.awaitIdle()

        val replayed = journalFile(1_785_000_000_000).useLines { CaptureJournal.replay(it) }
        assertEquals(1_785_000_000_000, replayed.startedAtEpochMs)
        assertEquals("Identity keynote", replayed.noteTitle)
        assertEquals(
            (0 until 50).map { "utterance $it" },
            replayed.transcript.map { it.text },
        )
    }

    @Test
    fun `the journal being written to right now is never offered as a crash`() = runBlocking {
        store.begin(1_785_000_000_000, resumeNoteId = -1L)
        store.line(TranscriptLine("0:04", "Still recording."))
        store.awaitIdle()

        assertTrue(store.pending().isEmpty())
        assertTrue("and it must certainly not be deleted", journalFile(1_785_000_000_000).exists())
    }

    @Test
    fun `finish marks the session ended and removes the file`() = runBlocking {
        store.begin(1_785_000_000_000, resumeNoteId = -1L)
        store.line(TranscriptLine("0:04", "Saved as a note."))
        store.finish()
        store.awaitIdle()

        assertFalse(journalFile(1_785_000_000_000).exists())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `discarding the live capture takes its journal with it`() = runBlocking {
        store.begin(1_785_000_000_000, resumeNoteId = -1L)
        store.line(TranscriptLine("0:04", "Not wanted."))
        store.discardCurrent()
        store.awaitIdle()

        assertFalse(journalFile(1_785_000_000_000).exists())
    }

    @Test
    fun `beginning a second session closes the first and leaves it recoverable`() = runBlocking {
        store.begin(1_785_000_000_000, resumeNoteId = -1L)
        store.line(TranscriptLine("0:04", "The session that crashed."))
        // No finish() — this is what a crash looks like from the next session's side.
        store.begin(1_785_000_003_000, resumeNoteId = -1L)
        store.line(TranscriptLine("0:02", "The session after it."))
        store.awaitIdle()

        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals("session-1785000000000.jsonl", pending.first().id)
        assertEquals(
            listOf("The session that crashed."),
            pending.first().session.transcript.map { it.text },
        )
    }

    @Test
    fun `journals are offered newest session first`() = runBlocking {
        listOf(1_785_000_000_000, 1_785_000_600_000, 1_785_000_300_000).forEach { startedAt ->
            writeJournal(
                startedAt,
                listOf(
                    CaptureJournal.sessionRecord(startedAt, resumeNoteId = -1L),
                    line("0:04", "session $startedAt"),
                ),
            )
        }

        assertEquals(
            listOf(1_785_000_600_000, 1_785_000_300_000, 1_785_000_000_000),
            store.pending().map { it.session.startedAtEpochMs },
        )
    }

    @Test
    fun `discard removes exactly the journal named`() = runBlocking {
        listOf(1_785_000_000_000, 1_785_000_600_000).forEach { startedAt ->
            writeJournal(
                startedAt,
                listOf(
                    CaptureJournal.sessionRecord(startedAt, resumeNoteId = -1L),
                    line("0:04", "session $startedAt"),
                ),
            )
        }

        store.discard("session-1785000000000.jsonl")

        assertFalse(journalFile(1_785_000_000_000).exists())
        assertTrue(journalFile(1_785_000_600_000).exists())
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a journal whose header was lost still recovers, dated from its filename`() = runBlocking {
        // The header is the one record replay can reconstruct: the store encodes the start
        // time in the filename for exactly this case.
        writeJournal(
            1_785_000_000_000,
            listOf(line("0:04", "The header never made it to disk.")),
        )

        val pending = store.pending().firstOrNull()

        assertNotNull(pending)
        assertEquals(1_785_000_000_000, pending!!.session.startedAtEpochMs)
        assertEquals(1, pending.session.transcript.size)
    }

    @Test
    fun `files that are not journals are ignored entirely`() = runBlocking {
        File(dir, "notes.txt").writeText("nothing to do with a capture")
        File(dir, "session-1785000000000.jsonl.tmp").writeText("half a download")

        assertTrue(store.pending().isEmpty())
        assertTrue(File(dir, "notes.txt").exists())
        assertTrue(File(dir, "session-1785000000000.jsonl.tmp").exists())
    }

    @Test
    fun `pending is empty and harmless before any session has ever run`() = runBlocking {
        assertNull(CaptureJournalStore(temp.newFolder("never-used")).pending().firstOrNull())
    }
}
