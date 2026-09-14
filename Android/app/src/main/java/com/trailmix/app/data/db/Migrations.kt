package com.trailmix.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The full v2→v9 migration chain, extracted out of [com.trailmix.app.di.AppModule] so
 * [MigrationTest][com.trailmix.app.data.db.MigrationTest] can exercise the exact objects Room
 * runs in production — no Hilt graph, no device, no duplicated SQL to drift out of sync.
 *
 * Schema changes ship as additive migrations only, never a destructive fallback (REL-03) — the
 * phone holds real notes.
 */
object Migrations {

    /** v1.2.0: CAL-01 meeting context + calendar-during-capture detection. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN meetingTitle TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN capturedInCall INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v1.3.0: hand-edited note bodies (UX-01). Nullable — null means "show the merged segments". */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN bodyOverride TEXT")
        }
    }

    /**
     * v1.4.0: CAL-02 attendee metadata + UX-02 structured summary. All nullable/additive —
     * an existing note with none of these set renders/exports exactly as it did before.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN attendeesJson TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN summaryJson TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN template TEXT")
        }
    }

    /**
     * v1.5.0: per-note export URI tracking (CAP-05 long-press menu delete/open-location,
     * INT-01 Google Drive sync). Both nullable/additive — an existing note with neither set
     * behaves exactly as before (no tracked export, "Open file location" stays disabled).
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN obsidianFileUri TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN driveFileUri TEXT")
        }
    }

    /**
     * v1.6.0: OBS-01 — recipe-produced assistant replies are tagged with the recipe's name
     * so they can be included in the note's Markdown exports. Nullable/additive — every
     * existing chat message stays an ordinary (non-exported) message.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN recipeName TEXT")
        }
    }

    /**
     * v1.8.0: REL-06 — soft-delete timestamp for the 1-day Recently deleted window.
     * Nullable/additive — every existing note stays a live (non-deleted) note.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN deletedAtEpochMs INTEGER")
        }
    }

    /**
     * OBS-02 (v1.11.0): the transcript moved out of the note file into a companion
     * `<name>.transcript.md`, so its `content://` URI needs tracking for exactly the reasons
     * the note's own `obsidianFileUri` is tracked — update-in-place across title changes,
     * cascade-delete, and the INT-02 export-location migration. Nullable/additive: existing
     * notes simply have no transcript file yet and get one on their next export.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN transcriptFileUri TEXT")
        }
    }

    /**
     * Photo-export feature (v1.20.0): tracks which MediaStore photo URIs were selected and
     * copied into this note's `photos/` export subfolder, as a JSON-encoded string list —
     * same pattern as [com.trailmix.app.data.model.StringListJson]-backed `attendeesJson`.
     * Supports update-in-place re-export without re-showing the picker. Nullable/additive:
     * existing notes simply have no exported photos yet. Only the URI references are stored,
     * never photo bytes, matching every other tracked-file column on this entity.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN exportedPhotoUrisJson TEXT")
        }
    }

    /**
     * CAP-24 live bookmark/flag-a-moment: the `mm:ss` labels of moments the user flagged
     * during capture, as a JSON-encoded string list — same [com.trailmix.app.data.model.StringListJson]
     * pattern as `attendeesJson`/`exportedPhotoUrisJson`. Nullable/additive: existing notes
     * simply have no flags.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN flaggedLabelsJson TEXT")
        }
    }

    /**
     * Speaker recognition (Eagle path, v1.21.0): a new table for enrolled speaker voiceprints,
     * each row an opaque `EagleProfile.getBytes()` export. The first `CREATE TABLE` migration
     * in this chain — every prior one is `ALTER TABLE ... ADD COLUMN` — but additive in the
     * same spirit: nothing existing is touched, an install with no enrolled speakers just has
     * an empty table.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS speaker_profiles (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "profileBytes BLOB NOT NULL, " +
                    "createdAtEpochMs INTEGER NOT NULL)",
            )
        }
    }

    /**
     * Cross-note chat (AI-10): a new table for conversations spanning more than one note,
     * keyed by [ConversationMessageEntity.noteIdsKey] rather than a single `noteId` — see that
     * entity's doc for why. Fully additive, same as [MIGRATION_11_12]: `chat_messages` and
     * everything else is untouched, so single-note chat behaves exactly as before.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "noteIdsKey TEXT NOT NULL, " +
                    "role TEXT NOT NULL, " +
                    "text TEXT NOT NULL, " +
                    "createdAtEpochMs INTEGER NOT NULL)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
        MIGRATION_12_13,
    )
}
