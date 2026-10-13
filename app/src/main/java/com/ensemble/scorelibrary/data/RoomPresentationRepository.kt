package com.ensemble.scorelibrary.data

import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class RoomPresentationRepository @Inject constructor(private val database: ScoreLibraryDatabase) : PresentationRepository {
    override fun observe(memberId: String?, request: ScreenRequest) = database.invalidationTracker.createFlow(
        "pieces", "movements", "part_definitions", "piece_parts", "score_pdfs", "score_pdf_versions",
        "part_score_bindings", "members", "member_part_permissions", "member_piece_part_permissions",
        "piece_part_assignments", "concerts", "concert_items", "score_cache_entries"
    ).map { ScoreResolver(database.resolutionDao().snapshot()).presentation(memberId, request) }
        .flowOn(Dispatchers.IO)
}
