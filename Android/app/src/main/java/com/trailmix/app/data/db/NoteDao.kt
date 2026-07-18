package com.trailmix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

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
