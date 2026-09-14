package com.trailmix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        NoteEntity::class, ChatMessageEntity::class, SpeakerProfileEntity::class,
        ConversationMessageEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class TrailMixDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun chatDao(): ChatDao
    abstract fun speakerProfileDao(): SpeakerProfileDao
    abstract fun conversationDao(): ConversationDao
}
