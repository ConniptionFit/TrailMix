package com.trailmix.app.data.db

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.trailmix.app.data.export.NoteExporter
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.moveSection
import com.trailmix.app.data.model.TranscriptLine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            title = title,
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
            val outputs = latestRecipeOutputs(note.id)
            // Clearing the tracked URI forces a find-or-create in the new folder.
            val newUri = runCatching {
                noteExporter.exportNote(note.copy(obsidianFileUri = null), outputs)
            }.getOrNull()
            if (newUri == null) {
                writeFailures++
                return@forEach
            }
            noteDao.update(note.copy(obsidianFileUri = newUri.toString()))
            moved++
            if (newUri.toString() != oldUri && !deleteDocument(oldUri)) removeFailures++
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
    private suspend fun exportIfConfigured(note: NoteEntity) {
        val outputs = latestRecipeOutputs(note.id)
        val exportUri = runCatching { noteExporter.exportNote(note, outputs) }.getOrNull()
        if (exportUri != null) {
            noteDao.update(note.copy(obsidianFileUri = exportUri.toString()))
        }
    }
}

/**
 * Renders the note as Markdown. [recipeOutputs] (OBS-01) — the latest output per saved
 * recipe as name→text pairs — is appended as a "Recipe Outputs" section before the raw
 * transcript appendix; the ad-hoc Share flow passes none, keeping shares note-only.
 */
fun NoteEntity.toMarkdown(recipeOutputs: List<Pair<String, String>> = emptyList()): String = buildString {
    appendLine("# $title")
    appendLine()

    // Metadata header block (UX-02): date / meeting title / attendees, composes with CAL-02.
    val metaLines = buildList {
        add("- **Date:** ${SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(createdAtEpochMs))}")
        meetingTitle?.let { add("- **Meeting:** $it") }
        if (attendees.isNotEmpty()) add("- **Attendees:** ${attendees.joinToString(", ")}")
    }
    if (metaLines.isNotEmpty()) {
        metaLines.forEach { appendLine(it) }
        appendLine()
    }

    val override = bodyOverride
    val summary = structuredSummary
    when {
        override != null -> appendLine(override.trim())
        summary != null -> {
            // AI-05: the exported file is the copy the user actually reads weeks later, so
            // provenance travels with it instead of living only as on-screen tinting.
            // `showSources` (previously inert for structured notes) is the opt-out.
            if (showSources && summary.hasAnnotations) {
                appendLine(SOURCE_LEGEND)
                appendLine()
            }
            appendStructuredSummary(summary, annotate = showSources)
        }
        else -> segments.forEach { appendLine(it.text.trim()) }
    }

    if (recipeOutputs.isNotEmpty()) {
        appendLine()
        appendLine("## Recipe Outputs")
        recipeOutputs.forEach { (name, text) ->
            appendLine()
            appendLine("### $name")
            appendLine(text.trim())
        }
    }

    val lines = transcript
    if (lines.isNotEmpty()) {
        appendLine()
        appendLine("## Transcript")
        lines.forEach { appendLine("- **${it.label}** ${it.text.trim()}") }
    }
}.trim()

/**
 * One-line key for the `[you]` / `[mm:ss]` markers, emitted above an annotated body so the
 * exported file explains itself without the app.
 */
private const val SOURCE_LEGEND =
    "> **Sources:** `[you]` = your typed note · `[mm:ss]` = spoken, at that point in the recording"

/** True when anything in the summary can actually carry a marker worth explaining. */
private val com.trailmix.app.data.model.StructuredSummary.hasAnnotations: Boolean
    get() = highlights.isNotEmpty() || sections.any { it.bullets.isNotEmpty() } || actionItems.isNotEmpty()

/**
 * Provenance marker for one bullet (AI-05): the capture offset when it was spoken, `[you]`
 * when it came from the user's own typed fragments, `[transcript]` when it was heard but
 * couldn't be traced to a specific moment.
 */
private fun sourceTag(source: Provenance, timestampLabel: String?): String = when {
    source == Provenance.FRAGMENT -> "**`[you]`** "
    timestampLabel != null -> "**`[$timestampLabel]`** "
    else -> "**`[transcript]`** "
}

private fun StringBuilder.appendStructuredSummary(
    summary: com.trailmix.app.data.model.StructuredSummary,
    annotate: Boolean,
) {
    fun tag(source: Provenance, label: String?) = if (annotate) sourceTag(source, label) else ""

    if (summary.highlights.isNotEmpty()) {
        appendLine("## Highlights")
        summary.highlights.forEach {
            appendLine("- ${tag(it.source, it.timestampLabel)}${it.text.trim()}")
        }
        appendLine()
    }
    summary.sections.forEach { section ->
        appendLine("## ${section.heading}")
        section.bullets.forEach {
            appendLine("- ${tag(it.source, it.timestampLabel)}${it.text.trim()}")
        }
        appendLine()
    }
    if (summary.actionItems.isNotEmpty()) {
        appendLine("## Action Items")
        summary.actionItems.forEach { item ->
            val suffix = buildString {
                item.owner?.let { append(" — $it") }
                item.deadline?.let { append(" (due $it)") }
            }
            appendLine("- [ ] ${tag(item.source, item.timestampLabel)}${item.text.trim()}$suffix")
        }
    }
}
