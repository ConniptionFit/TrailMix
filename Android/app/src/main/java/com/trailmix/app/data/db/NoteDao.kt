package com.trailmix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * OBS-04 + REL-14: a live note that is not fully backed up by files outside app-private
 * storage. Shared by the count and the list so the badge and the repair can never disagree
 * about what "unexported" means.
 *
 * `obsidianFileUri` is only ever set after a *successful* write, so null means the note file
 * never landed. **REL-14 added the second clause**: a note whose summary exported but whose
 * companion `.transcript.md` did not was invisible here, so OBS-04 both under-counted and
 * never repaired it — the note read as backed up while the verbatim record, the half that
 * cannot be regenerated, was missing.
 *
 * `transcriptJson <> '[]'` stands in for [NoteExporter]'s own "does this note have transcript
 * content" test. The two agree because every stored [com.trailmix.app.data.model.TranscriptLine]
 * comes from a path that already rejected blank text, so "has lines" and "has non-blank lines"
 * are the same set in practice; if they ever diverged the cost is one redundant export attempt
 * per repair pass, not a wrong answer.
 */
private const val UNEXPORTED =
    "deletedAtEpochMs IS NULL AND (obsidianFileUri IS NULL OR " +
        "(transcriptFileUri IS NULL AND transcriptJson <> '[]' AND transcriptJson <> ''))"

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
    @Query("SELECT COUNT(*) FROM notes WHERE $UNEXPORTED")
    fun observeUnexportedCount(): Flow<Int>

    @Query("SELECT * FROM notes WHERE $UNEXPORTED ORDER BY createdAtEpochMs DESC")
    suspend fun getUnexported(): List<NoteEntity>

    /**
     * REL-14: write back **only** the two tracked export URIs.
     *
     * This exists because the whole-row `@Update` it replaces was a lost-update bug. An
     * export is slow SAF I/O, and the row it wrote back was the snapshot read *before* that
     * I/O — so anything the user did to the note meanwhile was silently reverted when the
     * export landed. The worst shape was a delete: `deletedAtEpochMs` went back to null and
     * the note reappeared, which on an app whose delete is the user's own decision is the
     * opposite of fail-soft. Editing the note or adding a chat message in the same window
     * lost that edit the same way.
     *
     * Two columns, addressed by id, cannot revert anything they don't name.
     */
    @Query("UPDATE notes SET obsidianFileUri = :noteUri, transcriptFileUri = :transcriptUri WHERE id = :id")
    suspend fun setExportUris(id: Long, noteUri: String?, transcriptUri: String?)

    /**
     * Photo-export feature: persists which MediaStore photos the user selected for this
     * note, addressed by id alone for the same lost-update reason as [setExportUris] — never
     * a whole-row `@Update` from a snapshot that could be stale by the time this runs.
     */
    @Query("UPDATE notes SET exportedPhotoUrisJson = :photoUrisJson WHERE id = :id")
    suspend fun setExportedPhotoUris(id: Long, photoUrisJson: String?)

    @Query("UPDATE notes SET deletedAtEpochMs = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    /**
     * Also clears **both** tracked export URIs — the delete removed both files.
     *
     * REL-14: `transcriptFileUri` was left set, so a restored note pointed its companion
     * transcript at a `content://` document that had been deleted. The next export then
     * handed that stale URI to the writer, which either wrote nothing useful or — if the
     * provider had recycled the document id — wrote this note's transcript over an unrelated
     * file. Clearing it makes the restore do what its docstring already claimed: re-export
     * fresh, both halves.
     */
    @Query(
        "UPDATE notes SET deletedAtEpochMs = NULL, obsidianFileUri = NULL, " +
            "transcriptFileUri = NULL WHERE id = :id",
    )
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
