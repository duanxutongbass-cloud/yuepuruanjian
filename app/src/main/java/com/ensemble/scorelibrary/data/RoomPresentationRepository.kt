package com.ensemble.scorelibrary.data

import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import com.ensemble.scorelibrary.sync.TeamLibraryScope

@OptIn(ExperimentalCoroutinesApi::class)
class RoomPresentationRepository @Inject constructor(private val database: ScoreLibraryDatabase,
    private val team: TeamLibraryScope? = null) : PresentationRepository {
    override fun observe(memberId: String?, request: ScreenRequest) = combine(team?.active ?: flowOf(null),team?.readEpoch ?: flowOf(0L)) { c,_ -> c }
        .flatMapLatest { collection ->
        val selected = collection?.database ?: database
        selected.invalidationTracker.createFlow(
        "pieces", "movements", "part_definitions", "piece_parts", "score_pdfs", "score_pdf_versions",
        "part_score_bindings", "members", "member_part_permissions", "member_piece_part_permissions",
        "piece_part_assignments", "concerts", "concert_items", "score_cache_entries", "annotation_concert_snapshots"
        ).map {
            if (collection != null && memberId != collection.member)
                DomainResult.Failure(DomainError(ErrorCode.MEMBER_MISSING, ""))
            else {
                val data = selected.resolutionDao().snapshot()
                val concert=when(request) {
                    is ScreenRequest.Concert -> request.id
                    is ScreenRequest.Viewer -> request.concertId
                    else -> null
                }
                val frozen=if(collection!=null && concert!=null) selected.annotationDao().snapshot(concert) else null
                try { ScoreResolver(if (collection == null) data else team!!.project(collection, data, frozen)).presentation(memberId, request) }
                catch(error:IllegalArgumentException) { DomainResult.Failure(DomainError(ErrorCode.VERSION_MISMATCH,concert.orEmpty())) }
                catch(error:IllegalStateException) { DomainResult.Failure(DomainError(ErrorCode.VERSION_MISMATCH,concert.orEmpty())) }
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
