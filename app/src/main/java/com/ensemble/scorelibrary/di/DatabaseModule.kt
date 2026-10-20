package com.ensemble.scorelibrary.di

import android.content.Context
import androidx.room.Room
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.dao.LibraryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ScoreLibraryDatabase =
        Room.databaseBuilder(
            context,
            ScoreLibraryDatabase::class.java,
            if (com.ensemble.scorelibrary.BuildConfig.HTTP_TEST) "synthetic-http-library.db" else "ensemble-score-library.db"
        ).addMigrations(ScoreLibraryDatabase.MIGRATION_1_2, ScoreLibraryDatabase.MIGRATION_2_3, ScoreLibraryDatabase.MIGRATION_3_4, ScoreLibraryDatabase.MIGRATION_4_5, ScoreLibraryDatabase.MIGRATION_5_6).build()

    @Provides
    fun provideLibraryDao(database: ScoreLibraryDatabase): LibraryDao = database.libraryDao()
}
