package com.trailmix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NoteEntity::class, ChatMessageEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TrailMixDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun chatDao(): ChatDao
}
