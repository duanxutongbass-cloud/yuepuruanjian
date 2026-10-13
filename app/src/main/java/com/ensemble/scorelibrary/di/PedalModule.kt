package com.ensemble.scorelibrary.di

import com.ensemble.scorelibrary.core.pedal.AndroidPedalSettingsStore
import com.ensemble.scorelibrary.core.pedal.PedalSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PedalModule {
    @Binds abstract fun pedalSettings(store: AndroidPedalSettingsStore): PedalSettingsStore
}
