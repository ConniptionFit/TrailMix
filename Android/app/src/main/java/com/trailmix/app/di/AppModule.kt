package com.trailmix.app.di

import android.content.Context
import androidx.room.Room
import com.trailmix.app.data.db.ChatDao
import com.trailmix.app.data.db.Migrations
import com.trailmix.app.data.db.NoteDao
import com.trailmix.app.data.db.TrailMixDatabase
import com.trailmix.app.data.export.ExportSink
import com.trailmix.app.data.export.NoteExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TrailMixDatabase =
        Room.databaseBuilder(context, TrailMixDatabase::class.java, "trailmix.db")
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides
    fun provideNoteDao(db: TrailMixDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideChatDao(db: TrailMixDatabase): ChatDao = db.chatDao()

    /**
     * REL-14: [NotesRepository] depends on the [ExportSink] *interface*, not on
     * [NoteExporter], so the export/delete cascade can be exercised against a fake with no
     * SAF, no `Context` and no device. This is the only place the two are tied together.
     */
    @Provides
    @Singleton
    fun provideExportSink(exporter: NoteExporter): ExportSink = exporter
}
