package com.trailmix.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** JSON-encoded List<NoteSegment> — the merged note body with per-segment provenance. */
    val segmentsJson: String,
    /** JSON-encoded List<TranscriptLine>. */
    val transcriptJson: String,
    /** The raw fragments the user typed during capture (kept for re-merge / audit). */
    val typedFragments: String,
    val durationMs: Long,
    val createdAtEpochMs: Long,
    /** Source tinting visible — defaults to true right after a merge. */
    val showSources: Boolean = true,
    val mergedWithAi: Boolean = false,
    /** Calendar event this capture happened during (or was started from), if any. */
    val meetingTitle: String? = null,
    /** True when the device was in a call/VoIP session when capture started. */
    val capturedInCall: Boolean = false,
    /**
     * User-edited plain-text body. Null until the note is edited by hand; when
     * set it replaces the merged [segments] for display/export (provenance
     * tinting no longer applies to a hand-authored body). Re-merging (resume)
     * clears it back to null.
     */
    val bodyOverride: String? = null,
) {
    val segments: List<NoteSegment> get() = SegmentsJson.decode(segmentsJson)
    val transcript: List<TranscriptLine> get() = TranscriptJson.decode(transcriptJson)

    /** The text shown as the note body: the hand-edited override if present, else the merged segments. */
    val displayBody: String
        get() = bodyOverride ?: segments.joinToString(" ") { it.text }

    val preview: String
        get() = displayBody.replace('\n', ' ').take(120)
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val role: String, // "user" | "assistant"
    val text: String,
    val createdAtEpochMs: Long,
)
