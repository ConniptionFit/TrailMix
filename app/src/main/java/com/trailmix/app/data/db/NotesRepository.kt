package com.trailmix.app.data.db

import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.obsidian.ObsidianExporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val chatDao: ChatDao,
    private val obsidianExporter: ObsidianExporter,
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

    suspend fun addChatMessage(noteId: Long, role: String, text: String) {
        chatDao.insert(
            ChatMessageEntity(
                noteId = noteId,
                role = role,
                text = text,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) {
        chatDao.deleteForNote(id)
        noteDao.deleteById(id)
    }

    /** Best-effort Markdown export into a linked Obsidian vault (local SAF only). */
    private suspend fun exportIfConfigured(note: NoteEntity) {
        runCatching {
            obsidianExporter.exportNote(
                title = note.title,
                markdown = note.toMarkdown(),
                createdAtEpochMs = note.createdAtEpochMs,
                durationMs = note.durationMs,
            )
        }
    }
}

fun NoteEntity.toMarkdown(): String = buildString {
    appendLine("# $title")
    appendLine()
    val override = bodyOverride
    if (override != null) {
        appendLine(override.trim())
    } else {
        segments.forEach { appendLine(it.text.trim()) }
    }
    val lines = transcript
    if (lines.isNotEmpty()) {
        appendLine()
        appendLine("## Transcript")
        lines.forEach { appendLine("- **${it.label}** ${it.text.trim()}") }
    }
}.trim()
