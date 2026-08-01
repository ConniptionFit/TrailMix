package com.trailmix.app.data.db

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.trailmix.app.data.ai.NoteTitle
import com.trailmix.app.data.export.NoteExporter
import com.trailmix.app.data.export.NoteMarkdown
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.moveSection
import com.trailmix.app.data.model.TranscriptLine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

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

@Singleton
class NotesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val chatDao: ChatDao,
    private val noteExporter: NoteExporter,
) {
    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

    fun observeChat(noteId: Long): Flow<List<ChatMessageEntity>> = chatDao.observeForNote(noteId)

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
            ),
        )
        exportIfConfigured(noteDao.getById(id)!!)
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

    suspend fun setShowSources(id: Long, show: Boolean) = noteDao.setShowSources(id, show)

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
        note.obsidianFileUri?.let { if (!deleteDocument(it)) filesOk = false }
        // OBS-02: the companion transcript file is part of the note, so it goes too —
        // leaving it behind would strand an orphan .transcript.md in the export folder.
        note.transcriptFileUri?.let { if (!deleteDocument(it)) filesOk = false }
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

    /** Immediate, unrecoverable removal — the "Delete now" action in Recently deleted
     * and the purge path. The export file is already gone (removed at soft-delete time). */
    suspend fun deleteForever(id: Long) {
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
     * OBS-04: how many live notes have no exported file behind them.
     *
     * Only meaningful once an export location is configured — with none set, nothing is
     * expected to be exported and every note counts. Callers gate on the location.
     */
    fun observeUnexportedCount(): Flow<Int> = noteDao.observeUnexportedCount()

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
        if (!noteExporter.isConfigured()) return ExportRepairResult(0, 0)
        var exported = 0
        var failures = 0
        noteDao.getUnexported().forEach { note ->
            val before = note.obsidianFileUri
            exportIfConfigured(note)
            if (noteDao.getById(note.id)?.obsidianFileUri != before) exported++ else failures++
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
                noteExporter.exportNote(
                    note.copy(obsidianFileUri = null, transcriptFileUri = null),
                    outputs,
                )
            }.getOrNull()
            if (written == null) {
                writeFailures++
                return@forEach
            }
            noteDao.update(
                note.copy(
                    obsidianFileUri = written.note.toString(),
                    transcriptFileUri = written.transcript?.toString(),
                ),
            )
            moved++
            if (written.note.toString() != oldUri && !deleteDocument(oldUri)) removeFailures++
            // OBS-02: the transcript companion migrates with its note.
            if (oldTranscriptUri != null && written.transcript?.toString() != oldTranscriptUri &&
                !deleteDocument(oldTranscriptUri)
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

    private fun deleteDocument(uriStr: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uriStr))
    }.getOrDefault(false)

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
        val written = runCatching { noteExporter.exportNote(note, outputs) }.getOrNull() ?: return
        noteDao.update(
            note.copy(
                obsidianFileUri = written.note.toString(),
                // OBS-02: keep any previously-tracked transcript URI if this export didn't
                // produce one (e.g. a note whose transcript write failed) rather than
                // dropping the reference and orphaning the file.
                transcriptFileUri = written.transcript?.toString() ?: note.transcriptFileUri,
            ),
        )
        retryMissingExports()
    }
}

/**
 * OBS-04 outcome of a repair pass. [failures] is the honest half: notes that are still not
 * backed up after trying, which is what the user actually needs told.
 */
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
): String = NoteMarkdown.buildNote(markdownSource(recipeOutputs, noteLinkBase, transcriptLinkBase))

/** The verbatim transcript as its own standalone document (OBS-02). */
fun NoteEntity.toTranscriptMarkdown(
    noteLinkBase: String? = null,
    transcriptLinkBase: String? = null,
): String = NoteMarkdown.buildTranscript(markdownSource(emptyList(), noteLinkBase, transcriptLinkBase))

private fun NoteEntity.markdownSource(
    recipeOutputs: List<Pair<String, String>>,
    noteLinkBase: String? = null,
    transcriptLinkBase: String? = null,
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
)
