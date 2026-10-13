package com.ensemble.scorelibrary.domain

interface ScoreRepository {
    suspend fun visibleParts(memberId: String, pieceId: String): DomainResult<List<VisiblePart>>
    suspend fun resolvePiece(memberId: String, pieceId: String): DomainResult<ResolvedConcertItem>
    suspend fun resolveConcert(memberId: String, concertId: String): DomainResult<ResolvedConcert>
}
