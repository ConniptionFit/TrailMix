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

    // ── Recently deleted (REL-06, v1.8.0) ──────────────────────────────────

    @Query("SELECT * FROM notes WHERE deletedAtEpochMs IS NOT NULL ORDER BY deletedAtEpochMs DESC")
    fun observeDeleted(): Flow<List<NoteEntity>>

    /**
     * OBS-04: live notes with no exported file behind them.
     *
     * `obsidianFileUri` is only ever set after a *successful* write, so a null here means the
     * note is not backed up by anything outside app-private storage. Since the app is
     * `allowBackup=false` and local-only, that is the difference between a note that survives
     * a wipe and one that does not — worth counting, and worth being able to repair.
     *
     * Restored notes legitimately appear here: [restore] nulls the URI on purpose so the
     * export is rewritten fresh rather than pointing at a file that was deleted with the note.
     */
    @Query("SELECT COUNT(*) FROM notes WHERE deletedAtEpochMs IS NULL AND obsidianFileUri IS NULL")
    fun observeUnexportedCount(): Flow<Int>

    @Query(
        "SELECT * FROM notes WHERE deletedAtEpochMs IS NULL AND obsidianFileUri IS NULL " +
            "ORDER BY createdAtEpochMs DESC",
    )
    suspend fun getUnexported(): List<NoteEntity>

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
