package com.ensemble.scorelibrary.pdf

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import com.ensemble.scorelibrary.domain.ResolvedConcert
import com.ensemble.scorelibrary.core.model.PedalAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject

sealed interface PdfState {
    data object Loading : PdfState
    data class Failure(val error: ReaderError) : PdfState
    data class Ready(val frame: PdfFrame, val index: Int, val total: Int, val sourcePage: Int, val mode: FitMode,
        val reference: PdfPageRef) : PdfState
}

@HiltViewModel
class PdfReaderViewModel @Inject constructor(private val engine: PdfEngine, private val saved: SavedStateHandle,
    private val preparation: PerformancePreparation) : ViewModel() {
    private val mutablePerformance = MutableStateFlow<PerformanceState>(PerformanceState.Idle)
    val performance = mutablePerformance.asStateFlow()
    private var preparationJob: Job? = null
    private var preparationEpoch = 0L
    private val mutable = MutableStateFlow<PdfState>(PdfState.Loading)
    val state = mutable.asStateFlow()
    private val currentBinding = MutableStateFlow<Pair<String, ResolvedConcertItem>?>(null)
    val binding = currentBinding.asStateFlow()
    private val currentAnchor = MutableStateFlow<Pair<String, ResolvedConcertItem>?>(null)
    val anchor = currentAnchor.asStateFlow()
    private val mutablePlayMode = MutableStateFlow(false)
    val playMode = mutablePlayMode.asStateFlow()
    private val mutableFullscreen = MutableStateFlow(false)
    val fullscreen = mutableFullscreen.asStateFlow()
    fun setFullscreen(value: Boolean) {
        if (value && (mutable.value !is PdfState.Ready || mutablePerformance.value is PerformanceState.Checking)) return
        mutableFullscreen.value = value
        programKey?.let { saved["fullscreen-$it"] = value }
    }
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private var noticeJob: Job? = null
    private var cursor: ConcertCursor? = null
    private var programIndex = 0
    private var programKey: String? = null
    private var programContext = ""
    private data class Request(val member: String, val context: String, val item: ResolvedConcertItem, val concert: ResolvedConcert?)
    private var pendingRequest: Request? = null
    private val lock = Mutex()
    private var job: Job? = null
    private var sequence: PageSequence? = null
    private var identity: String? = null
    private var score: ResolvedConcertItem? = null
    private var index = 0
    private var mode = FitMode.PAGE
    private var scroll = 0f
    private var readerView = ReaderView()
    private var width = 0
    private var height = 0
    private var inspected = false
    private var generation = 0L

    fun bind(member: String, context: String, item: ResolvedConcertItem, concert: ResolvedConcert? = null) {
        val items = concert?.items ?: listOf(item)
        val signature = listOf(member, context, item.itemId, concert?.id.orEmpty()) + items.flatMap { entry ->
            listOf(entry.itemId, entry.pieceId, entry.part.id, entry.part.definitionId, entry.title, entry.part.name) + entry.segments.flatMap {
                listOf(it.bindingId, it.movementId.orEmpty(), it.scorePdfId, it.versionId, it.managedFilePath,
                    it.cachedFilePath.orEmpty(), it.cacheStatus.name, it.fileHash, it.startPage.toString(), it.endPage.toString()) }
        }
        val key = digest(signature)
        if (programKey == key && currentAnchor.value == member to item) return
        stopPreparation()
        mutablePlayMode.value = false
        mutableFullscreen.value = false
        if (programKey != null && cursor != null && concert != null && currentAnchor.value?.first == member &&
            currentAnchor.value?.second?.itemId == item.itemId && programKey != key) {
            pendingRequest = Request(member, context, item, concert)
            job?.cancel(); generation++; currentAnchor.value = member to item; currentBinding.value = null
            mutable.value = PdfState.Failure(ReaderError.PROGRAM_CHANGED)
            return
        }
        cursor = try { concert?.let { ConcertCursor(it) } } catch (_: IllegalArgumentException) {
            job?.cancel(); generation++; currentAnchor.value = member to item; currentBinding.value = null
            sequence = null; cursor = null; identity = null; score = null; programKey = null; pendingRequest = null
            mutable.value = PdfState.Failure(ReaderError.INVALID_PAGE_RANGE); return
        }
        val selected = concert?.items?.indexOfFirst { it == item } ?: 0
        if (selected < 0) {
            job?.cancel(); generation++; currentAnchor.value = member to item; currentBinding.value = null
            sequence = null; cursor = null; identity = null; score = null; programKey = null; pendingRequest = null
            mutable.value = PdfState.Failure(ReaderError.PROGRAM_CHANGED); return
        }
        currentAnchor.value = member to item; programKey = key; programContext = context
        mutableFullscreen.value = saved.get<Boolean>("fullscreen-$key") ?: false
        pendingRequest = null; mutableNotice.value = null
        val restorePlay = saved.get<Boolean>("play-$key") ?: false
        programIndex = concert?.items?.indexOfFirst { it.itemId == saved.get<String>("item-$key") }
            ?.takeIf { it >= 0 } ?: selected
        val active = concert?.items?.get(programIndex) ?: item
        activate(member, active)
        if (restorePlay) preparePerformance()
    }
    private fun digest(values: List<String>) = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

    private fun activate(member: String, item: ResolvedConcertItem, forcedPage: Int? = null) {
        val context = if (cursor == null) programContext else "$programKey:${item.itemId}"
        val signature = listOf(member, context, item.pieceId, item.part.id) + item.segments.flatMap {
            listOf(it.bindingId, it.versionId, it.fileHash, it.startPage.toString(), it.endPage.toString()) }
        val key = digest(signature)
        job?.cancel(); generation++
        mutable.value = PdfState.Loading
        currentBinding.value = member to item
        identity = key; score = item; inspected = false; scroll = 0f
        readerView = ReaderView(
            (saved.get<Float>("zoom-$key") ?: 1f).coerceIn(1f, 4f),
            (saved.get<Float>("pan-x-$key") ?: .5f).coerceIn(0f, 1f),
            (saved.get<Float>("pan-y-$key") ?: 0f).coerceIn(0f, 1f))
        sequence = try { PageSequence(item.segments) } catch (_: IllegalArgumentException) {
            mutable.value = PdfState.Failure(ReaderError.INVALID_PAGE_RANGE); null
        }
        index = (forcedPage ?: saved.get<Int>("page-$key") ?: 0).coerceIn(0, (sequence?.count ?: 1) - 1)
        saved["page-$key"] = index
        if (forcedPage == null) mode = saved.get<String>("program-fit-$programKey")
            ?.let { runCatching { FitMode.valueOf(it) }.getOrNull() }
            ?: saved.get<String>("mode-$key")?.let { runCatching { FitMode.valueOf(it) }.getOrNull() } ?: FitMode.PAGE
        render()
    }
    fun viewport(w: Int, h: Int) {
        if (width == w && height == h) return
        width = w; height = h; render()
    }
    fun action(action: ReaderAction) {
        action(if (action == ReaderAction.NEXT_PAGE) PedalAction.NEXT_PAGE else PedalAction.PREVIOUS_PAGE)
    }
    fun action(action: PedalAction) {
        if (mutablePerformance.value is PerformanceState.Checking) return
        if (mutable.value is PdfState.Loading || pendingRequest != null) return
        if (action == PedalAction.TOGGLE_PLAY_MODE) {
            if (mutable.value !is PdfState.Ready) return
            if (mutablePlayMode.value) {
                mutablePlayMode.value = false; stopPreparation()
                programKey?.let { saved["play-$it"] = false }
            } else preparePerformance()
            return
        }
        if (action == PedalAction.NO_OP || mutable.value is PdfState.Failure && action == PedalAction.NEXT_PAGE) return
        val pages = sequence ?: return
        val program = cursor
        val next = if (program != null) {
            val position = program.move(ConcertPosition(programIndex, index), action)
            if (position == null) { boundary(action); return }
            if (position.item != programIndex) {
                programIndex = position.item
                val item = program.concert.items[programIndex]
                programKey?.let { saved["item-$it"] = item.itemId }
                mutableNotice.value = null
                activate(requireNotNull(currentAnchor.value).first, item, position.page)
                return
            }
            position.page
        } else pages.move(index, if (action == PedalAction.NEXT_PAGE) ReaderAction.NEXT_PAGE else ReaderAction.PREVIOUS_PAGE)
        if (next == index) { boundary(action); return }
        index = next; scroll = 0f
        mutableNotice.value = null
        identity?.let { saved["page-$it"] = index }
        render()
    }
    fun nextPage() = action(ReaderAction.NEXT_PAGE)
    fun previousPage() = action(ReaderAction.PREVIOUS_PAGE)
    private fun stopPreparation() {
        preparationJob?.cancel(); preparationEpoch++
        mutablePerformance.value = PerformanceState.Idle
    }
    fun cancelPreparation() { stopPreparation(); programKey?.let { saved["play-$it"] = false } }
    fun invalidatePerformance(message: String) {
        stopPreparation(); mutablePlayMode.value = false
        mutablePerformance.value = PerformanceState.Failure(message)
        programKey?.let { saved["play-$it"] = false }
    }
    private fun preparePerformance() {
        val member = currentAnchor.value?.first ?: return
        val items = cursor?.concert?.items ?: listOfNotNull(score)
        val key = programKey ?: return
        val token = ++preparationEpoch
        mutablePerformance.value = PerformanceState.Checking(0, 0)
        preparationJob = viewModelScope.launch {
            try {
                val count = lock.withLock {
                    preparation.check(member, items, cursor != null) { checked, total ->
                        if (token == preparationEpoch) mutablePerformance.value = PerformanceState.Checking(checked, total)
                    }
                }
                mutable.first { it is PdfState.Ready || it is PdfState.Failure }
                ensureActive()
                if (token == preparationEpoch && key == programKey && pendingRequest == null && mutable.value is PdfState.Ready) {
                    mutablePerformance.value = PerformanceState.Ready(count)
                    mutablePlayMode.value = true; saved["play-$key"] = true
                } else if (token == preparationEpoch) mutablePerformance.value = PerformanceState.Idle
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == preparationEpoch) {
                    mutablePerformance.value = PerformanceState.Failure(if (failure is IllegalStateException || failure is IllegalArgumentException)
                        failure.message ?: "演出准备失败" else "演出准备失败，请重试")
                    mutablePlayMode.value = false; saved["play-$key"] = false
                }
            }
        }
    }
    fun itemNumber(): Pair<Int, Int>? = cursor?.let { programIndex + 1 to it.concert.items.size }
    private fun boundary(action: PedalAction) {
        feedback(if (action == PedalAction.NEXT_PAGE) "已到最后一页" else "已到第一页")
    }
    fun feedback(message: String) {
        mutableNotice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(2000); mutableNotice.value = null }
    }
    fun fit(value: FitMode) {
        if (mode == value) return
        mode = value; scroll = 0f; readerView = ReaderView(); saveView()
        identity?.let { saved["mode-$it"] = mode.name }
        programKey?.let { saved["program-fit-$it"] = mode.name }
        render()
    }
    fun scrollTo(fraction: Float) { scroll = fraction.coerceIn(0f, 1f); readerView = readerView.copy(y = scroll); saveView(); render(debounce = true) }
    fun transform(zoomFactor: Float, dx: Float, dy: Float, focusX: Float? = null, focusY: Float? = null) {
        if (!zoomFactor.isFinite() || zoomFactor <= 0 || !dx.isFinite() || !dy.isFinite()) return
        if (zoomFactor == 1f && dx == 0f && dy == 0f) return
        val ready = mutable.value as? PdfState.Ready ?: return
        if (mutablePerformance.value is PerformanceState.Checking) return
        val nextZoom = (readerView.zoom * zoomFactor).coerceIn(1f, 4f)
        val old = PdfPageTransform.view(ready.frame.transform.page, width, height, mode, readerView)
        val focus = PagePoint(focusX?.takeIf { it.isFinite() } ?: width / 2f, focusY?.takeIf { it.isFinite() } ?: height / 2f)
        val anchor = old.screenToPage(focus)
        val t = PdfPageTransform.view(ready.frame.transform.page, width, height, mode, readerView.copy(zoom = nextZoom))
        val overflowX = (t.page.width * t.scale - width).coerceAtLeast(0f)
        val overflowY = (t.page.height * t.scale - height).coerceAtLeast(0f)
        readerView = ReaderView(nextZoom,
            if (overflowX > 0) (if (anchor != null) (anchor.x * t.scale - focus.x - dx) / overflowX else readerView.x - dx / overflowX).coerceIn(0f, 1f) else .5f,
            if (overflowY > 0) (if (anchor != null) (anchor.y * t.scale - focus.y - dy) / overflowY else readerView.y - dy / overflowY).coerceIn(0f, 1f) else 0f)
        saveView(); render(debounce = true)
    }
    fun resetView() { readerView = ReaderView(); saveView(); render() }
    fun zoom() = readerView.zoom
    private fun saveView() { identity?.let { key ->
        saved["zoom-$key"] = readerView.zoom; saved["pan-x-$key"] = readerView.x; saved["pan-y-$key"] = readerView.y
    } }
    fun retry() {
        pendingRequest?.let { request -> programKey = null; pendingRequest = null
            bind(request.member, request.context, request.item, request.concert); return }
        inspected = false; render()
    }
    fun detach() {
        stopPreparation()
        job?.cancel(); generation++; identity = null; score = null; sequence = null
        width = 0; height = 0; inspected = false; mutable.value = PdfState.Loading
        currentBinding.value = null
        currentAnchor.value = null; cursor = null; programKey = null; pendingRequest = null
        mutablePlayMode.value = false; noticeJob?.cancel(); mutableNotice.value = null
        mutableFullscreen.value = false
    }
    private fun render(debounce: Boolean = false) {
        if (pendingRequest != null) return
        job?.cancel()
        val token = ++generation
        val pages = sequence ?: return
        if (width <= 0 || height <= 0) return
        val selected = pages.page(index)
        val w = width; val h = height; val fit = mode; val position = index; val view = readerView
        if (!debounce) mutable.value = PdfState.Loading
        job = viewModelScope.launch {
            var unpublished: PdfFrame? = null
            try {
                if (debounce) delay(100)
                lock.withLock {
                    ensureActive()
                    if (!inspected) {
                        engine.inspect(pages.segments); ensureActive()
                        if (token != generation) return@withLock
                        inspected = true
                    }
                    unpublished = engine.renderView(selected, w, h, fit, view)
                    ensureActive()
                    if (token != generation) return@withLock
                    mutable.value = PdfState.Ready(unpublished!!, position, pages.count, selected.pageIndex + 1, fit, selected)
                    unpublished = null
                }
            } catch (error: CancellationException) { throw error }
            catch (error: PdfFailure) { if (token == generation) renderFailure(error.reason) }
            catch (_: Exception) { if (token == generation) renderFailure(ReaderError.LOAD_FAILED) }
            finally { unpublished?.bitmap?.recycle() }
        }
    }
    private fun renderFailure(error: ReaderError) {
        mutable.value = PdfState.Failure(error)
        if (mutablePlayMode.value) {
            mutablePlayMode.value = false
            mutablePerformance.value = PerformanceState.Failure(readerErrorText(error))
            programKey?.let { saved["play-$it"] = false }
        }
    }
}
