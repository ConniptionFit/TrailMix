package com.trailmix.app.data.db

import com.trailmix.app.data.export.ExportSink
import com.trailmix.app.data.export.ExportedFiles
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * REL-14: the export/delete **cascade** — the rules deciding which notes reach the user's
 * export folder, what a delete takes with it, and what a restore rewrites.
 *
 * This is the app's only backup story (`allowBackup=false`, local-only; the exported Markdown
 * is the copy that survives a wipe) and it had no tests at all. Same shape as REL-13: the pure
 * halves were covered, and the half that deletes and overwrites things was not. Three rules
 * are pinned here, each of which was broken:
 *
 * - **a slow export may never write back anything but its own two URIs.** The note it was
 *   handed is a snapshot from before the I/O; treating it as current silently reverts whatever
 *   the user did meanwhile — including deleting the note;
 * - **repairing the backlog is not itself a reason to repair the backlog.** The opportunistic
 *   retry and the explicit repair are the same work and must not recurse into each other;
 * - **a restore rewrites both halves of a note.** The delete removed the summary *and* the
 *   transcript companion, so leaving either tracked URI behind points at a document that no
 *   longer exists.
 *
 * Runs on plain JVM fakes — no Room, no SAF, no device — which is what the [ExportSink]
 * seam exists for.
 */
class NotesRepositoryTest {

    private lateinit var noteDao: FakeNoteDao
    private lateinit var chatDao: FakeChatDao
    private lateinit var exporter: FakeExportSink
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() {
        noteDao = FakeNoteDao()
        chatDao = FakeChatDao()
        exporter = FakeExportSink()
        repo = NotesRepository(noteDao, chatDao, exporter)
    }

    private fun seed(
        title: String = "Identity keynote",
        transcript: List<TranscriptLine> = listOf(TranscriptLine("0:04", "Token lifetimes first.")),
        noteUri: String? = null,
        transcriptUri: String? = null,
        deletedAt: Long? = null,
    ): Long = noteDao.seed(
        NoteEntity(
            title = title,
            segmentsJson = "[]",
            transcriptJson = TranscriptJson.encode(transcript),
            typedFragments = "",
            durationMs = 60_000,
            createdAtEpochMs = 1_785_000_000_000,
            obsidianFileUri = noteUri,
            transcriptFileUri = transcriptUri,
            deletedAtEpochMs = deletedAt,
        ),
    )

    private suspend fun save(title: String = "Identity keynote") = repo.saveMergedNote(
        title = title,
        segments = listOf(NoteSegment("Token lifetimes first.", Provenance.TRANSCRIPT)),
        transcript = listOf(TranscriptLine("0:04", "Token lifetimes first.")),
        typedFragments = "",
        durationMs = 60_000,
        createdAtEpochMs = 1_785_000_000_000,
        mergedWithAi = false,
    )

    // ── A stale export must not revert the row it lands on ──────────────────

    /**
     * The REL-14 defect, and the one with teeth: the export write-back was a whole-row
     * `@Update` built from a snapshot read *before* the SAF write. Deleting the note while
     * that export was in flight put `deletedAtEpochMs` back to null — the note the user had
     * just deleted came back.
     */
    @Test
    fun `a note deleted while its export is in flight stays deleted`() = runBlocking {
        val id = seed()
        // The export of THIS note is in flight when the user deletes it; the write-back for
        // it lands afterwards, holding a snapshot taken before the delete existed.
        exporter.onExport = {
            exporter.onExport = null
            repo.delete(id)
        }

        repo.updateNoteContent(id, "Identity keynote", "body")

        assertNotNull(
            "the delete must survive the export that landed on top of it",
            noteDao.rows.getValue(id).deletedAtEpochMs,
        )
    }

    @Test
    fun `an edit made while an export is in flight is not reverted`() = runBlocking {
        val id = seed()
        exporter.onExport = { repo.updateNoteContent(id, "Renamed by hand", "Hand-written body") }

        repo.setShowSources(id, false)
        repo.updateMergedNote(
            id = id,
            title = "Identity keynote",
            segments = emptyList(),
            transcript = emptyList(),
            typedFragments = "",
            durationMs = 60_000,
            createdAtEpochMs = 1_785_000_000_000,
            mergedWithAi = false,
        )

        val after = noteDao.rows.getValue(id)
        assertEquals("Renamed by hand", after.title)
        assertEquals("Hand-written body", after.bodyOverride)
    }

    @Test
    fun `a successful export records both tracked uris`() = runBlocking {
        val id = save()

        val note = noteDao.rows.getValue(id)
        assertEquals("content://export/note-$id.md", note.obsidianFileUri)
        assertEquals("content://export/note-$id.transcript.md", note.transcriptFileUri)
    }

    @Test
    fun `a failed export leaves the note untracked and the note itself intact`() = runBlocking {
        exporter.failWrites = true

        val id = save()

        val note = noteDao.rows.getValue(id)
        assertNull(note.obsidianFileUri)
        assertEquals("Identity keynote", note.title)
    }

    @Test
    fun `an export that produced no transcript keeps the previously tracked one`() = runBlocking {
        val id = seed(transcriptUri = "content://export/old.transcript.md")
        exporter.transcriptUri = null

        repo.updateNoteContent(id, "Identity keynote", "edited")

        assertEquals(
            "dropping the reference would orphan the file it points at",
            "content://export/old.transcript.md",
            noteDao.rows.getValue(id).transcriptFileUri,
        )
    }

    // ── Repairing the backlog must not recurse into itself ──────────────────

    /**
     * The second REL-14 defect. Every successful export opportunistically retried the whole
     * unexported backlog, and `exportMissing` didn't hold the guard against that — so the
     * first note's success exported all the others, and then the outer loop walked its own
     * stale list and exported each of them again. N notes cost ~2N writes.
     */
    @Test
    fun `repairing the backlog exports each note exactly once`() = runBlocking {
        repeat(5) { seed(title = "Talk $it") }

        val result = repo.exportMissing()

        assertEquals(5, exporter.exportCount)
        assertEquals(5, result.exported)
        assertEquals(0, result.failures)
    }

    @Test
    fun `a successful export still opportunistically repairs the rest`() = runBlocking {
        val stragglers = List(3) { seed(title = "Missed $it") }

        save()

        assertTrue(
            "a working export location is exactly when a stale failure should self-heal",
            stragglers.all { noteDao.rows.getValue(it).obsidianFileUri != null },
        )
    }

    @Test
    fun `repair reports the notes that still could not be written`() = runBlocking {
        repeat(2) { seed(title = "Talk $it") }
        exporter.failWrites = true

        val result = repo.exportMissing()

        assertEquals(0, result.exported)
        assertEquals(2, result.failures)
        assertEquals(
            "Couldn't export 2 notes — check the folder is still available",
            result.summary(),
        )
    }

    @Test
    fun `repair does nothing at all when no export location is configured`() = runBlocking {
        repeat(3) { seed(title = "Talk $it") }
        exporter.configured = false

        val result = repo.exportMissing()

        assertEquals(0, exporter.exportCount)
        assertEquals(ExportRepairResult(0, 0), result)
    }

    // ── What counts as "not backed up" ──────────────────────────────────────

    /**
     * A note whose summary exported but whose companion `.transcript.md` did not used to be
     * invisible to OBS-04 — not counted, and never repaired. It read as backed up while the
     * verbatim record, the half that cannot be regenerated, was missing.
     */
    @Test
    fun `a note whose transcript file never landed counts as unexported`() = runBlocking {
        seed(noteUri = "content://export/note.md", transcriptUri = null)

        assertEquals(1, noteDao.unexportedCount())
        assertEquals(1, noteDao.getUnexported().size)
    }

    @Test
    fun `a typed-only note with no transcript is fully exported once its note file lands`() = runBlocking {
        seed(transcript = emptyList(), noteUri = "content://export/note.md", transcriptUri = null)

        assertEquals(
            "a note with nothing to transcribe must not be reported as missing a transcript",
            0,
            noteDao.unexportedCount(),
        )
    }

    @Test
    fun `a deleted note is never counted as unexported`() = runBlocking {
        seed(deletedAt = 1_785_000_500_000)

        assertEquals(0, noteDao.unexportedCount())
    }

    // ── Delete, restore, purge ──────────────────────────────────────────────

    @Test
    fun `delete is soft, and takes both exported files with it`() = runBlocking {
        val id = save()

        val result = repo.delete(id)

        assertTrue(result.filesDeleted)
        assertNotNull(noteDao.rows.getValue(id).deletedAtEpochMs)
        assertEquals(
            listOf("content://export/note-$id.md", "content://export/note-$id.transcript.md"),
            exporter.deleted,
        )
    }

    @Test
    fun `a file delete failure is reported without blocking the local delete`() = runBlocking {
        val id = save()
        exporter.failDeletes = true

        val result = repo.delete(id)

        assertFalse(result.filesDeleted)
        assertNotNull(
            "the user's delete is the user's decision — a stale SAF grant cannot veto it",
            noteDao.rows.getValue(id).deletedAtEpochMs,
        )
    }

    /**
     * The delete removed both files, so a restore has to rewrite both. `transcriptFileUri`
     * used to be left set, pointing at a document that had been deleted — and if the provider
     * recycled that id, the next export wrote this note's transcript over an unrelated file.
     */
    @Test
    fun `restore brings the note back and re-exports it fresh`() = runBlocking {
        val id = save()
        repo.delete(id)
        exporter.noteUriOverride = "content://export/restored.md"
        exporter.transcriptUriOverride = "content://export/restored.transcript.md"

        repo.restore(id)

        val note = noteDao.rows.getValue(id)
        assertNull(note.deletedAtEpochMs)
        assertEquals("content://export/restored.md", note.obsidianFileUri)
        assertEquals("content://export/restored.transcript.md", note.transcriptFileUri)
    }

    /**
     * The restore has to clear both URIs *itself*, not rely on the re-export overwriting
     * them — because the re-export is the step allowed to fail. With the export location
     * unreachable, a stale `transcriptFileUri` (the one REL-14 found being left behind)
     * survives as a live pointer to a document the delete already removed, and the next
     * successful export hands it straight to the writer.
     */
    @Test
    fun `a restore whose export fails leaves no pointer to a deleted file`() = runBlocking {
        val id = save()
        repo.delete(id)
        exporter.failWrites = true

        repo.restore(id)

        val note = noteDao.rows.getValue(id)
        assertNull(note.deletedAtEpochMs)
        assertNull("the summary file was deleted with the note", note.obsidianFileUri)
        assertNull("so was the companion transcript", note.transcriptFileUri)
    }

    @Test
    fun `deleting forever retries a file the soft delete could not remove`() = runBlocking {
        val id = save()
        exporter.failDeletes = true
        assertFalse(repo.delete(id).filesDeleted)
        exporter.deleted.clear()
        exporter.failDeletes = false

        repo.deleteForever(id)

        assertEquals(
            "the row was the last thing pointing at those files",
            listOf("content://export/note-$id.md", "content://export/note-$id.transcript.md"),
            exporter.deleted,
        )
        assertFalse(id in noteDao.rows)
    }

    @Test
    fun `deleting forever takes the note's chat history with it`() = runBlocking {
        val id = save()
        repo.addChatMessage(id, "user", "what were the action items?")

        repo.deleteForever(id)

        assertTrue(chatDao.rows.none { it.noteId == id })
    }

    @Test
    fun `the purge removes only notes past the recovery window`() = runBlocking {
        val now = System.currentTimeMillis()
        val stale = seed(title = "Old", deletedAt = now - RecentlyDeleted.RECOVERY_WINDOW_MS - 1)
        val fresh = seed(title = "Recent", deletedAt = now - 60_000)
        val live = seed(title = "Live")

        repo.purgeExpiredDeleted()

        assertFalse("past the window", stale in noteDao.rows)
        assertTrue("still restorable", fresh in noteDao.rows)
        assertTrue("never deleted", live in noteDao.rows)
    }

    // ── Migration to a new export location ──────────────────────────────────

    @Test
    fun `migrating re-homes every tracked note and removes the old files`() = runBlocking {
        val id = seed(
            noteUri = "content://old/note.md",
            transcriptUri = "content://old/note.transcript.md",
        )
        exporter.noteUriOverride = "content://new/note.md"
        exporter.transcriptUriOverride = "content://new/note.transcript.md"

        val result = repo.migrateExports()

        assertEquals(ExportMigrationResult(moved = 1, writeFailures = 0, removeFailures = 0), result)
        assertEquals("content://new/note.md", noteDao.rows.getValue(id).obsidianFileUri)
        assertEquals(
            listOf("content://old/note.md", "content://old/note.transcript.md"),
            exporter.deleted,
        )
    }

    @Test
    fun `a note that cannot be written to the new location keeps its old uri`() = runBlocking {
        val id = seed(noteUri = "content://old/note.md")
        exporter.failWrites = true

        val result = repo.migrateExports()

        assertEquals(1, result.writeFailures)
        assertEquals(
            "abandoning the old URI would orphan the only exported copy",
            "content://old/note.md",
            noteDao.rows.getValue(id).obsidianFileUri,
        )
    }

    @Test
    fun `one unwritable note never aborts the migration of the rest`() = runBlocking {
        val bad = seed(title = "Unwritable", noteUri = "content://old/bad.md")
        val good = seed(title = "Fine", noteUri = "content://old/good.md")
        exporter.failWritesFor = { it.id == bad }

        val result = repo.migrateExports()

        assertEquals(1, result.moved)
        assertEquals(1, result.writeFailures)
        assertTrue(noteDao.rows.getValue(good).obsidianFileUri!!.startsWith("content://export/"))
    }
}

// ── Fakes ───────────────────────────────────────────────────────────────────

/**
 * An in-memory [ExportSink]. [onExport] is the interesting knob: it runs *during* an export,
 * which is how the lost-update tests reproduce a user action landing in the window between the
 * repository reading a note and writing its export URIs back.
 */
private class FakeExportSink : ExportSink {
    var configured = true
    var failWrites = false
    var failDeletes = false
    var failWritesFor: ((NoteEntity) -> Boolean)? = null
    var noteUriOverride: String? = null
    var transcriptUriOverride: String? = null
    var transcriptUri: String? = "unset"
    var exportCount = 0
    val deleted = mutableListOf<String>()
    var onExport: (suspend () -> Unit)? = null

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>>,
        selectedPhotoUris: List<String>,
    ): ExportedFiles? {
        exportCount++
        onExport?.invoke()
        if (failWrites || failWritesFor?.invoke(note) == true) return null
        val transcript = when {
            transcriptUriOverride != null -> transcriptUriOverride
            transcriptUri != "unset" -> transcriptUri
            note.transcript.any { it.text.isNotBlank() } ->
                "content://export/note-${note.id}.transcript.md"
            else -> null
        }
        return ExportedFiles(
            note = noteUriOverride ?: "content://export/note-${note.id}.md",
            transcript = transcript,
        )
    }

    override fun deleteExported(uriStr: String): Boolean {
        if (failDeletes) return false
        deleted += uriStr
        return true
    }
}

/**
 * In-memory [NoteDao]. The queries are hand-written rather than generated, so
 * [unexportedCount] deliberately restates the SQL predicate in Kotlin — if the two ever
 * disagree the SQL is what ships, which is why the migration walk in `NoteDaoQueryTest`
 * territory stays a device concern and this only pins the *rule*.
 */
private class FakeNoteDao : NoteDao {
    val rows = linkedMapOf<Long, NoteEntity>()
    private val changes = MutableStateFlow(0)
    private var nextId = 1L

    fun seed(note: NoteEntity): Long = runBlockingInsert(note)

    private fun runBlockingInsert(note: NoteEntity): Long {
        val id = nextId++
        rows[id] = note.copy(id = id)
        changes.value++
        return id
    }

    fun unexportedCount(): Int = rows.values.count {
        it.deletedAtEpochMs == null &&
            (it.obsidianFileUri == null || (it.transcriptFileUri == null && it.transcript.isNotEmpty()))
    }

    override fun observeAll(): Flow<List<NoteEntity>> =
        changes.map { rows.values.filter { n -> n.deletedAtEpochMs == null } }

    override fun observeById(id: Long): Flow<NoteEntity?> = changes.map { rows[id] }

    override suspend fun getById(id: Long): NoteEntity? = rows[id]

    override suspend fun getAll(): List<NoteEntity> =
        rows.values.filter { it.deletedAtEpochMs == null }

    override fun observeDeleted(): Flow<List<NoteEntity>> =
        changes.map { rows.values.filter { n -> n.deletedAtEpochMs != null } }

    override fun observeUnexportedCount(): Flow<Int> = changes.map { unexportedCount() }

    override suspend fun getUnexported(): List<NoteEntity> = rows.values.filter {
        it.deletedAtEpochMs == null &&
            (it.obsidianFileUri == null || (it.transcriptFileUri == null && it.transcript.isNotEmpty()))
    }

    override suspend fun setExportUris(id: Long, noteUri: String?, transcriptUri: String?) {
        rows[id] = rows[id]?.copy(obsidianFileUri = noteUri, transcriptFileUri = transcriptUri)
            ?: return
        changes.value++
    }

    override suspend fun setExportedPhotoUris(id: Long, photoUrisJson: String?) {
        rows[id] = rows[id]?.copy(exportedPhotoUrisJson = photoUrisJson) ?: return
        changes.value++
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows[id] = rows[id]?.copy(deletedAtEpochMs = deletedAt) ?: return
        changes.value++
    }

    override suspend fun restore(id: Long) {
        rows[id] = rows[id]?.copy(
            deletedAtEpochMs = null,
            obsidianFileUri = null,
            transcriptFileUri = null,
        ) ?: return
        changes.value++
    }

    override suspend fun getDeletedBefore(cutoff: Long): List<NoteEntity> =
        rows.values.filter { it.deletedAtEpochMs != null && it.deletedAtEpochMs!! < cutoff }

    override suspend fun insert(note: NoteEntity): Long = runBlockingInsert(note)

    override suspend fun update(note: NoteEntity) {
        if (note.id !in rows) return
        rows[note.id] = note
        changes.value++
    }

    override suspend fun setShowSources(id: Long, show: Boolean) {
        rows[id] = rows[id]?.copy(showSources = show) ?: return
        changes.value++
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        changes.value++
    }
}

private class FakeChatDao : ChatDao {
    val rows = mutableListOf<ChatMessageEntity>()
    private var nextId = 1L

    override fun observeForNote(noteId: Long): Flow<List<ChatMessageEntity>> =
        MutableStateFlow(rows.filter { it.noteId == noteId })

    override suspend fun insert(message: ChatMessageEntity): Long {
        val id = nextId++
        rows += message.copy(id = id)
        return id
    }

    override suspend fun getRecipeOutputs(noteId: Long): List<ChatMessageEntity> =
        rows.filter { it.noteId == noteId && it.recipeName != null }

    override suspend fun deleteForNote(noteId: Long) {
        rows.removeAll { it.noteId == noteId }
    }
}
