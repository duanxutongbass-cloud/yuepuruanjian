package com.ensemble.scorelibrary.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.sync.TeamLibraryScope
import com.ensemble.scorelibrary.ui.PreviewImage
import com.ensemble.scorelibrary.ui.errorText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.sqrt

data class ComparisonTarget(val member: String, val piece: String, val part: String? = null)
data class ComparisonPane(val target: ComparisonTarget? = null, val parts: List<VisiblePart> = emptyList(),
    val summary: PieceSummary? = null, val score: ResolvedConcertItem? = null, val image: PreviewImage? = null,
    val index: Int = 0, val total: Int = 0, val sourcePage: Int? = null, val loading: Boolean = false, val error: String? = null)
data class ComparisonState(val member: String? = null, val panes: List<ComparisonPane> = List(2) { ComparisonPane() })

/** Two explicit Domain viewers, one bounded native renderer, no ink/history/active-version writes. */
@HiltViewModel
class ScoreComparisonViewModel @Inject constructor(private val repository: PresentationRepository,
    private val engine: PdfEngine, private val team: TeamLibraryScope? = null) : ViewModel() {
    private val mutable = MutableStateFlow(ComparisonState())
    val state = mutable.asStateFlow()
    private class Slot {
        var observer: Job? = null
        var render: Job? = null
        var revision = 0L
        var binding = 0L
        var width = 0
        var height = 0
    }
    private val slots = List(2) { Slot() }
    private val nativeLock = Mutex()
    private var owner: com.ensemble.scorelibrary.sync.TeamCollection? = null
    private var paneOwner: Any? = null
    private var expiry: Job? = null
    init { if (team != null) viewModelScope.launch {
        team.active.collect { current -> if (mutable.value.member != null && current !== owner) clear() }
    } }

    fun open(member: String, viewOwner: Any) {
        if (mutable.value.member == member && paneOwner === viewOwner) return
        clear(); owner = team?.active?.value; paneOwner = viewOwner
        mutable.value = ComparisonState(member)
        try { guard() } catch (_: Exception) { clear(); return }
        if (owner != null) expiry = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                try { guard() } catch (_: Exception) { clear(); return@launch }
            }
        }
    }
    fun current(member: String, viewOwner: Any): Boolean = mutable.value.member == member && paneOwner === viewOwner &&
        runCatching { guard() }.isSuccess
    private fun guard(): Long? {
        if (team == null) return null
        check(team.active.value === owner) { "团队账号已切换" }
        return owner?.let {
            check(it.member == mutable.value.member) { "不能读取其他成员的曲谱" }
            team.preparationStamp(it)
        }
    }
    private fun pane(side: Int) = mutable.value.panes[side]
    private fun replace(side: Int, value: ComparisonPane) {
        val old = pane(side).image
        mutable.value = mutable.value.copy(panes = mutable.value.panes.toMutableList().also { it[side] = value })
        if (old !== value.image) old?.releaseOwner()
    }
    fun choosePiece(side: Int, piece: String) {
        require(side in 0..1)
        val member = mutable.value.member ?: return
        select(side, ComparisonTarget(member, piece))
    }
    fun choosePart(side: Int, part: String) {
        require(side in 0..1)
        val before = pane(side)
        val target = before.target ?: return
        if (target.part != null || before.parts.none { it.id == part }) return
        select(side, target.copy(part = part))
    }
    fun reselectPart(side: Int) { pane(side).target?.let { select(side, it.copy(part = null)) } }
    private fun request(target: ComparisonTarget): ScreenRequest = target.part?.let { ScreenRequest.Viewer(target.piece, it) }
        ?: ScreenRequest.Piece(target.piece)
    private fun select(side: Int, target: ComparisonTarget) {
        val slot = slots[side]
        slot.observer?.cancel(); slot.render?.cancel(); slot.revision++
        val binding = ++slot.binding
        replace(side, ComparisonPane(target = target, loading = true))
        slot.observer = viewModelScope.launch {
            try {
                guard()
                repository.observe(target.member, request(target)).collect { result ->
                    if (slot.binding != binding || mutable.value.member != target.member) return@collect
                    try {
                        guard()
                        val presentation = unwrap(result)
                        check(presentation.dashboard.profile?.id == target.member) { "账号已变化" }
                        val content = unwrap(presentation.content)
                        val summary = presentation.dashboard.pieces.firstOrNull { it.id == target.piece }
                        if (target.part == null) {
                            val choice = content as? ScreenContent.Piece ?: error("无法读取声部")
                            check(choice.id == target.piece)
                            replace(side, ComparisonPane(target = target, parts = choice.parts, summary = summary))
                        } else {
                            val viewer = content as? ScreenContent.Viewer ?: error("无法读取分谱")
                            val score = viewer.score
                            check(viewer.concert == null && score.itemId.isEmpty() && score.pieceId == target.piece && score.part.id == target.part)
                            val previous = pane(side)
                            if (previous.score == score && previous.summary == summary && (previous.image != null || previous.loading)) return@collect
                            val changed = previous.score?.let { source(it) != source(score) } ?: true
                            val pages = PageSequence(score.segments)
                            val position = if (changed) 0 else previous.index.coerceIn(0, pages.count - 1)
                            slot.render?.cancel(); slot.revision++
                            replace(side, ComparisonPane(target, summary = summary, score = score, index = position, total = pages.count))
                            render(side)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { fail(side, target, e) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (slot.binding == binding) fail(side, target, e) }
        }
    }
    private fun <T> unwrap(result: DomainResult<T>): T = when (result) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error(errorText(result.error.code))
    }
    private fun source(score: ResolvedConcertItem) = score.copy(title = "", part = score.part.copy(name = ""))
    fun viewport(side: Int, width: Int, height: Int) {
        require(side in 0..1)
        val (w, h) = comparisonSize(width, height)
        val slot = slots[side]
        if (slot.width == w && slot.height == h) return
        slot.width = w; slot.height = h; render(side)
    }
    fun move(side: Int, delta: Int) {
        require(side in 0..1)
        if (delta !in listOf(-1, 1)) return
        val before = pane(side)
        if (before.score == null || before.total == 0) return
        val next = (before.index + delta).coerceIn(0, before.total - 1)
        if (next == before.index) return
        replace(side, before.copy(index = next)); render(side)
    }
    fun retry(side: Int) { pane(side).target?.let { select(side, it) } }
    private fun render(side: Int) {
        val slot = slots[side]
        slot.render?.cancel()
        val revision = ++slot.revision
        val before = pane(side)
        val score = before.score ?: return
        val target = before.target ?: return
        replace(side, before.copy(image = null, sourcePage = null, loading = slot.width > 0 && slot.height > 0, error = null))
        if (slot.width == 0 || slot.height == 0) return
        val width = slot.width; val height = slot.height; val index = before.index
        slot.render = viewModelScope.launch {
            var pending: PreviewImage? = null
            try {
                nativeLock.withLock {
                    ensureActive()
                    val stamp = guard()
                    val sequence = PageSequence(score.segments)
                    val page = sequence.page(index)
                    engine.inspect(listOf(page.segment)); ensureActive()
                    pending = PreviewImage(engine.render(page, width, height, FitMode.PAGE, 0f))
                    ensureActive()
                    check(guard() == stamp) { "曲谱授权已更新，请重试" }
                    // Check a fresh transactional projection too, including revocation not yet emitted to this observer.
                    val fresh = unwrap(repository.observe(target.member, request(target)).first())
                    check(fresh.dashboard.profile?.id == target.member && guard() == stamp)
                    val viewer = unwrap(fresh.content) as? ScreenContent.Viewer
                    check(viewer?.concert == null && viewer?.score == score) { "曲谱版本或授权已变化，请重试" }
                    ensureActive()
                    if (slot.revision == revision && pane(side).target == target && mutable.value.member == target.member) {
                        replace(side, pane(side).copy(image = pending, sourcePage = page.pageIndex + 1, loading = false))
                        pending = null
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (slot.revision == revision) fail(side, target, e) }
            finally { pending?.releaseOwner() }
        }
    }
    private fun fail(side: Int, target: ComparisonTarget, error: Exception) {
        val slot = slots[side]; slot.render?.cancel(); slot.revision++
        val message = if (error is PdfFailure) readerErrorText(error.reason) else "曲谱或账号已变化，请重新选择或重试"
        replace(side, ComparisonPane(target = target, error = message))
    }
    fun clear(viewOwner: Any? = null) {
        if (viewOwner != null && paneOwner !== viewOwner) return
        expiry?.cancel(); expiry = null
        slots.forEachIndexed { index, slot ->
            slot.observer?.cancel(); slot.render?.cancel(); slot.binding++; slot.revision++
            slot.width = 0; slot.height = 0; pane(index).image?.releaseOwner()
        }
        mutable.value = ComparisonState(); paneOwner = null; owner = null
    }
    override fun onCleared() { clear(); super.onCleared() }
}

/** At most two displayed owners, two attached retired views, and one in-flight native frame. */
internal fun comparisonSize(width: Int, height: Int, budget: Long = minOf(2_160_000L, Runtime.getRuntime().maxMemory() / 80)): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || budget < 1) return 0 to 0
    val scale = minOf(1.0, 1200.0 / width, 1800.0 / height, sqrt(budget.toDouble() / width.toDouble() / height.toDouble()))
    val w = minOf((width * scale).toInt().coerceAtLeast(1), budget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    val h = minOf((height * scale).toInt().coerceAtLeast(1), (budget / w).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    return w to h
}
