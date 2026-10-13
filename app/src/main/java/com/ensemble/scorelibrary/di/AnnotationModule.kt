package com.ensemble.scorelibrary.di

import com.ensemble.scorelibrary.annotation.AnnotationRepository
import com.ensemble.scorelibrary.annotation.AnnotationStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AnnotationModule {
    @Binds abstract fun annotations(repository: AnnotationRepository): AnnotationStore
    companion object {
        @dagger.Provides
        @com.ensemble.scorelibrary.annotation.InkComputation
        fun computation(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    }
}
