package com.ensemble.scorelibrary.domain

import kotlinx.coroutines.flow.Flow

interface PresentationRepository {
    fun observe(memberId: String?, request: ScreenRequest): Flow<DomainResult<Presentation>>
}
