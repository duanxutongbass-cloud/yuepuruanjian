package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.annotation.AnnotationScope
import com.ensemble.scorelibrary.annotation.AnnotationStore
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

sealed interface PerformanceState {
    data object Idle : PerformanceState
    data class Checking(val checked: Int, val total: Int) : PerformanceState
    data class Ready(val pages: Int) : PerformanceState
    data class Failure(val message: String) : PerformanceState
}

class PerformancePreparation @Inject constructor(private val engine: PdfEngine, private val annotations: AnnotationStore) {
    suspend fun check(member: String, items: List<ResolvedConcertItem>, concert: Boolean, progress: (Int, Int) -> Unit): Int {
        require(items.isNotEmpty()) { "节目单为空" }
        val total = items.sumOf { item -> item.segments.sumOf { it.endPage.toLong() - it.startPage + 1 } }
        require(total in 1..10_000) { "演出页数无效或超过本批次检查上限" }
        var checked = 0
        progress(checked, total.toInt())
        for (item in items) {
            coroutineContext.ensureActive()
            try { engine.inspect(item.segments) }
            catch (failure: PdfFailure) { throw IllegalStateException("${item.title}：${readerErrorText(failure.reason)}", failure) }
            for (segment in item.segments) for (page in segment.startPage..segment.endPage) {
                coroutineContext.ensureActive()
                val ink = annotations.load(AnnotationScope(member, item.pieceId, item.part.id, segment.versionId, page - 1,
                    item.itemId.takeIf { concert }))
                check(!concert || ink.locked) { "${item.title}：共享批注未锁定，请先由管理员锁定演出" }
                progress(++checked, total.toInt())
            }
        }
        return checked
    }
}
