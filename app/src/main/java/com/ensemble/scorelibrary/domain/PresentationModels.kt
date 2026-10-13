package com.ensemble.scorelibrary.domain

data class MemberChoice(val id: String, val name: String)
data class Profile(val id: String, val name: String, val defaultPartName: String?, val canManage: Boolean)
data class PieceSummary(val id: String, val title: String, val composer: String?)
data class ConcertSummary(val id: String, val title: String)
data class Dashboard(
    val members: List<MemberChoice>, val profile: Profile? = null,
    val pieces: List<PieceSummary> = emptyList(), val concerts: List<ConcertSummary> = emptyList()
)
sealed interface ScreenRequest {
    data object Home : ScreenRequest
    data class Piece(val id: String) : ScreenRequest
    data class Concert(val id: String) : ScreenRequest
    data class Viewer(val pieceId: String, val partId: String, val concertId: String? = null,
        val itemId: String? = null) : ScreenRequest
}
sealed interface ScreenContent {
    data object Home : ScreenContent
    data class Piece(val id: String, val title: String, val parts: List<VisiblePart>) : ScreenContent
    data class Concert(val concert: ResolvedConcert) : ScreenContent
    data class Viewer(val score: ResolvedConcertItem, val concert: ResolvedConcert? = null) : ScreenContent
}
data class Presentation(val dashboard: Dashboard, val content: DomainResult<ScreenContent>)

fun ScoreResolver.presentation(memberId: String?, request: ScreenRequest): DomainResult<Presentation> {
    val dashboard = dashboard(memberId)
    if (dashboard is DomainResult.Failure) return dashboard
    val value = (dashboard as DomainResult.Success).value
    if (request == ScreenRequest.Home) return DomainResult.Success(Presentation(value, DomainResult.Success(ScreenContent.Home)))
    if (memberId == null) return DomainResult.Failure(DomainError(ErrorCode.MEMBER_MISSING, ""))
    val content: DomainResult<ScreenContent> = when (request) {
        ScreenRequest.Home -> DomainResult.Success(ScreenContent.Home)
        is ScreenRequest.Piece -> when (val parts = visibleParts(memberId, request.id)) {
            is DomainResult.Failure -> parts
            is DomainResult.Success -> DomainResult.Success(ScreenContent.Piece(request.id,
                value.pieces.first { it.id == request.id }.title, parts.value))
        }
        is ScreenRequest.Concert -> resolveConcert(memberId, request.id).map { ScreenContent.Concert(it) }
        is ScreenRequest.Viewer -> if (request.concertId == null) {
            resolvePart(memberId, request.pieceId, request.partId).map { ScreenContent.Viewer(it) }
        } else {
            resolveConcert(memberId, request.concertId).mapResult { concert ->
                val item = concert.items.firstOrNull {
                    it.itemId == request.itemId && it.pieceId == request.pieceId && it.part.id == request.partId
                }
                if (item == null) DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, request.pieceId))
                else DomainResult.Success(ScreenContent.Viewer(item, concert))
            }
        }
    }
    return DomainResult.Success(Presentation(value, content))
}

inline fun <T, R> DomainResult<T>.map(transform: (T) -> R): DomainResult<R> = when (this) {
    is DomainResult.Success -> DomainResult.Success(transform(value))
    is DomainResult.Failure -> this
}
inline fun <T, R> DomainResult<T>.mapResult(transform: (T) -> DomainResult<R>): DomainResult<R> = when (this) {
    is DomainResult.Success -> transform(value)
    is DomainResult.Failure -> this
}
