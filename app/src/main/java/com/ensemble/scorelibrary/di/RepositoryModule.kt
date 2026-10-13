package com.ensemble.scorelibrary.di

import com.ensemble.scorelibrary.data.RoomScoreRepository
import com.ensemble.scorelibrary.domain.ScoreRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun archives(implementation: com.ensemble.scorelibrary.backup.AssetArchiveService): com.ensemble.scorelibrary.backup.AssetArchiveStore
    @Binds abstract fun pdfEngine(implementation: com.ensemble.scorelibrary.pdf.NativePdfEngine): com.ensemble.scorelibrary.pdf.PdfEngine
    @Binds abstract fun availability(implementation: com.ensemble.scorelibrary.data.AndroidDeviceAvailability): com.ensemble.scorelibrary.ui.DeviceAvailability
    @Binds abstract fun presentation(implementation: com.ensemble.scorelibrary.data.RoomPresentationRepository): com.ensemble.scorelibrary.domain.PresentationRepository
    @Binds abstract fun repository(implementation: RoomScoreRepository): ScoreRepository
}
