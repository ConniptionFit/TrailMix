package com.trailmix.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.trailmix.app.data.db.ChatDao
import com.trailmix.app.data.db.NoteDao
import com.trailmix.app.data.db.TrailMixDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The phone holds real notes now — schema changes ship as additive
     * migrations, never as a destructive fallback (REL-03).
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN meetingTitle TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN capturedInCall INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v1.3.0: hand-edited note bodies (UX-01). Nullable — null means "show the merged segments". */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN bodyOverride TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TrailMixDatabase =
        Room.databaseBuilder(context, TrailMixDatabase::class.java, "trailmix.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideNoteDao(db: TrailMixDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideChatDao(db: TrailMixDatabase): ChatDao = db.chatDao()
}
