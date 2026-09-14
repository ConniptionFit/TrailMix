package com.trailmix.app.di

import android.content.Context
import androidx.room.Room
import com.trailmix.app.data.db.ChatDao
import com.trailmix.app.data.db.ConversationDao
import com.trailmix.app.data.db.Migrations
import com.trailmix.app.data.db.NoteDao
import com.trailmix.app.data.db.SpeakerProfileDao
import com.trailmix.app.data.db.TrailMixDatabase
import com.trailmix.app.data.export.ExportSink
import com.trailmix.app.data.export.NoteExporter
import com.trailmix.app.data.speech.SherpaOnnxDiarizer
import com.trailmix.app.data.speech.SherpaOnnxDiarizerConfig
import com.trailmix.app.data.speech.SpeakerDiarizer
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

    @Provides
    fun provideSpeakerProfileDao(db: TrailMixDatabase): SpeakerProfileDao = db.speakerProfileDao()

    @Provides
    fun provideConversationDao(db: TrailMixDatabase): ConversationDao = db.conversationDao()

    /**
     * REL-14: [NotesRepository] depends on the [ExportSink] *interface*, not on
     * [NoteExporter], so the export/delete cascade can be exercised against a fake with no
     * SAF, no `Context` and no device. This is the only place the two are tied together.
     */
    @Provides
    @Singleton
    fun provideExportSink(exporter: NoteExporter): ExportSink = exporter

    /**
     * AI-01 (2026-09-13): [CaptureSessionManager][com.trailmix.app.data.speech.CaptureSessionManager]
     * depends on the [SpeakerDiarizer] *interface*, not [SherpaOnnxDiarizer] — same seam,
     * same reasoning as [provideExportSink] above. Swapping to a newer sherpa-onnx release or a
     * different engine entirely is a new implementation class plus this one line changing,
     * never a change to the interface or its consumer.
     */
    @Provides
    @Singleton
    fun provideSpeakerDiarizer(impl: SherpaOnnxDiarizer): SpeakerDiarizer = impl

    @Provides
    @Singleton
    fun provideSherpaOnnxDiarizerConfig(): SherpaOnnxDiarizerConfig = SherpaOnnxDiarizerConfig()
}
