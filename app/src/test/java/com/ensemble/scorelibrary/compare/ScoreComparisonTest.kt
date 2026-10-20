package com.ensemble.scorelibrary.compare

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.PreviewImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScoreComparisonTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val owner = Any()
    private val a = MutableStateFlow<DomainResult<Presentation>>(ready("a"))
    private val b = MutableStateFlow<DomainResult<Presentation>>(ready("b"))
    private var gate: CompletableDeferred<Unit>? = null
    private var denyFresh = false
    private var missing: String? = null
    private val rendered = mutableListOf<PdfPageRef>()
    private val bitmaps = mutableListOf<Bitmap>()
    private var running = 0
    private var peak = 0
    private val repository = object : PresentationRepository {
        override fun observe(memberId: String?, request: ScreenRequest): Flow<DomainResult<Presentation>> {
            val piece = when (request) { is ScreenRequest.Piece -> request.id; is ScreenRequest.Viewer -> request.pieceId; else -> error("Unexpected request") }
            if (denyFresh && request is ScreenRequest.Viewer) return flowOf(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, piece)))
            return (if (piece == "a") a else b).map { result ->
                if (request !is ScreenRequest.Piece) result
                else result.map { p -> p.copy(content = p.content.map { content ->
                    val score = (content as ScreenContent.Viewer).score
                    ScreenContent.Piece(piece, score.title, listOf(score.part))
                }) }
            }
        }
    }
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            if (segments.any { it.scorePdfId == missing }) throw PdfFailure(ReaderError.FILE_MISSING)
            return segments.map { PageSize(400, 600) }
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            running++; peak = maxOf(peak, running)
            try {
                val wait = gate
                if (wait != null) withContext(NonCancellable) { wait.await() }
                rendered += page
                return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmaps += it },
                    PdfPageTransform.fit(PageSize(400, 600), width, height, mode))
            } finally { running-- }
        }
    }
    private fun ready(piece: String, version: String = "v1", name: String = "曲目", member: String = "m") =
        DomainResult.Success(Presentation(Dashboard(emptyList(), Profile(member, "成员", null, false),
            listOf(PieceSummary(piece, name, null, "分类", if (piece == "a") "001" else "002", listOf("小提琴")))),
            DomainResult.Success(ScreenContent.Viewer(ResolvedConcertItem("", piece, name, VisiblePart("$piece-part", "violin", "小提琴"),
                listOf(ResolvedScoreSegment("$piece-binding", null, piece, version, "fixture", "hash-$version", 2, 4, CacheStatus.NOT_CACHED)))))))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { models.clear(); Dispatchers.resetMain() }
    private fun model(): ScoreComparisonViewModel = ScoreComparisonViewModel(repository, engine).also {
        models.put("compare", it); it.open("m", owner); it.viewport(0, 100, 150); it.viewport(1, 100, 150)
    }
    private fun select(vm: ScoreComparisonViewModel, side: Int, piece: String) {
        vm.choosePiece(side, piece); testScheduler.runCurrent()
        assertEquals(listOf("$piece-part"), vm.state.value.panes[side].parts.map { it.id })
        vm.choosePart(side, "$piece-part"); testScheduler.runCurrent()
    }
    private val testScheduler get() = dispatcher.scheduler
    @Test fun twoExplicitPartsUseOnlyTheirAuthorizedRangesAndBoundedFrames() = runTest(dispatcher) {
        val vm = model(); vm.viewport(0, 4000, 6000); select(vm, 0, "a"); select(vm, 1, "b")
        val sides = vm.state.value.panes
        assertEquals(listOf(2, 2), sides.map { it.sourcePage })
        assertEquals(listOf(3, 3), sides.map { it.total })
        assertEquals("001", sides[0].summary?.organizerSequence)
        assertEquals("002", sides[1].summary?.organizerSequence)
        assertTrue(sides[0].image!!.frame.bitmap.width <= 1200)
        assertTrue(sides[0].image!!.frame.bitmap.height <= 1800)
        assertEquals(listOf("a", "b"), rendered.map { it.segment.scorePdfId })
    }
    @Test fun independentPageTurnsAndBoundsNeverMoveOtherPane() = runTest(dispatcher) {
        val vm = model(); select(vm, 0, "a"); select(vm, 1, "b")
        val other = vm.state.value.panes[1].image
        repeat(5) { vm.move(0, 1); runCurrent() }
        assertEquals(2, vm.state.value.panes[0].index); assertEquals(4, vm.state.value.panes[0].sourcePage)
        assertSame(other, vm.state.value.panes[1].image)
        repeat(5) { vm.move(0, -1); runCurrent() }
        assertEquals(0, vm.state.value.panes[0].index)
    }
    @Test fun newSourceResetsOnlyItsPositionAndNeverDisplaysOldFrame() = runTest(dispatcher) {
        val vm = model(); select(vm, 0, "a"); vm.move(0, 1); runCurrent()
        val old = vm.state.value.panes[0].image!!
        a.value = ready("a", "v2"); runCurrent()
        assertEquals(0, vm.state.value.panes[0].index)
        assertEquals("v2", vm.state.value.panes[0].score!!.segments.single().versionId)
        assertTrue(old.frame.bitmap.isRecycled); assertNull(old.acquire())
    }
    @Test fun unrelatedProjectionEmissionDoesNotRerenderOrResetPage() = runTest(dispatcher) {
        val vm = model(); select(vm, 0, "a"); vm.move(0, 1); runCurrent()
        val old = vm.state.value.panes[0].image
        val before = (a.value as DomainResult.Success).value
        a.value = DomainResult.Success(before.copy(dashboard = before.dashboard.copy(members = listOf(MemberChoice("q", "other")))))
        runCurrent(); assertSame(old, vm.state.value.panes[0].image); assertEquals(1, vm.state.value.panes[0].index)
    }
    @Test fun revocationRetiresVisibleInkBeforeNonCancellableNativeWorkFinishes() = runTest(dispatcher) {
        val vm = model(); select(vm, 0, "a"); select(vm, 1, "b")
        val wait = CompletableDeferred<Unit>(); gate = wait
        vm.move(0, 1); runCurrent()
        a.value = DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, "a")); runCurrent()
        assertNull(vm.state.value.panes[0].image); assertNotNull(vm.state.value.panes[0].error)
        assertNotNull(vm.state.value.panes[1].image)
        wait.complete(Unit); runCurrent()
        assertNull(vm.state.value.panes[0].image); assertTrue(bitmaps.last().isRecycled); assertEquals(1, peak)
    }
    @Test fun freshReadCanRejectRevocationNotYetEmittedToExistingObserver() = runTest(dispatcher) {
        val vm = model(); val wait = CompletableDeferred<Unit>(); gate = wait
        select(vm, 0, "a"); denyFresh = true; wait.complete(Unit); runCurrent()
        assertNotNull(vm.state.value.panes[0].error); assertNull(vm.state.value.panes[0].image)
        assertTrue(bitmaps.single().isRecycled)
    }
    @Test fun oneMissingFileDoesNotClearValidOtherSideOrSubstituteOldVersion() = runTest(dispatcher) {
        val vm = model(); select(vm, 1, "b"); missing = "a"; select(vm, 0, "a")
        assertEquals("本地曲谱文件缺失", vm.state.value.panes[0].error)
        assertNotNull(vm.state.value.panes[1].image); assertEquals(listOf("b"), rendered.map { it.segment.scorePdfId })
    }
    @Test fun newMemberClearsBothAndDiscardsLateOldFrame() = runTest(dispatcher) {
        val vm = model(); val wait = CompletableDeferred<Unit>(); gate = wait; select(vm, 0, "a")
        vm.open("other", Any()); assertEquals(listOf(null, null), vm.state.value.panes.map { it.image })
        wait.complete(Unit); runCurrent()
        assertEquals("other", vm.state.value.member); assertTrue(bitmaps.single().isRecycled)
    }
    @Test fun oldDialogDisposalCannotClearNewDialogAndClearReleasesViewsSafely() = runTest(dispatcher) {
        val vm = model(); select(vm, 0, "a")
        val image = vm.state.value.panes[0].image!!
        val view = PreviewImageView(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        view.show(image)
        val next = Any(); vm.open("m", next); vm.clear(owner)
        assertTrue(vm.current("m", next)); assertFalse(image.canDisplay()); assertFalse(image.frame.bitmap.isRecycled)
        view.clearPreview(); assertTrue(image.frame.bitmap.isRecycled)
        vm.clear(next); assertNull(vm.state.value.member)
    }
    @Test fun rapidResizeQueuesAtMostOneNativeRenderAndDiscardsStaleFrames() = runTest(dispatcher) {
        val vm = model(); val wait = CompletableDeferred<Unit>(); gate = wait; select(vm, 0, "a")
        repeat(20) { vm.viewport(0, 200 + it, 300 + it); runCurrent() }
        vm.choosePiece(1, "b"); runCurrent(); vm.choosePart(1, "b-part"); runCurrent()
        wait.complete(Unit); runCurrent()
        assertEquals(1, peak); assertEquals(3, rendered.size)
        assertTrue(bitmaps.first().isRecycled)
        assertNotNull(vm.state.value.panes[0].image); assertNotNull(vm.state.value.panes[1].image)
    }
    @Test fun forgedPartAndUnexpectedReturnedPartNeverReachEngine() = runTest(dispatcher) {
        val vm = model(); vm.choosePiece(0, "a"); runCurrent(); vm.choosePart(0, "forged"); runCurrent()
        assertTrue(rendered.isEmpty())
        val before = (a.value as DomainResult.Success).value
        val score = ((before.content as DomainResult.Success).value as ScreenContent.Viewer).score
        a.value = DomainResult.Success(before.copy(content = DomainResult.Success(ScreenContent.Viewer(score.copy(part = score.part.copy(id = "wrong"))))))
        runCurrent(); vm.choosePart(0, "wrong"); runCurrent()
        // The selected exact part is valid here; a subsequent mismatched Domain result is rejected.
        a.value = ready("a"); runCurrent()
        assertNull(vm.state.value.panes[0].image); assertNotNull(vm.state.value.panes[0].error)
    }
    @Test fun hiddenPaneDropsImageWithoutLosingItsOwnPageAndViewModelDisposalReleasesOthers() = runTest(dispatcher) {
        val vm = model(); select(vm, 0, "a"); select(vm, 1, "b"); vm.move(0, 1); runCurrent()
        val old = vm.state.value.panes[0].image!!
        vm.viewport(0, 0, 0); assertNull(vm.state.value.panes[0].image); assertEquals(1, vm.state.value.panes[0].index)
        assertTrue(old.frame.bitmap.isRecycled)
        vm.viewport(0, 100, 150); runCurrent(); assertEquals(3, vm.state.value.panes[0].sourcePage)
        models.clear(); assertTrue(bitmaps.all { it.isRecycled })
    }
    @Test fun extremeViewportsNeverOverflowBudgetOrDimensions() {
        for (width in listOf(1, 800, Int.MAX_VALUE)) for (height in listOf(1, 1600, Int.MAX_VALUE)) for (budget in listOf(1L, 200L, 1_000_000L)) {
            val (w, h) = comparisonSize(width, height, budget)
            assertTrue(w in 1..1200 && h in 1..1800); assertTrue(w.toLong() * h <= budget)
        }
        assertEquals(0 to 0, comparisonSize(0, 500)); assertEquals(0 to 0, comparisonSize(1, -1))
    }
}
