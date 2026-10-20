package com.ensemble.scorelibrary.domain

import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.CacheStatus

enum class ErrorCode {
    MEMBER_MISSING, PIECE_MISSING, CONCERT_MISSING, DEFAULT_PART_MISSING,
    PIECE_PART_MISSING, UNAUTHORIZED, AMBIGUOUS_ASSIGNMENT, BINDING_MISSING,
    AMBIGUOUS_BINDING, MOVEMENT_MISMATCH, PDF_MISSING, ACTIVE_VERSION_MISSING,
    VERSION_MISMATCH, INVALID_PAGE_RANGE, AMBIGUOUS_CACHE
}

data class DomainError(val code: ErrorCode, val contextId: String)
sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}

data class VisiblePart(val id: String, val definitionId: String, val name: String)
data class ResolvedScoreSegment(
    val bindingId: String, val movementId: String?, val scorePdfId: String,
    val versionId: String, val managedFilePath: String, val fileHash: String,
    val startPage: Int, val endPage: Int, val cacheStatus: CacheStatus,
    val cachedFilePath: String? = null
)
data class ResolvedConcertItem(
    val itemId: String, val pieceId: String, val title: String,
    val part: VisiblePart, val segments: List<ResolvedScoreSegment>
)
data class ResolvedConcert(val id: String, val title: String, val items: List<ResolvedConcertItem>)

// A transaction-consistent input. Only repositories handle this raw, unfiltered data.
data class LibrarySnapshot(
    val pieces: List<PieceEntity> = emptyList(),
    val movements: List<MovementEntity> = emptyList(),
    val definitions: List<PartDefinitionEntity> = emptyList(),
    val parts: List<PiecePartEntity> = emptyList(),
    val pdfs: List<ScorePdfEntity> = emptyList(),
    val versions: List<ScorePdfVersionEntity> = emptyList(),
    val bindings: List<PartScoreBindingEntity> = emptyList(),
    val members: List<MemberEntity> = emptyList(),
    val permissions: List<MemberPartPermissionEntity> = emptyList(),
    val extraPermissions: List<MemberPiecePartPermissionEntity> = emptyList(),
    val assignments: List<PiecePartAssignmentEntity> = emptyList(),
    val concerts: List<ConcertEntity> = emptyList(),
    val items: List<ConcertItemEntity> = emptyList(),
    val caches: List<ScoreCacheEntryEntity> = emptyList()
)

class ScoreResolver(private val data: LibrarySnapshot) {
    // One transaction snapshot, one set of indexes. Never reuse indexes across authorization changes.
    // Keep groups (rather than overwriting duplicate IDs) so ambiguous input still fails closed.
    private val membersById=data.members.filterNot { it.isArchived }.groupBy { it.id }
    private val piecesById=data.pieces.filterNot { it.isArchived }.groupBy { it.id }
    private val definitionsById=data.definitions.groupBy { it.id }
    private val activeDefinitions=data.definitions.filterNot { it.isArchived }.groupBy { it.id }
    private val partsByPiece=data.parts.groupBy { it.pieceId }
    private val partsById=data.parts.groupBy { it.id }
    private val globalGrants=data.permissions.filterNot { it.isArchived }.groupBy { it.memberId }
        .mapValues { (_,rows) -> rows.map { it.partDefinitionId }.toSet() }
    private val extraGrants=data.extraPermissions.filterNot { it.isArchived }.groupBy { it.memberId }
        .mapValues { (_,rows) -> rows.map { it.piecePartId }.toSet() }
    private val assignmentsByMember=data.assignments.filterNot { it.isArchived }.groupBy { it.memberId }
    private val danglingAssignments=assignmentsByMember.mapValues { (_,rows) -> rows.any { it.piecePartId !in partsById } }
    private val bindingsByPart=data.bindings.filterNot { it.isArchived }.groupBy { it.piecePartId }
    private val movementsByPiece=data.movements.filterNot { it.isArchived }.groupBy { it.pieceId }
    private val pdfsById=data.pdfs.filterNot { it.isArchived }.groupBy { it.id }
    private val versionsById=data.versions.groupBy { it.id }
    private val cachesByVersion=data.caches.groupBy { it.scorePdfVersionId }
    private val concertsById=data.concerts.filterNot { it.isArchived }.groupBy { it.id }
    private val itemsByConcert=data.items.filter { it.isEnabled && !it.isArchived }.groupBy { it.concertId }
    private fun concertVisibleTo(concert: ConcertEntity, member: MemberEntity): Boolean =
        concertAudienceAllows(concert, member)
    private fun summaryPartNames(member: MemberEntity, piece: PieceEntity): List<String> {
        if(member.role != com.ensemble.scorelibrary.core.model.MemberRole.ADMIN)
            return (visibleParts(member.id,piece.id) as? DomainResult.Success)?.value.orEmpty().map { it.name }
        return partsByPiece[piece.id].orEmpty().filterNot { it.isArchived }.mapNotNull { part ->
            activeDefinitions[part.partDefinitionId]?.firstOrNull()
                ?.let { part.displayName ?: it.name }
        }.distinct()
    }
    fun dashboard(memberId: String?): DomainResult<Dashboard> = result {
        val choices = data.members.filterNot { it.isArchived }.sortedBy { it.displayName }
            .map { MemberChoice(it.id, it.displayName) }
        if (memberId == null) return@result Dashboard(choices)
        val member = member(memberId)
        Dashboard(choices, Profile(member.id, member.displayName,
            data.definitions.firstOrNull { definition ->
                definition.id == member.defaultPartDefinitionId && !definition.isArchived &&
                    data.permissions.any { it.memberId == memberId &&
                        it.partDefinitionId == definition.id && !it.isArchived }
            }?.name,
            member.role == com.ensemble.scorelibrary.core.model.MemberRole.ADMIN),
            data.pieces.filterNot { it.isArchived }.sortedBy { it.title }.map { PieceSummary(it.id, it.title, it.composer, it.categoryName, it.organizerSequence,
                summaryPartNames(member, it), it.categoryCode) },
            data.concerts.filter { !it.isArchived && (member.role == com.ensemble.scorelibrary.core.model.MemberRole.ADMIN || concertVisibleTo(it, member)) }.sortedWith(compareBy<ConcertEntity> { it.performanceAtEpochMillis ?: Long.MAX_VALUE }.thenBy { it.id })
                .map { ConcertSummary(it.id, it.title) })
    }

    fun resolvePart(memberId: String, pieceId: String, partId: String): DomainResult<ResolvedConcertItem> = result {
        val piece = piece(pieceId)
        val part = allowed(memberId, pieceId).firstOrNull { it.id == partId }
            ?: fail(ErrorCode.UNAUTHORIZED, pieceId)
        ResolvedConcertItem("", piece.id, piece.title, visible(part), segments(memberId, part))
    }

    private class Invalid(val error: DomainError) : RuntimeException()
    private fun fail(code: ErrorCode, id: String): Nothing = throw Invalid(DomainError(code, id))
    private inline fun <T> result(block: () -> T): DomainResult<T> = try {
        DomainResult.Success(block())
    } catch (error: Invalid) { DomainResult.Failure(error.error) }

    private fun member(id: String) = membersById[id]?.singleOrNull()
        ?: fail(ErrorCode.MEMBER_MISSING, id)
    private fun piece(id: String) = piecesById[id]?.singleOrNull()
        ?: fail(ErrorCode.PIECE_MISSING, id)

    private fun allowed(memberId: String, pieceId: String): List<PiecePartEntity> {
        member(memberId)
        piece(pieceId)
        val global = globalGrants[memberId].orEmpty()
        val extra = extraGrants[memberId].orEmpty()
        return partsByPiece[pieceId].orEmpty().filter {
            !it.isArchived && it.partDefinitionId in activeDefinitions &&
                (it.partDefinitionId in global || it.id in extra)
        }
    }

    private fun visible(part: PiecePartEntity): VisiblePart {
        val definition = activeDefinitions[part.partDefinitionId].orEmpty().single()
        return VisiblePart(part.id, definition.id, part.displayName ?: definition.name)
    }

    fun visibleParts(memberId: String, pieceId: String): DomainResult<List<VisiblePart>> = result {
        allowed(memberId, pieceId).sortedWith(compareBy<PiecePartEntity> {
            definitionsById[it.partDefinitionId].orEmpty().single().sortOrder
        }.thenBy { it.id }).map(::visible)
    }

    private fun selected(memberId: String, pieceId: String): PiecePartEntity {
        val member = member(memberId)
        val allowed = allowed(memberId, pieceId)
        val overrides = assignmentsByMember[memberId].orEmpty().filter { assignment ->
                partsById[assignment.piecePartId].orEmpty().any { it.pieceId == pieceId }
        }
        // A dangling assignment must never be silently replaced with the default.
        if (danglingAssignments[memberId]==true) {
            fail(ErrorCode.PIECE_PART_MISSING, pieceId)
        }
        if (overrides.size > 1) fail(ErrorCode.AMBIGUOUS_ASSIGNMENT, pieceId)
        val selected = if (overrides.isNotEmpty()) {
            partsById[overrides.single().piecePartId].orEmpty().singleOrNull { !it.isArchived }
                ?: fail(ErrorCode.PIECE_PART_MISSING, pieceId)
        } else {
            val default = member.defaultPartDefinitionId ?: fail(ErrorCode.DEFAULT_PART_MISSING, memberId)
            partsByPiece[pieceId].orEmpty().singleOrNull { it.partDefinitionId == default && !it.isArchived }
                ?: fail(ErrorCode.PIECE_PART_MISSING, pieceId)
        }
        if (allowed.none { it.id == selected.id }) fail(ErrorCode.UNAUTHORIZED, pieceId)
        return selected
    }

    private fun segments(memberId: String, part: PiecePartEntity): List<ResolvedScoreSegment> {
        val bindings = bindingsByPart[part.id].orEmpty()
        if (bindings.isEmpty()) fail(ErrorCode.BINDING_MISSING, part.id)
        if (bindings.groupBy { it.movementId }.any { it.value.size > 1 } ||
            (bindings.any { it.movementId == null } && bindings.size > 1)) {
            fail(ErrorCode.AMBIGUOUS_BINDING, part.id)
        }
        val movements = movementsByPiece[part.pieceId].orEmpty()
        bindings.filter { it.movementId != null }.forEach { binding ->
            if (movements.none { it.id == binding.movementId }) fail(ErrorCode.MOVEMENT_MISMATCH, binding.id)
        }
        if (bindings.none { it.movementId == null } && movements.any { movement ->
                bindings.none { it.movementId == movement.id } }) fail(ErrorCode.BINDING_MISSING, part.id)
        return bindings.sortedWith(compareBy<PartScoreBindingEntity> { binding ->
            movements.firstOrNull { it.id == binding.movementId }?.sortOrder ?: -1
        }.thenBy { it.id }).map { binding ->
            val pdf = pdfsById[binding.scorePdfId]?.singleOrNull()
                ?: fail(ErrorCode.PDF_MISSING, binding.id)
            val version = versionsById[pdf.activeVersionId]?.singleOrNull()
                ?: fail(ErrorCode.ACTIVE_VERSION_MISSING, pdf.id)
            if (version.scorePdfId != pdf.id) fail(ErrorCode.VERSION_MISMATCH, pdf.id)
            // Page ranges are inclusive and one-based; renderers convert at their boundary.
            if (binding.startPage < 1 || binding.endPage < binding.startPage ||
                binding.endPage > version.pageCount) fail(ErrorCode.INVALID_PAGE_RANGE, binding.id)
            val personal = cachesByVersion[version.id].orEmpty().filter { it.memberId == memberId }
            val shared = cachesByVersion[version.id].orEmpty().filter { it.memberId == null }
            val cache = personal.ifEmpty { shared }
            if (cache.size > 1) fail(ErrorCode.AMBIGUOUS_CACHE, version.id)
            ResolvedScoreSegment(binding.id, binding.movementId, pdf.id, version.id,
                version.managedFilePath, version.fileHash, binding.startPage, binding.endPage,
                cache.singleOrNull()?.status ?: CacheStatus.NOT_CACHED,
                cache.singleOrNull()?.cachedFilePath)
        }
    }

    fun resolvePiece(memberId: String, pieceId: String): DomainResult<ResolvedConcertItem> = result {
        val piece = piece(pieceId)
        val part = selected(memberId, pieceId)
        ResolvedConcertItem("", piece.id, piece.title, visible(part), segments(memberId, part))
    }

    fun resolveConcert(memberId: String, concertId: String): DomainResult<ResolvedConcert> = result {
        member(memberId)
        val concert = concertsById[concertId]?.singleOrNull()
            ?: fail(ErrorCode.CONCERT_MISSING, concertId)
        if (!concertVisibleTo(concert, member(memberId))) fail(ErrorCode.UNAUTHORIZED, concertId)
        val items = itemsByConcert[concertId].orEmpty()
            .sortedWith(compareBy<ConcertItemEntity> { it.sortOrder }.thenBy { it.id })
            .map { item ->
                val piece = piece(item.pieceId)
                val part = selected(memberId, piece.id)
                ResolvedConcertItem(item.id, piece.id, piece.title, visible(part), segments(memberId, part))
            }
        ResolvedConcert(concert.id, concert.title, items)
    }
}
