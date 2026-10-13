package com.ensemble.scorelibrary.domain

import com.ensemble.scorelibrary.core.database.entity.*
import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    private fun data() = LibrarySnapshot(
        pieces = listOf(PieceEntity(id = "p", title = "Piece")),
        members = listOf(MemberEntity(id = "m", displayName = "Member", defaultPartDefinitionId = "d")),
        definitions = listOf(PartDefinitionEntity(id = "d", name = "Visible"), PartDefinitionEntity(id = "hidden", name = "Secret")),
        parts = listOf(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"),
            PiecePartEntity(id = "secret", pieceId = "p", partDefinitionId = "hidden")),
        permissions = listOf(MemberPartPermissionEntity(memberId = "m", partDefinitionId = "d")),
        pdfs = listOf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = "v")),
        versions = listOf(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
            managedFilePath = "managed/score.pdf", originalUri = "content://file", fileHash = "hash", fileSizeBytes = 1, pageCount = 8)),
        bindings = listOf(PartScoreBindingEntity(piecePartId = "part", scorePdfId = "pdf", startPage = 1, endPage = 8)),
        concerts = listOf(ConcertEntity(id = "c", title = "Concert")),
        items = listOf(ConcertItemEntity(id = "i", concertId = "c", pieceId = "p", sortOrder = 1))
    )
    @Test fun noMemberMeansNoCatalogAndOnlyNonArchivedMemberChoices() {
        val d = data()
        val result = ScoreResolver(d.copy(members = d.members + d.members.single().copy(id = "old", isArchived = true)))
            .dashboard(null) as DomainResult.Success
        assertTrue(result.value.pieces.isEmpty())
        assertTrue(result.value.concerts.isEmpty())
        assertEquals(listOf("m"), result.value.members.map { it.id })
    }
    @Test fun pieceDetailUsesAuthorizedDomainProjectionOnly() {
        val presentation = ScoreResolver(data()).presentation("m", ScreenRequest.Piece("p")) as DomainResult.Success
        val piece = (presentation.value.content as DomainResult.Success).value as ScreenContent.Piece
        assertEquals(listOf("Visible"), piece.parts.map { it.name })
        assertEquals("p", piece.id)
    }
    @Test fun directViewerCannotAccessHiddenOrWrongPiecePart() {
        val resolver = ScoreResolver(data())
        assertEquals(ErrorCode.UNAUTHORIZED, (resolver.resolvePart("m", "p", "secret") as DomainResult.Failure).error.code)
        assertEquals(ErrorCode.PIECE_MISSING, (resolver.resolvePart("m", "other", "part") as DomainResult.Failure).error.code)
        assertTrue(resolver.resolvePart("m", "p", "part") is DomainResult.Success)
    }
    @Test fun revokedPermissionRemovesDetailAndBlocksViewer() {
        val d = data()
        val resolver = ScoreResolver(d.copy(permissions = d.permissions.map { it.copy(isArchived = true) }))
        assertTrue((resolver.visibleParts("m", "p") as DomainResult.Success).value.isEmpty())
        assertTrue(resolver.resolvePart("m", "p", "part") is DomainResult.Failure)
    }
    @Test fun concertViewerRevalidatesItemAndAssignedPart() {
        val resolver = ScoreResolver(data())
        val valid = resolver.presentation("m", ScreenRequest.Viewer("p", "part", "c", "i")) as DomainResult.Success
        assertTrue(valid.value.content is DomainResult.Success)
        val bad = resolver.presentation("m", ScreenRequest.Viewer("p", "secret", "c", "i")) as DomainResult.Success
        assertEquals(ErrorCode.UNAUTHORIZED, (bad.value.content as DomainResult.Failure).error.code)
        val absent = resolver.presentation("m", ScreenRequest.Viewer("p", "part", "c", "missing")) as DomainResult.Success
        assertTrue(absent.value.content is DomainResult.Failure)
    }
    @Test fun adminAccessFlagComesFromDomainAndArchivedMembersFail() {
        assertFalse((ScoreResolver(data()).dashboard("m") as DomainResult.Success).value.profile!!.canManage)
        val d = data()
        assertTrue(ScoreResolver(d.copy(members = d.members.map { it.copy(isArchived = true) })).dashboard("m") is DomainResult.Failure)
    }
    @Test fun unauthorizedDefaultPartNameIsNotExposedByProfile() {
        val d = data()
        assertEquals("Visible", (ScoreResolver(d).dashboard("m") as DomainResult.Success).value.profile!!.defaultPartName)
        assertNull((ScoreResolver(d.copy(permissions = emptyList())).dashboard("m") as DomainResult.Success).value.profile!!.defaultPartName)
        assertNull((ScoreResolver(d.copy(permissions = d.permissions.map { it.copy(isArchived = true) })).dashboard("m") as DomainResult.Success).value.profile!!.defaultPartName)
    }
}
