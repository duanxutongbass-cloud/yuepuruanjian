package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.annotation.AnnotationScope
import com.ensemble.scorelibrary.annotation.AnnotationStore
import com.ensemble.scorelibrary.annotation.InkCodec
import com.ensemble.scorelibrary.annotation.AnnotationData
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

class PerformancePreparation @Inject constructor(private val engine: PdfEngine, private val annotations: AnnotationStore,
    private val team: com.ensemble.scorelibrary.sync.TeamLibraryScope? = null) {
    suspend fun check(member: String, items: List<ResolvedConcertItem>, concert: Boolean, progress: (Int, Int) -> Unit): Int =
        prepare(member, items, concert, progress).pages
    internal suspend fun prepare(member: String, items: List<ResolvedConcertItem>, concert: Boolean, progress: (Int, Int) -> Unit): PreparedPerformance {
        val collection = team?.active?.value
        if (collection != null) check(collection.member == member) { "演出账号不匹配" }
        val stamp = collection?.let { team!!.preparationStamp(it) }
        require(items.isNotEmpty()) { "节目单为空" }
        val total = items.sumOf { item -> item.segments.sumOf { it.endPage.toLong() - it.startPage + 1 } }
        require(total in 1..10_000) { "演出页数无效或超过本批次检查上限" }
        var checked = 0
        var inkBytes = 0L
        val frozen = linkedMapOf<AnnotationScope, AnnotationData>()
        progress(checked, total.toInt())
        for (item in items) {
            coroutineContext.ensureActive()
            try { engine.inspect(item.segments) }
            catch (failure: PdfFailure) { throw IllegalStateException("${item.title}：${readerErrorText(failure.reason)}", failure) }
            for (segment in item.segments) for (page in segment.startPage..segment.endPage) {
                coroutineContext.ensureActive()
                val scope = AnnotationScope(member, item.pieceId, item.part.id, segment.versionId, page - 1, item.itemId.takeIf { concert })
                val ink = annotations.load(scope)
                check(!concert || ink.locked) { "${item.title}：共享批注未锁定，请先由管理员锁定演出" }
                val personal = InkCodec.encode(ink.personal); val shared = InkCodec.encode(ink.shared)
                inkBytes += personal.toByteArray(Charsets.UTF_8).size + shared.toByteArray(Charsets.UTF_8).size
                check(inkBytes <= 32L * 1024 * 1024) { "本场批注超过离线演出准备上限，请缩减节目单后重试" }
                frozen[scope] = ink.copy(personal = InkCodec.decode(personal), shared = InkCodec.decode(shared), draft = emptyList(),
                    admin = false, personalConflict = null, sharedConflict = null)
                progress(++checked, total.toInt())
            }
        }
        val authorize: (() -> Unit) -> Unit = if (collection == null) { block ->
            check(team?.active?.value == null) { "演出账号已切换" }; block()
        } else { block -> team!!.enterPrepared(collection, checkNotNull(stamp), block) }
        authorize { }
        return PreparedPerformance(checked, frozen.toMap(), collection?.key, collection?.expiresAt ?: Long.MAX_VALUE, authorize)
    }
}
