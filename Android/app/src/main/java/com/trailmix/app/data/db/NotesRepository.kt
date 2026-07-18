package com.trailmix.app.data.db

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.trailmix.app.data.drive.DriveExporter
import com.trailmix.app.data.export.ExportTarget
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.moveSection
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.obsidian.ObsidianExporter
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

@Singleton
class NotesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val chatDao: ChatDao,
    private val obsidianExporter: ObsidianExporter,
    private val driveExporter: DriveExporter,
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
     * (OBS-01) — those are durable outputs, so adding one also re-exports the note into
     * every configured target so the file on disk picks it up immediately.
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
     * Delete a note locally and cascade to any tracked export files (CAP-05: long-press
     * Delete on Home). The local DB delete always completes; a failed file delete (stale
     * URI, revoked SAF grant, file already removed externally) is caught and reported back
     * via [DeleteResult.filesDeleted] rather than aborting or crashing.
     */
    suspend fun delete(id: Long): DeleteResult {
        val note = noteDao.getById(id)
        chatDao.deleteForNote(id)
        noteDao.deleteById(id)

        var filesOk = true
        note?.obsidianFileUri?.let { if (!deleteDocument(it)) filesOk = false }
        note?.driveFileUri?.let { if (!deleteDocument(it)) filesOk = false }
        return DeleteResult(filesDeleted = filesOk)
    }

    /**
     * "Move" (CAP-05 Part 1): re-export a single note to a freshly SAF-picked folder for
     * [target], replacing whatever URI was previously tracked for that target. Independent
     * of the standing Obsidian vault / Drive folder settings.
     */
    suspend fun moveExport(noteId: Long, target: ExportTarget, treeUri: Uri): Boolean {
        val note = noteDao.getById(noteId) ?: return false
        val outputs = latestRecipeOutputs(noteId)
        val uri = when (target) {
            ExportTarget.OBSIDIAN -> obsidianExporter.exportNoteToPickedFolder(treeUri, note, outputs)
            ExportTarget.DRIVE -> driveExporter.exportNoteToPickedFolder(treeUri, note, outputs)
        } ?: return false
        val updated = when (target) {
            ExportTarget.OBSIDIAN -> note.copy(obsidianFileUri = uri.toString())
            ExportTarget.DRIVE -> note.copy(driveFileUri = uri.toString())
        }
        noteDao.update(updated)
        return true
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
     * Best-effort Markdown export/update-in-place into every configured target (Obsidian
     * vault and/or Google Drive folder — INT-01, v1.5.0). Same automatic-on-merge/edit
     * trigger for both, by design, so the two export surfaces behave consistently. Absent
     * config for a target is a silent no-op for that target; a write failure for one target
     * never blocks the other. Successful exports get their `content://` URI written back
     * onto the note (update-in-place on the next export, cascade-delete, "Open file
     * location") without re-triggering export again.
     */
    private suspend fun exportIfConfigured(note: NoteEntity) {
        val outputs = latestRecipeOutputs(note.id)
        val obsidianUri = runCatching { obsidianExporter.exportNote(note, outputs) }.getOrNull()
        val driveUri = runCatching { driveExporter.exportNote(note, outputs) }.getOrNull()
        if (obsidianUri != null || driveUri != null) {
            noteDao.update(
                note.copy(
                    obsidianFileUri = obsidianUri?.toString() ?: note.obsidianFileUri,
                    driveFileUri = driveUri?.toString() ?: note.driveFileUri,
                ),
            )
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
        summary != null -> appendStructuredSummary(summary)
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

private fun StringBuilder.appendStructuredSummary(summary: com.trailmix.app.data.model.StructuredSummary) {
    if (summary.highlights.isNotEmpty()) {
        appendLine("## Highlights")
        summary.highlights.forEach { appendLine("- ${it.text.trim()}") }
        appendLine()
    }
    summary.sections.forEach { section ->
        appendLine("## ${section.heading}")
        section.bullets.forEach { appendLine("- ${it.text.trim()}") }
        appendLine()
    }
    if (summary.actionItems.isNotEmpty()) {
        appendLine("## Action Items")
        summary.actionItems.forEach { item ->
            val suffix = buildString {
                item.owner?.let { append(" — $it") }
                item.deadline?.let { append(" (due $it)") }
            }
            appendLine("- [ ] ${item.text.trim()}$suffix")
        }
    }
}
