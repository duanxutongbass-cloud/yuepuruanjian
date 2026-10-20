package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.annotation.AnnotationData
import com.ensemble.scorelibrary.annotation.AnnotationScope
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal class PreparedPerformance(val pages: Int, val ink: Map<AnnotationScope, AnnotationData>,
    val owner: String?, val deadline: Long, private val authorize: (() -> Unit) -> Unit) {
    private val entered = java.util.concurrent.atomic.AtomicBoolean(false)
    fun enter(block: () -> Unit) = authorize {
        check(entered.compareAndSet(false, true)) { "此次准备已使用，请重新核验演出" }
        block()
    }
}

/** An in-memory read capability for this exact entered scene. Never grants repository or network access. */
class FrozenPerformanceRun internal constructor(val id: String, val member: String, val context: String,
    val anchor: ResolvedConcertItem, val concert: ResolvedConcert, val owner: String?, val deadline: Long,
    internal val ink: Map<AnnotationScope, AnnotationData>, private val elapsedStart: Long,
    private val wallStart: Long) {
    internal fun valid(wall: Long, elapsed: Long): Boolean = elapsed >= elapsedStart && wall >= wallStart - 300 &&
        (deadline == Long.MAX_VALUE || wall < deadline && elapsed - elapsedStart < (deadline - wallStart).coerceAtLeast(0) * 1000)
    fun matches(member: String?, request: ScreenRequest): Boolean = member == this.member && request is ScreenRequest.Viewer &&
        request.concertId == concert.id && request.itemId == anchor.itemId && request.pieceId == anchor.pieceId && request.partId == anchor.part.id
    fun presentation(): Presentation = Presentation(Dashboard(listOf(MemberChoice(member, "当前演出")), Profile(member, "当前演出", null, false)),
        DomainResult.Success(ScreenContent.Viewer(anchor, concert)))
}

@Singleton
class PerformanceRuns @Inject constructor() {
    private val mutable = MutableStateFlow<FrozenPerformanceRun?>(null)
    val active = mutable.asStateFlow()
    @Synchronized internal fun begin(member: String, context: String, anchor: ResolvedConcertItem, concert: ResolvedConcert,
        prepared: PreparedPerformance): FrozenPerformanceRun {
        require(concert.items.isNotEmpty() && anchor in concert.items && prepared.pages > 0 && prepared.ink.isNotEmpty())
        val pageCount = concert.items.sumOf { item -> item.segments.sumOf { it.endPage.toLong() - it.startPage + 1 } }
        require(pageCount in 1..10_000 && prepared.pages.toLong() == pageCount)
        val required = concert.items.flatMap { item -> item.segments.flatMap { segment -> (segment.startPage..segment.endPage).map { page ->
            AnnotationScope(member, item.pieceId, item.part.id, segment.versionId, page - 1, item.itemId)
        } } }.toSet()
        require(prepared.ink.keys == required) { "演出页面尚未完整准备" }
        require(prepared.ink.all { (scope, data) -> scope.member == member && scope.item != null && data.locked &&
            concert.items.any { item -> item.itemId == scope.item && item.pieceId == scope.piece && item.part.id == scope.part &&
                item.segments.any { it.versionId == scope.version && scope.page + 1 in it.startPage..it.endPage } } })
        val run = FrozenPerformanceRun(UUID.randomUUID().toString(), member, context, anchor, concert, prepared.owner, prepared.deadline,
            prepared.ink.toMap(), android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis() / 1000)
        prepared.enter {
            check(run.valid(System.currentTimeMillis() / 1000, android.os.SystemClock.elapsedRealtime())) { "演出核验期限已到，请联网重新准备" }
            mutable.value = run
        }
        return run
    }
    @Synchronized fun current(): FrozenPerformanceRun? {
        val run = mutable.value ?: return null
        if (!run.valid(System.currentTimeMillis() / 1000, android.os.SystemClock.elapsedRealtime())) { mutable.value = null; return null }
        return run
    }
    @Synchronized fun end(id: String? = null) { if (id == null || mutable.value?.id == id) mutable.value = null }
    fun viewer(member: String?, request: ScreenRequest) = current()?.takeIf { it.matches(member, request) }
    internal fun page(id: String?, scope: AnnotationScope): AnnotationData? = current()?.takeIf { it.id == id && it.member == scope.member }?.ink?.get(scope)
}
