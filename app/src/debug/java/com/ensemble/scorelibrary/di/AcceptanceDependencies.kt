package com.ensemble.scorelibrary.di

import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Instrumentation seeds only an explicitly opted-in disposable emulator; absent from release. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AcceptanceDependencies {
    fun database(): ScoreLibraryDatabase
}
