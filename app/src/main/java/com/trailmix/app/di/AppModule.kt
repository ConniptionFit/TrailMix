package com.trailmix.app.di

import android.content.Context
import androidx.room.Room
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
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TrailMixDatabase =
        Room.databaseBuilder(context, TrailMixDatabase::class.java, "trailmix.db").build()

    @Provides
    fun provideNoteDao(db: TrailMixDatabase): NoteDao = db.noteDao()
}
