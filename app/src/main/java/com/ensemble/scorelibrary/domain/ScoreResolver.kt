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
            data.pieces.filterNot { it.isArchived }.sortedBy { it.title }.map { PieceSummary(it.id, it.title, it.composer) },
            data.concerts.filterNot { it.isArchived }.sortedWith(compareBy<ConcertEntity> { it.performanceAtEpochMillis ?: Long.MAX_VALUE }.thenBy { it.id })
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

    private fun member(id: String) = data.members.singleOrNull { it.id == id && !it.isArchived }
        ?: fail(ErrorCode.MEMBER_MISSING, id)
    private fun piece(id: String) = data.pieces.singleOrNull { it.id == id && !it.isArchived }
        ?: fail(ErrorCode.PIECE_MISSING, id)

    private fun allowed(memberId: String, pieceId: String): List<PiecePartEntity> {
        member(memberId)
        piece(pieceId)
        val global = data.permissions.filter { it.memberId == memberId && !it.isArchived }
            .map { it.partDefinitionId }.toSet()
        val extra = data.extraPermissions.filter { it.memberId == memberId && !it.isArchived }
            .map { it.piecePartId }.toSet()
        val definitions = data.definitions.filterNot { it.isArchived }.map { it.id }.toSet()
        return data.parts.filter {
            it.pieceId == pieceId && !it.isArchived && it.partDefinitionId in definitions &&
                (it.partDefinitionId in global || it.id in extra)
        }
    }

    private fun visible(part: PiecePartEntity): VisiblePart {
        val definition = data.definitions.single { it.id == part.partDefinitionId && !it.isArchived }
        return VisiblePart(part.id, definition.id, part.displayName ?: definition.name)
    }

    fun visibleParts(memberId: String, pieceId: String): DomainResult<List<VisiblePart>> = result {
        allowed(memberId, pieceId).sortedWith(compareBy<PiecePartEntity> {
            data.definitions.single { d -> d.id == it.partDefinitionId }.sortOrder
        }.thenBy { it.id }).map(::visible)
    }

    private fun selected(memberId: String, pieceId: String): PiecePartEntity {
        val member = member(memberId)
        val allowed = allowed(memberId, pieceId)
        val overrides = data.assignments.filter { assignment ->
            assignment.memberId == memberId && !assignment.isArchived &&
                data.parts.any { it.id == assignment.piecePartId && it.pieceId == pieceId }
        }
        // A dangling assignment must never be silently replaced with the default.
        if (data.assignments.any { it.memberId == memberId && !it.isArchived &&
                data.parts.none { part -> part.id == it.piecePartId } }) {
            fail(ErrorCode.PIECE_PART_MISSING, pieceId)
        }
        if (overrides.size > 1) fail(ErrorCode.AMBIGUOUS_ASSIGNMENT, pieceId)
        val selected = if (overrides.isNotEmpty()) {
            data.parts.singleOrNull { it.id == overrides.single().piecePartId && !it.isArchived }
                ?: fail(ErrorCode.PIECE_PART_MISSING, pieceId)
        } else {
            val default = member.defaultPartDefinitionId ?: fail(ErrorCode.DEFAULT_PART_MISSING, memberId)
            data.parts.singleOrNull { it.pieceId == pieceId && it.partDefinitionId == default && !it.isArchived }
                ?: fail(ErrorCode.PIECE_PART_MISSING, pieceId)
        }
        if (allowed.none { it.id == selected.id }) fail(ErrorCode.UNAUTHORIZED, pieceId)
        return selected
    }

    private fun segments(memberId: String, part: PiecePartEntity): List<ResolvedScoreSegment> {
        val bindings = data.bindings.filter { it.piecePartId == part.id && !it.isArchived }
        if (bindings.isEmpty()) fail(ErrorCode.BINDING_MISSING, part.id)
        if (bindings.groupBy { it.movementId }.any { it.value.size > 1 } ||
            (bindings.any { it.movementId == null } && bindings.size > 1)) {
            fail(ErrorCode.AMBIGUOUS_BINDING, part.id)
        }
        val movements = data.movements.filter { it.pieceId == part.pieceId && !it.isArchived }
        bindings.filter { it.movementId != null }.forEach { binding ->
            if (movements.none { it.id == binding.movementId }) fail(ErrorCode.MOVEMENT_MISMATCH, binding.id)
        }
        if (bindings.none { it.movementId == null } && movements.any { movement ->
                bindings.none { it.movementId == movement.id } }) fail(ErrorCode.BINDING_MISSING, part.id)
        return bindings.sortedWith(compareBy<PartScoreBindingEntity> { binding ->
            movements.firstOrNull { it.id == binding.movementId }?.sortOrder ?: -1
        }.thenBy { it.id }).map { binding ->
            val pdf = data.pdfs.singleOrNull { it.id == binding.scorePdfId && !it.isArchived }
                ?: fail(ErrorCode.PDF_MISSING, binding.id)
            val version = data.versions.singleOrNull { it.id == pdf.activeVersionId }
                ?: fail(ErrorCode.ACTIVE_VERSION_MISSING, pdf.id)
            if (version.scorePdfId != pdf.id) fail(ErrorCode.VERSION_MISMATCH, pdf.id)
            // Page ranges are inclusive and one-based; renderers convert at their boundary.
            if (binding.startPage < 1 || binding.endPage < binding.startPage ||
                binding.endPage > version.pageCount) fail(ErrorCode.INVALID_PAGE_RANGE, binding.id)
            val personal = data.caches.filter { it.scorePdfVersionId == version.id && it.memberId == memberId }
            val shared = data.caches.filter { it.scorePdfVersionId == version.id && it.memberId == null }
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
        val concert = data.concerts.singleOrNull { it.id == concertId && !it.isArchived }
            ?: fail(ErrorCode.CONCERT_MISSING, concertId)
        val items = data.items.filter { it.concertId == concertId && it.isEnabled && !it.isArchived }
            .sortedWith(compareBy<ConcertItemEntity> { it.sortOrder }.thenBy { it.id })
            .map { item ->
                val piece = piece(item.pieceId)
                val part = selected(memberId, piece.id)
                ResolvedConcertItem(item.id, piece.id, piece.title, visible(part), segments(memberId, part))
            }
        ResolvedConcert(concert.id, concert.title, items)
    }
}
