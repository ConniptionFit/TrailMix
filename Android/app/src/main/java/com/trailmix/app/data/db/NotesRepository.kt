package com.trailmix.app.data.db

import com.trailmix.app.data.ai.NoteTitle
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.export.ExportSink
import com.trailmix.app.data.export.ExportedPhoto
import com.trailmix.app.data.export.NoteMarkdown
import com.trailmix.app.data.export.NoteMarkdownImporter
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.model.moveSection
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Result of deleting a note (CAP-05): the local DB delete always happens; [filesDeleted] is
 * false only if the note had a tracked export URI and removing it failed (stale URI, revoked
 * SAF permission, provider error) — fail-soft, surfaced as a non-blocking UI hint, never a
 * reason to abort the local delete. */
data class DeleteResult(val filesDeleted: Boolean)

/**
 * Outcome of the export-location migration (INT-02, v1.7.0). [moved] notes were written to
 * the new folder (tracked URI updated); [writeFailures] couldn't be written to the new
 * location (their tracked URI is left pointing at the old file, untouched);
 * [removeFailures] were written to the new location but the old copy couldn't be deleted
 * (stale/revoked URI) — every case is per-note fail-soft, one bad note never aborts the rest.
 */
data class ExportMigrationResult(val moved: Int, val writeFailures: Int, val removeFailures: Int)

/**
 * The note store, and — the part REL-14 is about — the **cascade** around it: which notes get
 * written to the user's export folder, when a delete takes its files with it, what a restore
 * rewrites, and how a failed export is found again later.
 *
 * That cascade is the app's only real backup story (`allowBackup=false`, local-only, the
 * exported Markdown is the copy that survives a wipe), and until REL-14 it had **no tests at
 * all** — the same shape as REL-13, where the pure half was covered and the half that actually
 * deletes and overwrites things was not. Its collaborators are therefore interfaces
 * ([ExportSink], the two DAOs) so the whole of it runs on plain JVM fakes with no device.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val chatDao: ChatDao,
    private val exportSink: ExportSink,
    private val conversationDao: ConversationDao,
) {
    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

    fun observeChat(noteId: Long): Flow<List<ChatMessageEntity>> = chatDao.observeForNote(noteId)

    /** Cross-note chat (AI-10): the live notes a multi-select chat was launched against. */
    suspend fun getNotesByIds(ids: List<Long>): List<NoteEntity> = noteDao.getByIds(ids)

    fun observeConversation(noteIdsKey: String): Flow<List<ConversationMessageEntity>> =
        conversationDao.observeForNoteIds(noteIdsKey)

    suspend fun addConversationMessage(noteIdsKey: String, role: String, text: String) {
        conversationDao.insert(
            ConversationMessageEntity(
                noteIdsKey = noteIdsKey,
                role = role,
                text = text,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveMergedNote(
        title: String,
        segments: List<NoteSegment>,
        transcript: List<TranscriptLine>,
        typedFragments: String,
        durationMs: Long,
        createdAtEpochMs: Long,
        mergedWithAi: Boolean,
        meetingTitle: String? = null,
        capturedInCall: Boolean = false,
        attendees: List<String> = emptyList(),
        structuredSummary: StructuredSummary? = null,
        template: String? = null,
        flags: List<String> = emptyList(),
    ): Long {
        val id = noteDao.insert(
            NoteEntity(
                title = title,
                segmentsJson = SegmentsJson.encode(segments),
                transcriptJson = TranscriptJson.encode(transcript),
                typedFragments = typedFragments,
                durationMs = durationMs,
                createdAtEpochMs = createdAtEpochMs,
                showSources = true,
                mergedWithAi = mergedWithAi,
                meetingTitle = meetingTitle,
                capturedInCall = capturedInCall,
                attendeesJson = attendees.takeIf { it.isNotEmpty() }?.let { StringListJson.encode(it) },
                summaryJson = structuredSummary?.let { StructuredSummaryJson.encode(it) },
                template = template,
                flaggedLabelsJson = flags.takeIf { it.isNotEmpty() }?.let { StringListJson.encode(it) },
            ),
        )
        // REL-14: was `getById(id)!!`. Room hands back the row it just inserted in every
        // ordinary case, but a bare `!!` inside the merge path is a crash where a skipped
        // export would do — and REL-10 spent a release making sure a merge cannot take the
        // app down with it. The note is already saved either way; only its export is at stake.
        noteDao.getById(id)?.let { exportIfConfigured(it) }
        return id
    }

    /**
     * Re-merge into an existing note (resume capture). Replaces the merged body,
     * transcript, and typed fragments with the freshly accumulated set, keeps the
     * note's original id/creation date, and clears any hand-edited [bodyOverride]
     * since the merged body has been regenerated.
     */
    suspend fun updateMergedNote(
        id: Long,
        title: String,
        segments: List<NoteSegment>,
        transcript: List<TranscriptLine>,
        typedFragments: String,
        durationMs: Long,
        createdAtEpochMs: Long,
        mergedWithAi: Boolean,
        meetingTitle: String? = null,
        capturedInCall: Boolean = false,
        attendees: List<String> = emptyList(),
        structuredSummary: StructuredSummary? = null,
        template: String? = null,
        flags: List<String> = emptyList(),
    ) {
        val existing = noteDao.getById(id) ?: return
        val updated = existing.copy(
            // AI-06: never let a re-merge downgrade a real title back to the auto-generated
            // placeholder. The deterministic fallback can't name a note, so on any AI failure
            // it emits `Note — <time>`; without this the note would visibly lose its name, and
            // the exported filenames would desynchronise from the wiki-links inside them
            // (OBS-03). A genuine re-title still wins — this only blocks default-over-real.
            title = NoteTitle.preferExisting(incoming = title, existing = existing.title),
            segmentsJson = SegmentsJson.encode(segments),
            transcriptJson = TranscriptJson.encode(transcript),
            typedFragments = typedFragments,
            durationMs = durationMs,
            createdAtEpochMs = createdAtEpochMs,
            showSources = true,
            mergedWithAi = mergedWithAi,
            meetingTitle = meetingTitle ?: existing.meetingTitle,
            capturedInCall = capturedInCall || existing.capturedInCall,
            bodyOverride = null,
            attendeesJson = attendees.takeIf { it.isNotEmpty() }?.let { StringListJson.encode(it) }
                ?: existing.attendeesJson,
            summaryJson = structuredSummary?.let { StructuredSummaryJson.encode(it) },
            template = template ?: existing.template,
            // CAP-24: a resume's freshly-flagged moments replace the prior set, same "new
            // merge replaces old value" rule as transcript/segments — flags from before a
            // resume are still in the transcript's time range and were already offered once.
            flaggedLabelsJson = flags.takeIf { it.isNotEmpty() }?.let { StringListJson.encode(it) }
                ?: existing.flaggedLabelsJson,
        )
        noteDao.update(updated)
        exportIfConfigured(updated)
    }

    /** Persist a hand-edited title/body (UX-01). Sets [bodyOverride]; provenance no longer applies. */
    suspend fun updateNoteContent(id: Long, title: String, body: String) {
        val existing = noteDao.getById(id) ?: return
        val updated = existing.copy(
            title = title.ifBlank { existing.title },
            bodyOverride = body,
        )
        noteDao.update(updated)
        exportIfConfigured(updated)
    }

    /**
     * UX-22: correct one ASR mistake in a saved transcript, in place — the same "fetch,
     * decode, mutate, re-encode, update, re-export" shape as [updateNoteContent], scoped to a
     * single line instead of the whole body. Out-of-range [lineIndex] is a no-op rather than
     * throwing: the transcript can't have changed shape under the UI between it rendering a
     * row and the user saving an edit to it, but failing soft costs nothing if it ever did.
     */
    suspend fun updateTranscriptLine(id: Long, lineIndex: Int, newText: String) {
        val existing = noteDao.getById(id) ?: return
        val lines = existing.transcript
        if (lineIndex !in lines.indices) return
        val updatedLines = lines.toMutableList().apply {
            this[lineIndex] = this[lineIndex].copy(text = newText)
        }
        val updated = existing.copy(transcriptJson = TranscriptJson.encode(updatedLines))
        noteDao.update(updated)
        exportIfConfigured(updated)
    }

    suspend fun setShowSources(id: Long, show: Boolean) = noteDao.setShowSources(id, show)

    /**
     * Photo-export feature: persists which MediaStore photos the user selected for this
     * note's export, then re-exports so the change is reflected on disk immediately — the
     * same "persist, then re-export" shape as [updateNoteContent]. An empty [photoUris]
     * clears the tracked selection (the user removed every photo), distinct from
     * [ExportSink.exportNote]'s own empty-means-"reuse tracked" default used by the
     * background repair pass, which never has a selection to pass in the first place.
     */
    suspend fun setSelectedPhotos(id: Long, photoUris: List<String>) {
        val existing = noteDao.getById(id) ?: return
        val json = photoUris.takeIf { it.isNotEmpty() }?.let { StringListJson.encode(it) }
        noteDao.setExportedPhotoUris(id, json)
        exportIfConfigured(existing.copy(exportedPhotoUrisJson = json))
    }

    /**
     * [recipeName] is set only on assistant replies produced by running a saved recipe
     * (OBS-01) — those are durable outputs, so adding one also re-exports the note so the
     * file on disk picks it up immediately.
     */
    suspend fun addChatMessage(noteId: Long, role: String, text: String, recipeName: String? = null) {
        chatDao.insert(
            ChatMessageEntity(
                noteId = noteId,
                role = role,
                text = text,
                createdAtEpochMs = System.currentTimeMillis(),
                recipeName = recipeName,
            ),
        )
        if (recipeName != null) {
            noteDao.getById(noteId)?.let { exportIfConfigured(it) }
        }
    }

    /**
     * UX-04: persist a reorder of the structured summary's topic sections. No-op if the
     * note has no structured summary or the indices don't move anything. The new order is
     * re-exported so the Markdown files match what's on screen.
     */
    suspend fun moveSummarySection(id: Long, from: Int, to: Int) {
        val existing = noteDao.getById(id) ?: return
        val summary = existing.structuredSummary ?: return
        val moved = summary.moveSection(from, to)
        if (moved == summary) return
        val updated = existing.copy(summaryJson = StructuredSummaryJson.encode(moved))
        noteDao.update(updated)
        exportIfConfigured(updated)
    }

    /**
     * Delete a note (CAP-05, reworked by REL-06 in v1.8.0 to be a soft delete): the note
     * moves to Recently deleted for [RecentlyDeleted.RECOVERY_WINDOW_MS] (1 day) instead of
     * vanishing, hidden from every normal surface but restorable. The tracked export file
     * IS removed immediately — a deleted note shouldn't linger in the user's export folder —
     * and a restore re-exports it fresh. Chat history stays until the purge so a restored
     * note keeps its conversation. A failed file delete (stale URI, revoked SAF grant) is
     * reported via [DeleteResult.filesDeleted] rather than aborting, same as before. (Any
     * dormant pre-v1.7.0 Drive copy is deliberately left alone.)
     */
    suspend fun delete(id: Long): DeleteResult {
        val note = noteDao.getById(id) ?: return DeleteResult(filesDeleted = true)
        noteDao.softDelete(id, System.currentTimeMillis())

        var filesOk = true
        note.obsidianFileUri?.let { if (!exportSink.deleteExported(it)) filesOk = false }
        // OBS-02: the companion transcript file is part of the note, so it goes too —
        // leaving it behind would strand an orphan .transcript.md in the export folder.
        note.transcriptFileUri?.let { if (!exportSink.deleteExported(it)) filesOk = false }
        return DeleteResult(filesDeleted = filesOk)
    }

    /** Notes currently in Recently deleted, newest deletion first (REL-06). */
    fun observeDeletedNotes(): Flow<List<NoteEntity>> = noteDao.observeDeleted()

    /**
     * Bring a soft-deleted note back (REL-06). Its export file was removed at delete time,
     * so the restore re-exports it fresh into the configured location (silent no-op when
     * no location is configured, as always).
     */
    suspend fun restore(id: Long) {
        noteDao.restore(id)
        noteDao.getById(id)?.let { exportIfConfigured(it) }
    }

    /**
     * Immediate, unrecoverable removal — the "Delete now" action in Recently deleted and the
     * purge path.
     *
     * The export files are normally already gone, removed at soft-delete time. REL-14: they
     * are retried here anyway, because the one case that matters is the one where that
     * removal *failed* — a stale URI or a revoked SAF grant, reported fail-soft as
     * [DeleteResult.filesDeleted] = false. Deleting the row was the last thing still pointing
     * at those files, so without this retry a transient failure at delete time stranded an
     * orphaned `.md` in the user's export folder permanently, with nothing left that knew it
     * was there. A day in Recently deleted is ample time for the grant to be working again.
     */
    suspend fun deleteForever(id: Long) {
        noteDao.getById(id)?.let { note ->
            note.obsidianFileUri?.let { exportSink.deleteExported(it) }
            note.transcriptFileUri?.let { exportSink.deleteExported(it) }
        }
        chatDao.deleteForNote(id)
        noteDao.deleteById(id)
    }

    /**
     * Hard-delete every soft-deleted note older than the 1-day recovery window (REL-06).
     * Called opportunistically from the Home and Recently deleted screens — no background
     * job needed for a purely local cleanup with day-scale granularity.
     */
    suspend fun purgeExpiredDeleted() {
        val cutoff = System.currentTimeMillis() - RecentlyDeleted.RECOVERY_WINDOW_MS
        noteDao.getDeletedBefore(cutoff).forEach { deleteForever(it.id) }
    }

    /**
     * Recovery feature (2026-09-12): rebuild notes from whatever is already sitting in the
     * export folder — this app's only backup (`allowBackup=false`, local-only). Existing
     * notes are never duplicated: a file whose title+timestamp already matches a live note is
     * skipped. A restored note is linked to the file it came from (both tracked URIs set to
     * the real, existing document) so its next edit updates that file in place instead of
     * writing a new one beside it.
     *
     * Deliberately lossy in the same way [NoteMarkdownImporter] documents: the restored body
     * is the file's own text verbatim, not a re-derived structured/provenance-tagged summary,
     * and any chat/AI conversation that was never run as a named recipe isn't in the export at
     * all. A flatter note that keeps 100% of the user's actual words beats a prettier one that
     * risks silently dropping some.
     */
    suspend fun importFromExportFolder(): ImportResult {
        val files = exportSink.listExportedNotes()
        val existingKeys = noteDao.getAll().map { it.title to it.createdAtEpochMs }.toSet()
        var imported = 0
        var skipped = 0
        var failed = 0
        files.forEach { file ->
            val parsed = NoteMarkdownImporter.parseNote(file.noteMarkdown, file.transcriptMarkdown)
            when {
                parsed == null -> failed++
                (parsed.title to parsed.createdAtEpochMs) in existingKeys -> skipped++
                else -> {
                    noteDao.insert(
                        NoteEntity(
                            title = parsed.title,
                            segmentsJson = SegmentsJson.encode(emptyList()),
                            transcriptJson = TranscriptJson.encode(parsed.transcript),
                            typedFragments = "",
                            durationMs = parsed.durationMs,
                            createdAtEpochMs = parsed.createdAtEpochMs,
                            showSources = false,
                            mergedWithAi = false,
                            meetingTitle = parsed.meetingTitle,
                            bodyOverride = parsed.bodyOverride,
                            attendeesJson = parsed.attendees.takeIf { it.isNotEmpty() }?.let { StringListJson.encode(it) },
                            template = parsed.template,
                            obsidianFileUri = file.noteUri,
                            transcriptFileUri = file.transcriptUri,
                        ),
                    )
                    imported++
                }
            }
        }
        return ImportResult(imported, skipped, failed)
    }

    /**
     * OBS-04: how many live notes have no exported file behind them.
     *
     * Only meaningful once an export location is configured — with none set, nothing is
     * expected to be exported and every note counts. Callers gate on the location.
     */
    fun observeUnexportedCount(): Flow<Int> = noteDao.observeUnexportedCount()

    /**
     * OBS-05: notice when a tracked export file is gone — deleted in a file manager, lost to
     * a sync conflict, or a provider that recycled its document id. Until now a tracked URI
     * was trusted at face value forever; a note could read as backed up while nothing was
     * actually behind it. Clearing the stale half of a URI pair (never both blindly — the
     * other file may still be fine) makes the note reappear in [observeUnexportedCount] and
     * eligible for [exportMissing] to rewrite, the same path a first-ever export failure
     * already uses.
     *
     * Returns how many notes had at least one stale URI cleared. Called once per Settings
     * visit ([com.trailmix.app.ui.settings.SettingsViewModel]) rather than reactively on
     * every DB emission — checking file existence is real SAF I/O per note, not a query.
     */
    suspend fun detectAndClearDeletedExports(): Int {
        if (!exportSink.isConfigured()) return 0
        var cleared = 0
        noteDao.getAll().forEach { note ->
            val noteGone = note.obsidianFileUri?.let { !exportSink.exists(it) } ?: false
            val transcriptGone = note.transcriptFileUri?.let { !exportSink.exists(it) } ?: false
            if (noteGone || transcriptGone) {
                noteDao.setExportUris(
                    id = note.id,
                    noteUri = if (noteGone) null else note.obsidianFileUri,
                    transcriptUri = if (transcriptGone) null else note.transcriptFileUri,
                )
                cleared++
            }
        }
        return cleared
    }

    /**
     * OBS-04: retry every live note that has no exported file.
     *
     * Exports were previously fire-and-forget: [exportIfConfigured] swallowed failures, and
     * [migrateExports] only ever looked at notes that *already* had a tracked URI — so a note
     * whose first export failed was skipped by the repair path too and silently never
     * exported again. On a `allowBackup=false`, local-only app whose exported Markdown is the
     * only copy that survives a wipe, that is the difference between having a backup and
     * believing you do.
     *
     * Fail-soft per note: a single unwritable note never aborts the rest.
     */
    suspend fun exportMissing(): ExportRepairResult {
        if (!exportSink.isConfigured()) return ExportRepairResult(0, 0)
        var exported = 0
        var failures = 0
        // REL-14: hold the same re-entry guard the opportunistic path uses, for the whole
        // pass. Without it every note's successful export triggered `retryMissingExports`,
        // which exported the entire remaining backlog — and then this loop walked its own
        // now-stale list and exported each of them a second time. N unexported notes cost
        // ~2N writes and N full SAF directory scans, behind a button the user is watching.
        // Repairing the backlog *is* the backlog repair; it must not recurse into itself.
        repairingExports = true
        try {
            // OBS-05: a stale tracked URI must be cleared before this scan, or a note whose
            // file quietly vanished stays invisible to the very query meant to find it.
            detectAndClearDeletedExports()
            noteDao.getUnexported().forEach { note ->
                exportIfConfigured(note)
                // Success is "something got written", not "the note file got written" —
                // a note whose summary was already exported can be here purely because its
                // companion transcript is missing, in which case only that URI moves.
                val after = noteDao.getById(note.id)
                val moved = after?.obsidianFileUri != note.obsidianFileUri ||
                    after?.transcriptFileUri != note.transcriptFileUri
                if (moved) exported++ else failures++
            }
        } finally {
            repairingExports = false
        }
        return ExportRepairResult(exported = exported, failures = failures)
    }

    /**
     * Export-location migration (INT-02, v1.7.0): called right after the user picks a NEW
     * export location while notes are still tracked against the old one. For each note with
     * a tracked export file: write it into the new location (a forced fresh write — the
     * tracked URI is ignored so the note lands in the new folder, not updated in place in
     * the old one), update the tracked URI, then delete the old file. Per-note fail-soft:
     * a stale/revoked old URI or an unwritable note never aborts the rest.
     */
    suspend fun migrateExports(): ExportMigrationResult {
        var moved = 0
        var writeFailures = 0
        var removeFailures = 0
        noteDao.getAll().filter { it.obsidianFileUri != null }.forEach { note ->
            val oldUri = note.obsidianFileUri!!
            val oldTranscriptUri = note.transcriptFileUri
            val outputs = latestRecipeOutputs(note.id)
            // Clearing both tracked URIs forces a find-or-create in the new folder.
            val written = runCatching {
                exportSink.exportNote(
                    note.copy(obsidianFileUri = null, transcriptFileUri = null),
                    outputs,
                )
            }.getOrNull()
            if (written == null) {
                writeFailures++
                return@forEach
            }
            // REL-14: same targeted write-back as exportIfConfigured, for the same reason —
            // this loop re-exports every note in the library, so the window between reading a
            // row and writing it back spans a full SAF write per note.
            noteDao.setExportUris(
                id = note.id,
                noteUri = written.note,
                transcriptUri = written.transcript,
            )
            moved++
            if (written.note != oldUri && !exportSink.deleteExported(oldUri)) removeFailures++
            // OBS-02: the transcript companion migrates with its note.
            if (oldTranscriptUri != null && written.transcript != oldTranscriptUri &&
                !exportSink.deleteExported(oldTranscriptUri)
            ) {
                removeFailures++
            }
        }
        return ExportMigrationResult(moved, writeFailures, removeFailures)
    }

    /**
     * The most recent output per recipe for a note (OBS-01), in first-run order — running
     * "Follow-up email" twice exports only the newest draft, not both.
     */
    private suspend fun latestRecipeOutputs(noteId: Long): List<Pair<String, String>> =
        chatDao.getRecipeOutputs(noteId)
            .groupBy { it.recipeName!! }
            .map { (name, messages) -> name to messages.last().text }

    /**
     * Best-effort Markdown export/update-in-place into the configured Export location
     * (INT-02, v1.7.0 — formerly Obsidian vault and/or Google Drive). Automatic on every
     * merge/edit/recipe run; absent config is a silent no-op. A successful export gets its
     * `content://` URI written back onto the note (update-in-place on the next export,
     * cascade-delete, migration) without re-triggering export again.
     */
    /**
     * OBS-04: after a note exports successfully, opportunistically retry anything still
     * missing a file.
     *
     * A successful write is proof the export location is reachable *right now* — grant
     * intact, folder present, storage writable — which is exactly the moment a note that
     * failed during an earlier outage is most likely to succeed. So transient failures
     * self-heal on the next merge without the user ever noticing, and only a persistent
     * problem (a genuinely revoked grant) survives to be reported in Settings.
     *
     * Deliberately only runs after a success: retrying the whole backlog while the location
     * is broken would just burn I/O failing repeatedly. Guarded against re-entry because
     * [exportIfConfigured] is what calls it.
     */
    private var repairingExports = false

    private suspend fun retryMissingExports() {
        if (repairingExports) return
        repairingExports = true
        try {
            noteDao.getUnexported().forEach { exportIfConfigured(it) }
        } finally {
            repairingExports = false
        }
    }

    private suspend fun exportIfConfigured(note: NoteEntity) {
        val outputs = latestRecipeOutputs(note.id)
        val written = runCatching { exportSink.exportNote(note, outputs) }.getOrNull() ?: return
        // REL-14: write back the two URIs by id, NOT `noteDao.update(note.copy(…))`.
        //
        // `note` was read before the export, and the export is slow SAF I/O — so a whole-row
        // update here rewrote every column from a stale snapshot and silently reverted
        // anything the user did in between. The worst shape was a delete: `deletedAtEpochMs`
        // went back to null and the note the user had just deleted reappeared. An edit
        // (`updateNoteContent`) or a chat message landing in the same window was lost the
        // same way. A two-column update addressed by id cannot revert what it doesn't name.
        noteDao.setExportUris(
            id = note.id,
            noteUri = written.note,
            // OBS-02: keep any previously-tracked transcript URI if this export didn't
            // produce one (e.g. a note whose transcript write failed) rather than
            // dropping the reference and orphaning the file.
            transcriptUri = written.transcript ?: note.transcriptFileUri,
        )
        retryMissingExports()
    }
}

/**
 * OBS-04 outcome of a repair pass. [failures] is the honest half: notes that are still not
 * backed up after trying, which is what the user actually needs told.
 */
/** Recovery feature (2026-09-12): outcome of [NotesRepository.importFromExportFolder]. */
data class ImportResult(val imported: Int, val skipped: Int, val failed: Int) {
    fun summary(): String {
        val noun = { n: Int -> "$n note${if (n == 1) "" else "s"}" }
        return when {
            imported == 0 && skipped == 0 && failed == 0 -> "No notes found in the export folder"
            imported == 0 && failed == 0 -> "Nothing new to restore — already have ${noun(skipped)}"
            failed == 0 -> "Restored ${noun(imported)}" + if (skipped > 0) " (${noun(skipped)} already present)" else ""
            else -> "Restored ${noun(imported)}; ${noun(failed)} couldn't be read"
        }
    }
}

data class ExportRepairResult(val exported: Int, val failures: Int) {
    /**
     * User-facing summary. Pure so the wording can be unit-tested — this is the message that
     * decides whether someone believes their notes are safe, and the failure count must never
     * be rounded away by a cheerful partial success.
     */
    fun summary(): String {
        val noun = { n: Int -> "$n note${if (n == 1) "" else "s"}" }
        return when {
            exported == 0 && failures == 0 -> "Nothing to export"
            failures == 0 -> "Exported ${noun(exported)}"
            exported == 0 ->
                "Couldn't export ${noun(failures)} — check the folder is still available"
            else -> "Exported ${noun(exported)}; ${noun(failures)} still couldn't be written"
        }
    }
}

/**
 * Renders the note as the exported/shared Markdown summary (OBS-02, v1.11.0).
 *
 * The raw transcript is **no longer appended here** — it lives in a companion
 * `<name>.transcript.md` linked from the frontmatter and the footer. That split is the whole
 * point of the format: this file is meant to be readable on a phone and pasteable into
 * another model as context, and a 45-minute transcript is tens of thousands of characters
 * that would crowd out everything worth reading. Use [toTranscriptMarkdown] for the verbatim
 * record.
 *
 * [recipeOutputs] (OBS-01) — latest output per saved recipe — is rendered before the footer;
 * the ad-hoc Share flow passes none, keeping shares note-only.
 */
fun NoteEntity.toMarkdown(
    recipeOutputs: List<Pair<String, String>> = emptyList(),
    noteLinkBase: String? = null,
    transcriptLinkBase: String? = null,
    format: ExportFormat = ExportFormat.LLM_OPTIMIZED,
    photos: List<ExportedPhoto> = emptyList(),
): String = NoteMarkdown.buildNote(markdownSource(recipeOutputs, noteLinkBase, transcriptLinkBase, photos), format)

/** The verbatim transcript as its own standalone document (OBS-02). */
fun NoteEntity.toTranscriptMarkdown(
    noteLinkBase: String? = null,
    transcriptLinkBase: String? = null,
    format: ExportFormat = ExportFormat.LLM_OPTIMIZED,
): String = NoteMarkdown.buildTranscript(markdownSource(emptyList(), noteLinkBase, transcriptLinkBase), format)

private fun NoteEntity.markdownSource(
    recipeOutputs: List<Pair<String, String>>,
    noteLinkBase: String? = null,
    transcriptLinkBase: String? = null,
    photos: List<ExportedPhoto> = emptyList(),
) = NoteMarkdown.Source(
    title = title,
    createdAtEpochMs = createdAtEpochMs,
    durationMs = durationMs,
    meetingTitle = meetingTitle,
    attendees = attendees,
    template = template,
    capturedInCall = capturedInCall,
    summary = structuredSummary,
    bodyOverride = bodyOverride,
    flatBody = segments.joinToString(" ") { it.text },
    transcript = transcript,
    recipeOutputs = recipeOutputs,
    showSources = showSources,
    noteLinkBase = noteLinkBase,
    transcriptLinkBase = transcriptLinkBase,
    photos = photos,
)
