package com.trailmix.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val typedNotes: String,
    val transcript: String,
    val summary: String,
    val mergedMarkdown: String,
    val durationMs: Long,
    val createdAtEpochMs: Long,
    val audioPath: String? = null,
    val obsidianRelativePath: String? = null,
)
