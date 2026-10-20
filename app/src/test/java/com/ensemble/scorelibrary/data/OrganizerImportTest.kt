package com.ensemble.scorelibrary.data

import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartEntity
import org.junit.Assert.*
import org.junit.Test

class OrganizerImportTest {
    private val piece = PieceEntity(id = "existing", title = "天空之城", categoryCode = "01", categoryName = "久石让", organizerSequence = "007")
    private val request = ImportScoreRequest("天空之城", "第一小提琴", "m", categoryCode = "01", categoryName = "久石让", organizerSequence = "007")
    @Test fun exactNumberWithMatchingMetadataAutomaticallyGroupsPartsAndPreservesIdentity() {
        assertSame(piece, existingOrganizerPiece(listOf(piece), emptyList(), request, "violin"))
        assertSame(piece, existingOrganizerPiece(listOf(piece), emptyList(), request.copy(appendToNumberedPiece = true), "violin"))
    }
    @Test fun conflictingNumberAndDuplicateInstrumentCannotOverwriteOrMerge() {
        val confirmed = request.copy(appendToNumberedPiece = true)
        for (bad in listOf(piece.copy(title = "Other"), piece.copy(categoryCode = "02"), piece.copy(isArchived = true)))
            assertThrows(IllegalStateException::class.java) { existingOrganizerPiece(listOf(bad), emptyList(), confirmed, "violin") }
        val part = PiecePartEntity(pieceId = piece.id, partDefinitionId = "violin")
        assertThrows(IllegalStateException::class.java) { existingOrganizerPiece(listOf(piece), listOf(part), confirmed, "violin") }
        assertThrows(IllegalStateException::class.java) { existingOrganizerPiece(emptyList(), emptyList(), confirmed, "violin") }
        assertNull(existingOrganizerPiece(emptyList(), emptyList(), request, "violin"))
    }
}
