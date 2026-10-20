package com.ensemble.scorelibrary.data

import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.*
import javax.inject.Inject

class RoomScoreRepository @Inject constructor(private val database: ScoreLibraryDatabase,
    private val team: com.ensemble.scorelibrary.sync.TeamLibraryScope? = null) : ScoreRepository {
    private suspend fun resolver(member: String, concert: String? = null): ScoreResolver {
        val collection = team?.active?.value
        val selected = team?.databaseFor(member, database) ?: database
        val data = selected.resolutionDao().snapshot()
        check(team?.active?.value === collection) { "团队账号已切换" }
        val frozen = if(collection != null && concert != null) selected.annotationDao().snapshot(concert) else null
        return ScoreResolver(if (collection == null) data else team!!.project(collection, data, frozen))
    }
    override suspend fun visibleParts(memberId: String, pieceId: String) =
        resolver(memberId).visibleParts(memberId, pieceId)
    override suspend fun resolvePiece(memberId: String, pieceId: String) =
        resolver(memberId).resolvePiece(memberId, pieceId)
    override suspend fun resolveConcert(memberId: String, concertId: String) =
        resolver(memberId, concertId).resolveConcert(memberId, concertId)
}
