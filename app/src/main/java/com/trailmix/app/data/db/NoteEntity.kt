package com.trailmix.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
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
    /** JSON-encoded List<String> of calendar attendee names for this meeting, if known (CAL-02). */
    val attendeesJson: String? = null,
    /** JSON-encoded [StructuredSummary]; null = this note has only the flat [segments] body (UX-02). */
    val summaryJson: String? = null,
    /** Name of the [com.trailmix.app.data.model.SummaryTemplate] used to steer structuring, if any. */
    val template: String? = null,
    /**
     * `content://` URI of this note's exported file in the linked Obsidian vault, if it has
     * ever been exported (v1.5.0, needed for update-in-place, cascade-delete, and "Open file
     * location" — CAP-05/INT-01). Null until the first successful export.
     */
    val obsidianFileUri: String? = null,
    /** Same as [obsidianFileUri] but for the Google Drive SAF sync target (INT-01, v1.5.0). */
    val driveFileUri: String? = null,
) {
    val segments: List<NoteSegment> get() = SegmentsJson.decode(segmentsJson)
    val transcript: List<TranscriptLine> get() = TranscriptJson.decode(transcriptJson)
    val attendees: List<String> get() = StringListJson.decode(attendeesJson)
    val structuredSummary: StructuredSummary? get() = StructuredSummaryJson.decode(summaryJson)

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
