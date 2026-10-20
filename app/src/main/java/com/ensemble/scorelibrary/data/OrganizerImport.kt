package com.ensemble.scorelibrary.data

import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartEntity

internal fun existingOrganizerPiece(pieces: List<PieceEntity>, parts: List<PiecePartEntity>,
    request: ImportScoreRequest, definitionId: String): PieceEntity? {
    val existing = request.organizerSequence?.let { number -> pieces.singleOrNull { it.organizerSequence == number } }
    if (existing == null) {
        check(!request.appendToNumberedPiece) { "未找到该编号，不能补入已有曲目" }
        return null
    }
    // User authorizes exact-number grouping. A matching title alone never identifies a piece.
    check(!existing.isArchived && existing.title == request.title.trim() && existing.categoryCode == request.categoryCode &&
        existing.categoryName == request.categoryName) { "编号对应的曲名或分类不一致，禁止自动合并" }
    check(parts.none { it.pieceId == existing.id && it.partDefinitionId == definitionId }) { "此曲目已有该声部，请在管理中显式替换版本" }
    return existing
}
