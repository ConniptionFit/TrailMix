package com.trailmix.app.data.db

import com.trailmix.app.data.obsidian.ObsidianExporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val obsidianExporter: ObsidianExporter,
) {
    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

    suspend fun saveProcessedNote(
        title: String,
        typedNotes: String,
        transcript: String,
        summary: String,
        mergedMarkdown: String,
        durationMs: Long,
        createdAtEpochMs: Long,
        audioPath: String?,
    ): NoteEntity {
        val id = noteDao.insert(
            NoteEntity(
                title = title,
                typedNotes = typedNotes,
                transcript = transcript,
                summary = summary,
                mergedMarkdown = mergedMarkdown,
                durationMs = durationMs,
                createdAtEpochMs = createdAtEpochMs,
                audioPath = audioPath,
            ),
        )
        val relativePath = obsidianExporter.exportNote(
            title = title,
            markdown = mergedMarkdown,
            createdAtEpochMs = createdAtEpochMs,
            durationMs = durationMs,
        )
        val saved = noteDao.getById(id)!!.copy(obsidianRelativePath = relativePath)
        noteDao.update(saved)
        return saved
    }

    suspend fun delete(id: Long) = noteDao.deleteById(id)
}
