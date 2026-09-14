package com.trailmix.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.SegmentsJson
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.StructuredSummaryJson
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine

// REL-18 (v1.20.0-dev): every Home query filters on this column (soft-delete visibility) —
// unindexed, it was a full table scan on every one of them.
@Entity(tableName = "notes", indices = [Index("deletedAtEpochMs")])
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
     * `content://` URI of this note's exported file in the configured Export location, if it
     * has ever been exported (v1.5.0; needed for update-in-place, cascade-delete, and the
     * INT-02 export-location migration). Column keeps its historical "obsidian" name — since
     * v1.7.0 the destination is the neutral Export location, not necessarily an Obsidian vault.
     * Null until the first successful export.
     */
    val obsidianFileUri: String? = null,
    /**
     * DORMANT (INT-02, v1.7.0): was the Google Drive sync copy's URI (INT-01, v1.5.0).
     * Google Drive sync was removed; this column stays physically in the schema (Room
     * migrations are additive-only) and on the entity (so the Room identity hash still
     * matches DB v7) but is never read or written anymore.
     */
    val driveFileUri: String? = null,
    /**
     * REL-06 (v1.8.0): soft-delete timestamp. Non-null = the note is in Recently deleted,
     * hidden from every normal surface, and recoverable until it's older than
     * [com.trailmix.app.data.db.RecentlyDeleted.RECOVERY_WINDOW_MS] (1 day), when the purge
     * removes the row for real. Null = live note.
     */
    val deletedAtEpochMs: Long? = null,
    /**
     * OBS-02 (v1.11.0): `content://` URI of this note's companion `<name>.transcript.md` in
     * the Export location. The transcript was split out of the note file so the note stays
     * small enough to paste into another model as context — a 45-minute transcript is tens of
     * thousands of characters that crowd out everything else. Tracked for the same reasons as
     * [obsidianFileUri]: update-in-place, cascade-delete, and export-location migration.
     * Null until the note has been exported with a non-empty transcript.
     */
    val transcriptFileUri: String? = null,
    /**
     * Photo-export feature (v1.20.0): JSON-encoded `List<String>` of `content://` MediaStore
     * URIs the user selected for this note's export, copied into its `photos/` export
     * subfolder. Only the references are stored, never photo bytes. Null until the note has
     * been exported with at least one photo attached.
     */
    val exportedPhotoUrisJson: String? = null,
    /**
     * CAP-24 (v1.20.0): JSON-encoded `List<String>` of `mm:ss` labels the user flagged during
     * capture — jump points into this note's transcript, not a separate value object, so no
     * migration is needed if flags ever grow more fields (they'd move to their own JSON shape
     * without touching this column's meaning). Null/empty = no flags on this note.
     */
    val flaggedLabelsJson: String? = null,
) {
    val segments: List<NoteSegment> get() = SegmentsJson.decode(segmentsJson)
    val transcript: List<TranscriptLine> get() = TranscriptJson.decode(transcriptJson)
    val attendees: List<String> get() = StringListJson.decode(attendeesJson)
    val structuredSummary: StructuredSummary? get() = StructuredSummaryJson.decode(summaryJson)
    val exportedPhotoUris: List<String> get() = StringListJson.decode(exportedPhotoUrisJson)
    val flaggedLabels: List<String> get() = StringListJson.decode(flaggedLabelsJson)

    /** The text shown as the note body: the hand-edited override if present, else the merged segments. */
    val displayBody: String
        get() = bodyOverride ?: segments.joinToString(" ") { it.text }

    val preview: String
        get() = displayBody.replace('\n', ' ').take(120)
}

// REL-18 (v1.20.0-dev): observeForNote/getRecipeOutputs/deleteForNote all full-scan this table
// filtering on noteId — unindexed, since it's an FK column Room does not index by default.
@Entity(tableName = "chat_messages", indices = [Index("noteId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val role: String, // "user" | "assistant"
    val text: String,
    val createdAtEpochMs: Long,
    /**
     * Set on an assistant reply that was produced by running a saved recipe (OBS-01,
     * v1.6.0) — holds the recipe's name (e.g. "Follow-up email"). Null for ordinary chat
     * replies and all user messages. Distinguishes durable recipe outputs (exported with
     * the note) from conversational chatter (not exported).
     */
    val recipeName: String? = null,
)

/**
 * Cross-note chat (AI-10): a conversation isn't keyed by its own allocated id — like single-note
 * chat is keyed directly by `noteId`, not a separate "chat session" concept — it's keyed by
 * [noteIdsKey], a canonical (sorted, deduped, comma-joined) encoding of the note set being
 * discussed (see [ConversationKey]). Selecting the same set of notes again resumes the same
 * conversation, mirroring how reopening one note's chat resumes its history.
 */
@Entity(tableName = "conversation_messages")
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteIdsKey: String,
    val role: String, // "user" | "assistant"
    val text: String,
    val createdAtEpochMs: Long,
)

/**
 * Speaker recognition (Eagle path): one enrolled voiceprint, opaque outside the SDK boundary —
 * [profileBytes] is exactly `EagleProfile.getBytes()`, reconstructed via `EagleProfile(ByteArray)`
 * only where actually needed (never held open here — `EagleProfile` owns a native handle that
 * must be `delete()`d, so this row stores inert bytes, not a live SDK object).
 */
@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val profileBytes: ByteArray,
    val createdAtEpochMs: Long,
) {
    // ByteArray gives the compiler-generated equals/hashCode reference identity, not content
    // equality — override so two loads of the same row compare equal by id, not by array ref.
    override fun equals(other: Any?): Boolean =
        other is SpeakerProfileEntity && id == other.id && name == other.name &&
            createdAtEpochMs == other.createdAtEpochMs && profileBytes.contentEquals(other.profileBytes)

    override fun hashCode(): Int = id.hashCode()
}
