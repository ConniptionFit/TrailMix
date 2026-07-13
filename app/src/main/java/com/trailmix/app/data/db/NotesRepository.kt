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

    fun observeChat(noteId: Long): Flow<List<ChatMessageEntity>> = chatDao.observeForNote(noteId)

    suspend fun saveMergedNote(
        title: String,
        segments: List<NoteSegment>,
        transcript: List<TranscriptLine>,
        typedFragments: String,
        durationMs: Long,
        createdAtEpochMs: Long,
        mergedWithAi: Boolean,
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
            ),
        )
        exportIfConfigured(noteDao.getById(id)!!)
        return id
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
    segments.forEach { appendLine(it.text.trim()) }
    val lines = transcript
    if (lines.isNotEmpty()) {
        appendLine()
        appendLine("## Transcript")
        lines.forEach { appendLine("- **${it.label}** ${it.text.trim()}") }
    }
}.trim()
