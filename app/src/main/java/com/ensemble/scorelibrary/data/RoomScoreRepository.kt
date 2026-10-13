package com.ensemble.scorelibrary.data

import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.*
import javax.inject.Inject

class RoomScoreRepository @Inject constructor(private val database: ScoreLibraryDatabase) : ScoreRepository {
    private suspend fun resolver() = ScoreResolver(database.resolutionDao().snapshot())
    override suspend fun visibleParts(memberId: String, pieceId: String) =
        resolver().visibleParts(memberId, pieceId)
    override suspend fun resolvePiece(memberId: String, pieceId: String) =
        resolver().resolvePiece(memberId, pieceId)
    override suspend fun resolveConcert(memberId: String, concertId: String) =
        resolver().resolveConcert(memberId, concertId)
}
