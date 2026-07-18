package com.trailmix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAtEpochMs IS NULL ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    /** One-shot list of every live note — used by the export-location migration (INT-02, v1.7.0). */
    @Query("SELECT * FROM notes WHERE deletedAtEpochMs IS NULL ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<NoteEntity>

    // ── Recently deleted (REL-04, v1.8.0) ──────────────────────────────────

    @Query("SELECT * FROM notes WHERE deletedAtEpochMs IS NOT NULL ORDER BY deletedAtEpochMs DESC")
    fun observeDeleted(): Flow<List<NoteEntity>>

    @Query("UPDATE notes SET deletedAtEpochMs = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    /** Also clears the tracked export URI — the export file was removed at soft-delete time. */
    @Query("UPDATE notes SET deletedAtEpochMs = NULL, obsidianFileUri = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("SELECT * FROM notes WHERE deletedAtEpochMs IS NOT NULL AND deletedAtEpochMs < :cutoff")
    suspend fun getDeletedBefore(cutoff: Long): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("UPDATE notes SET showSources = :show WHERE id = :id")
    suspend fun setShowSources(id: Long, show: Boolean)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE noteId = :noteId ORDER BY createdAtEpochMs ASC, id ASC")
    fun observeForNote(noteId: Long): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    /** All recipe-produced assistant replies for a note, oldest first (OBS-01). */
    @Query(
        "SELECT * FROM chat_messages WHERE noteId = :noteId AND recipeName IS NOT NULL " +
            "ORDER BY createdAtEpochMs ASC, id ASC",
    )
    suspend fun getRecipeOutputs(noteId: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)
}
