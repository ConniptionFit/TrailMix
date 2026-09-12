package com.trailmix.app.data.export

import com.trailmix.app.data.db.NoteEntity

/**
 * The two files one note produces (OBS-02, v1.11.0). [transcript] is null when the note has
 * no transcript lines — a typed-only note doesn't get an empty companion file.
 *
 * REL-14: these are `content://` URIs held as **strings**, not `android.net.Uri`. That is the
 * form they are stored in on [NoteEntity] and compared in, so the conversion belongs on the
 * SAF side of this seam rather than being done and undone on the repository side — and it
 * keeps `android.net.Uri` (a class that returns null under unit-test stubs) out of a contract
 * the repository's tests have to speak.
 */
data class ExportedFiles(val note: String, val transcript: String?)

/**
 * Recovery feature (2026-09-12): one summary/transcript pair already sitting in the export
 * folder, as raw file content plus the real `content://` URIs — so a note restored from it
 * can be linked for update-in-place re-export rather than creating a duplicate file next to
 * the one it came from.
 */
data class ExportedNoteFile(
    val noteUri: String,
    val transcriptUri: String?,
    val noteMarkdown: String,
    val transcriptMarkdown: String?,
)

/**
 * REL-14: everything [com.trailmix.app.data.db.NotesRepository] needs to reach the user's
 * export folder — deliberately an interface, and deliberately Android-free.
 *
 * The repository owns the *cascade* (which notes get written, when a delete takes its files
 * with it, what a restore rewrites) and that logic is where the defects were: a note could be
 * resurrected by a slow export landing after it was deleted, and the repair pass exported the
 * whole backlog once per unexported note. None of that needs SAF, a `Context`, or a device to
 * exercise — it needed a seam, which is what this is. See `NotesRepositoryTest`.
 *
 * [NoteExporter] is the real implementation and is where SAF, `DocumentFile` and
 * `DocumentsContract` stay. `deleteExported` lives here rather than on the repository for the
 * same reason: the export package owns the export files' whole lifecycle, creation to removal.
 */
interface ExportSink {

    /**
     * Whether an export location is configured at all.
     *
     * OBS-04 needs this to tell two very different situations apart: "you have no export
     * location, so nothing is expected to be backed up" versus "you have one and these notes
     * failed to reach it". Reporting the first as a failure would be noise; reporting the
     * second as fine would be a lie.
     */
    suspend fun isConfigured(): Boolean

    /**
     * Best-effort export/update-in-place. Returns null when no location is set or the note
     * write failed; a failed *transcript* write is not fatal, it just leaves that URI null.
     *
     * [selectedPhotoUris] (photo-export feature) are `content://` MediaStore URI **strings**
     * to copy into this note's `photos/` export subfolder — plain strings for the same
     * Android-free reason as everything else here. Empty by default; when empty on a note
     * that already has [NoteEntity.exportedPhotoUris] tracked, those are re-copied instead,
     * so a background repair pass (`retryMissingExports`/`exportMissing`, which has no UI to
     * reselect) still re-attaches whatever photos were chosen originally.
     */
    suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
        selectedPhotoUris: List<String> = emptyList(),
    ): ExportedFiles?

    /**
     * Remove a previously exported file. False on any failure (stale URI, revoked SAF grant,
     * provider error) — every caller treats this as fail-soft, never a reason to abort a
     * local delete.
     */
    fun deleteExported(uriStr: String): Boolean

    /**
     * OBS-05: whether a tracked export file is still actually there. A tracked URI going
     * stale (the user deleted the file in a file manager, a sync conflict removed it, the
     * provider recycled the document id) was previously never noticed — the note read as
     * backed up on the strength of a URI alone, whether or not anything was still behind it.
     */
    fun exists(uriStr: String): Boolean

    /**
     * Recovery feature: every `.md` note (with its companion transcript, if any) currently
     * sitting in the export folder. Empty if no location is configured. Used by
     * [com.trailmix.app.data.db.NotesRepository.importFromExportFolder] to reconstruct notes
     * after data loss — the exported folder is this app's only backup (`allowBackup=false`).
     */
    suspend fun listExportedNotes(): List<ExportedNoteFile>
}
