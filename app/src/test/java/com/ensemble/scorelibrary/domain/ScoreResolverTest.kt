package com.ensemble.scorelibrary.domain

import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import org.junit.Assert.*
import org.junit.Test

class ScoreResolverTest {
    private fun fixture() = LibrarySnapshot(
        pieces = listOf(PieceEntity(id = "p", title = "Piece")),
        definitions = listOf(PartDefinitionEntity(id = "d", name = "I"), PartDefinitionEntity(id = "e", name = "II")),
        parts = listOf(PiecePartEntity(id = "a", pieceId = "p", partDefinitionId = "d"),
            PiecePartEntity(id = "b", pieceId = "p", partDefinitionId = "e")),
        members = listOf(MemberEntity(id = "m", displayName = "Member", defaultPartDefinitionId = "d")),
        permissions = listOf(MemberPartPermissionEntity(memberId = "m", partDefinitionId = "d")),
        pdfs = listOf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = "v")),
        versions = listOf(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
            managedFilePath = "file", originalUri = "content://file", fileHash = "hash", fileSizeBytes = 1, pageCount = 10)),
        bindings = listOf(PartScoreBindingEntity(id = "binding", piecePartId = "a", scorePdfId = "pdf", startPage = 1, endPage = 10)),
        concerts = listOf(ConcertEntity(id = "c", title = "Concert")),
        items = listOf(ConcertItemEntity(id = "i", concertId = "c", pieceId = "p", sortOrder = 0))
    )
    private fun resolve(data: LibrarySnapshot) = ScoreResolver(data).resolveConcert("m", "c")
    private fun value(data: LibrarySnapshot) = (resolve(data) as DomainResult.Success).value
    private fun error(code: ErrorCode, data: LibrarySnapshot) {
        assertEquals(code, (resolve(data) as DomainResult.Failure).error.code)
    }

    @Test fun defaultsRequirePermissionAndHiddenPartsStayHidden() {
        val data = fixture()
        val visible = ScoreResolver(data).visibleParts("m", "p") as DomainResult.Success
        assertEquals(listOf("a"), visible.value.map { it.id })
        assertEquals("a", value(data).items.single().part.id)
        error(ErrorCode.UNAUTHORIZED, data.copy(permissions = emptyList()))
    }

    @Test fun assignmentOverridesDefaultOnlyWithExplicitPermission() {
        val data = fixture().let { it.copy(
            assignments = listOf(PiecePartAssignmentEntity(memberId = "m", piecePartId = "b")),
            bindings = it.bindings.map { b -> b.copy(piecePartId = "b") }) }
        error(ErrorCode.UNAUTHORIZED, data)
        val extra = data.copy(extraPermissions = listOf(MemberPiecePartPermissionEntity(memberId = "m", piecePartId = "b")))
        assertEquals("b", value(extra).items.single().part.id)
        assertEquals("b", value(extra.copy(members = extra.members.map { it.copy(defaultPartDefinitionId = null) })).items.single().part.id)
        error(ErrorCode.UNAUTHORIZED, extra.copy(extraPermissions = extra.extraPermissions.map { it.copy(isArchived = true) }))
    }

    @Test fun extraPermissionDoesNotLeakToOtherPiecesOrMembers() {
        val d = fixture()
        val data = d.copy(pieces = d.pieces + PieceEntity(id = "other", title = "Other"),
            parts = d.parts + PiecePartEntity(id = "otherPart", pieceId = "other", partDefinitionId = "e"),
            extraPermissions = listOf(MemberPiecePartPermissionEntity(memberId = "m", piecePartId = "b"),
                MemberPiecePartPermissionEntity(memberId = "otherMember", piecePartId = "otherPart")))
        assertEquals(emptyList<VisiblePart>(), (ScoreResolver(data).visibleParts("m", "other") as DomainResult.Success).value)
    }

    @Test fun archivedDataCannotGrantAccess() {
        val d = fixture()
        error(ErrorCode.MEMBER_MISSING, d.copy(members = d.members.map { it.copy(isArchived = true) }))
        error(ErrorCode.PIECE_MISSING, d.copy(pieces = d.pieces.map { it.copy(isArchived = true) }))
        error(ErrorCode.UNAUTHORIZED, d.copy(permissions = d.permissions.map { it.copy(isArchived = true) }))
        error(ErrorCode.UNAUTHORIZED, d.copy(definitions = d.definitions.map { it.copy(isArchived = true) }))
        error(ErrorCode.PIECE_PART_MISSING, d.copy(parts = d.parts.map { it.copy(isArchived = true) }))
    }

    @Test fun missingDataAndAmbiguousAssignmentsAreExplicitErrors() {
        val d = fixture()
        error(ErrorCode.MEMBER_MISSING, d.copy(members = emptyList()))
        error(ErrorCode.CONCERT_MISSING, d.copy(concerts = emptyList()))
        error(ErrorCode.PIECE_MISSING, d.copy(pieces = emptyList()))
        error(ErrorCode.DEFAULT_PART_MISSING, d.copy(members = d.members.map { it.copy(defaultPartDefinitionId = null) }))
        error(ErrorCode.PIECE_PART_MISSING, d.copy(parts = emptyList()))
        error(ErrorCode.PIECE_PART_MISSING, d.copy(assignments = listOf(PiecePartAssignmentEntity(memberId = "m", piecePartId = "missing"))))
        error(ErrorCode.AMBIGUOUS_ASSIGNMENT, d.copy(assignments = listOf(
            PiecePartAssignmentEntity(memberId = "m", piecePartId = "a"),
            PiecePartAssignmentEntity(memberId = "m", piecePartId = "b"))))
    }

    @Test fun activeVersionNeverFallsBackToLatestAndMustBelongToPdf() {
        val d = fixture()
        val old = d.versions.single()
        val withNew = d.copy(versions = d.versions + old.copy(id = "new", versionNumber = 2))
        assertEquals("v", value(withNew).items.single().segments.single().versionId)
        error(ErrorCode.ACTIVE_VERSION_MISSING, d.copy(pdfs = d.pdfs.map { it.copy(activeVersionId = null) }))
        error(ErrorCode.ACTIVE_VERSION_MISSING, d.copy(versions = emptyList()))
        error(ErrorCode.VERSION_MISMATCH, d.copy(versions = d.versions.map { it.copy(scorePdfId = "otherPdf") }))
        error(ErrorCode.PDF_MISSING, d.copy(pdfs = emptyList()))
        error(ErrorCode.PDF_MISSING, d.copy(pdfs = d.pdfs.map { it.copy(isArchived = true) }))
    }

    @Test fun pageRangesAreInclusiveOneBasedAndBounded() {
        val d = fixture()
        listOf(0 to 1, 3 to 2, 1 to 11, -1 to 2).forEach { (start, end) ->
            error(ErrorCode.INVALID_PAGE_RANGE, d.copy(bindings = d.bindings.map { it.copy(startPage = start, endPage = end) }))
        }
        assertEquals(10, value(d).items.single().segments.single().endPage)
        error(ErrorCode.BINDING_MISSING, d.copy(bindings = emptyList()))
        error(ErrorCode.BINDING_MISSING, d.copy(bindings = d.bindings.map { it.copy(isArchived = true) }))
        error(ErrorCode.AMBIGUOUS_BINDING, d.copy(bindings = d.bindings + d.bindings.single().copy(id = "duplicate")))
    }

    @Test fun movementsAreOrderedCompleteAndBelongToPiece() {
        val d = fixture()
        val first = MovementEntity(id = "first", pieceId = "p", title = "First", sortOrder = 1)
        val second = first.copy(id = "second", title = "Second", sortOrder = 2)
        val binding = d.bindings.single()
        val data = d.copy(movements = listOf(second, first), bindings = listOf(
            binding.copy(id = "secondBinding", movementId = "second", startPage = 5),
            binding.copy(id = "firstBinding", movementId = "first", endPage = 4)))
        assertEquals(listOf("first", "second"), value(data).items.single().segments.map { it.movementId })
        error(ErrorCode.BINDING_MISSING, data.copy(bindings = data.bindings.take(1)))
        error(ErrorCode.MOVEMENT_MISMATCH, data.copy(movements = data.movements.map { it.copy(pieceId = "other") }))
        error(ErrorCode.MOVEMENT_MISMATCH, data.copy(movements = emptyList()))
        error(ErrorCode.AMBIGUOUS_BINDING, data.copy(bindings = data.bindings + binding))
    }

    @Test fun programOrderIsStableAndDisabledOrArchivedItemsAreExcluded() {
        val d = fixture()
        val item = d.items.single()
        val data = d.copy(items = listOf(item.copy(id = "later", sortOrder = 9),
            item.copy(id = "disabled", pieceId = "missing", isEnabled = false),
            item.copy(id = "archived", pieceId = "missing", isArchived = true),
            item.copy(id = "earlier", sortOrder = 1)))
        assertEquals(listOf("earlier", "later"), value(data).items.map { it.itemId })
        assertTrue(value(d.copy(items = emptyList())).items.isEmpty())
        error(ErrorCode.PIECE_MISSING, data.copy(items = data.items + item.copy(id = "bad", pieceId = "missing")))
    }

    @Test fun cacheReadUsesExactVersionAndMemberBeforeShared() {
        val d = fixture()
        val shared = ScoreCacheEntryEntity(scorePdfVersionId = "v", status = CacheStatus.CACHED)
        assertEquals(CacheStatus.NOT_CACHED, value(d).items.single().segments.single().cacheStatus)
        assertEquals(CacheStatus.CACHED, value(d.copy(caches = listOf(shared))).items.single().segments.single().cacheStatus)
        val personal = shared.copy(id = "personal", memberId = "m", status = CacheStatus.FAILED)
        assertEquals(CacheStatus.FAILED, value(d.copy(caches = listOf(shared, personal))).items.single().segments.single().cacheStatus)
        assertEquals(CacheStatus.NOT_CACHED, value(d.copy(caches = listOf(personal.copy(memberId = "other"), shared.copy(scorePdfVersionId = "old")))).items.single().segments.single().cacheStatus)
        error(ErrorCode.AMBIGUOUS_CACHE, d.copy(caches = listOf(shared, shared.copy(id = "duplicate"))))
    }
}
