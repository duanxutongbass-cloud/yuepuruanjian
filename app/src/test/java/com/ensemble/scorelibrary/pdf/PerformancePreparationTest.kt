package com.ensemble.scorelibrary.pdf

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.core.model.PedalAction
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerformancePreparationTest {
    private val dispatcher = StandardTestDispatcher()
    private val views = ViewModelStore()
    private val ink = TestPerformanceAnnotations()
    private var missing: String? = null
    private val inspected = mutableListOf<String>()
    private val items = (0..4).map { n -> ResolvedConcertItem("i$n", "p$n", "Piece$n", VisiblePart("part$n", "d", "I"),
        listOf(ResolvedScoreSegment("b$n", null, "pdf$n", "v$n", "file$n", "hash$n", 2, 3, CacheStatus.NOT_CACHED))) }
    private val concert = ResolvedConcert("c", "Concert", items)
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            inspected += segments.map { it.versionId }
            if (segments.any { it.versionId == missing }) throw PdfFailure(ReaderError.FILE_MISSING)
            return listOf(PageSize(600,900))
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            if (page.segment.versionId == missing) throw PdfFailure(ReaderError.FILE_MISSING)
            return PdfFrame(Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(600,900),width,height,mode,scroll))
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { views.clear(); Dispatchers.resetMain() }
    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = PdfReaderViewModel(engine,saved,PerformancePreparation(engine,ink))
        .also { views.put("reader",it); it.bind("m","i0",items[0],concert); it.viewport(800,1200) }
    @Test fun checksEveryAuthorizedFileAndSourcePageBeforeEntering() = runTest(dispatcher) {
        val model=vm(); runCurrent(); model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertTrue(model.playMode.value); assertEquals(PerformanceState.Ready(10),model.performance.value)
        assertEquals((0..4).flatMap { n -> listOf("p$n" to 1,"p$n" to 2) }, ink.loaded.map { it.piece to it.page })
        assertTrue(inspected.containsAll((0..4).map { "v$it" }))
    }
    @Test fun missingFifthPieceIsFoundBeforeAnyPageTurn() = runTest(dispatcher) {
        val model=vm(); runCurrent(); missing="v4"
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertFalse(model.playMode.value)
        assertTrue((model.performance.value as PerformanceState.Failure).message.contains("Piece4"))
        assertEquals(items[0],model.binding.value!!.second)
        assertEquals(2,(model.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun unlockedConcertCannotEnterButOrdinaryReadingRemainsAvailable() = runTest(dispatcher) {
        ink.locked=false; val model=vm(); runCurrent()
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertFalse(model.playMode.value); assertTrue((model.performance.value as PerformanceState.Failure).message.contains("未锁定"))
        model.nextPage(); runCurrent(); assertEquals(3,(model.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun corruptLaterAnnotationPageFailsWholePreparation() = runTest(dispatcher) {
        ink.failurePage=2; val model=vm(); runCurrent(); model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertFalse(model.playMode.value); assertEquals(PerformanceState.Failure("批注损坏"), model.performance.value)
    }
    @Test fun cancelPreparationDoesNotEnterLaterOrQueueKeys() = runTest(dispatcher) {
        val model=vm(); runCurrent(); val barrier=CompletableDeferred<Unit>(); ink.barrier=barrier
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertTrue(model.performance.value is PerformanceState.Checking)
        repeat(5){model.nextPage()}; model.cancelPreparation(); barrier.complete(Unit); runCurrent()
        assertFalse(model.playMode.value); assertEquals(PerformanceState.Idle,model.performance.value)
        assertEquals(2,(model.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun restoringPositionDoesNotAutomaticallyReenterPerformance() = runTest(dispatcher) {
        val saved=SavedStateHandle(); var model=vm(saved); runCurrent()
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent(); assertTrue(model.playMode.value)
        model.detach(); missing="v4"; model=vm(saved); runCurrent()
        assertFalse(model.playMode.value); assertEquals(PerformanceState.Idle, model.performance.value)
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertFalse(model.playMode.value); assertTrue(model.performance.value is PerformanceState.Failure)
    }
    @Test fun changingMemberCancelsPendingCheckAndDiscardsOldResult() = runTest(dispatcher) {
        val model=vm(); runCurrent(); val barrier=CompletableDeferred<Unit>(); ink.barrier=barrier
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        model.bind("other","i0",items[0],concert); barrier.complete(Unit); runCurrent()
        assertFalse(model.playMode.value); assertEquals("other",model.binding.value!!.first)
        assertEquals(PerformanceState.Idle,model.performance.value)
    }
    @Test fun runtimeFileLossStopsPerformanceEvenAfterSuccessfulPreparation() = runTest(dispatcher) {
        val model=vm(); runCurrent(); model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertTrue(model.playMode.value)
        missing="v0"; model.nextPage(); runCurrent()
        assertEquals(PdfState.Failure(ReaderError.FILE_MISSING),model.state.value)
        assertFalse(model.playMode.value); assertTrue(model.performance.value is PerformanceState.Failure)
    }
    @Test fun pageBudgetRejectsOversizedPreparationBeforeScanningAnnotations() = runTest(dispatcher) {
        val large=items[0].copy(segments=items[0].segments.map{it.copy(endPage=20_001)})
        try { PerformancePreparation(engine,ink).check("m",listOf(large),true){_,_->}; fail("Must reject oversized programme") }
        catch (_: IllegalArgumentException) { }
        assertTrue(ink.loaded.isEmpty())
    }
    @Test fun stoppingOrLeavingEndsPreparedScene() = runTest(dispatcher) {
        val model = vm(); runCurrent(); model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertNotNull(model.frozenViewer("m", ScreenRequest.Viewer("p0", "part0", "c", "i0")))
        model.interruptPerformance(); runCurrent()
        assertNull(model.frozenViewer("m", ScreenRequest.Viewer("p0", "part0", "c", "i0")))
        assertFalse(model.playMode.value)
        model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        assertTrue(model.playMode.value)
        model.detach(); runCurrent()
        assertNull(model.frozenViewer("m", ScreenRequest.Viewer("p0", "part0", "c", "i0")))
    }
    @Test fun enteredSceneUsesCapturedInkWithoutNewRepositoryReads() = runTest(dispatcher) {
        val model = vm(); runCurrent(); model.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        val loads = ink.loaded.size
        ink.locked = false; ink.failurePage = 2
        val page = com.ensemble.scorelibrary.annotation.AnnotationScope("m", "p0", "part0", "v0", 2, "i0")
        assertTrue(model.performanceInk(page)!!.locked)
        assertNull(model.performanceInk(page.copy(member = "other")))
        model.nextPage(); runCurrent()
        assertTrue(model.playMode.value)
        assertEquals(loads, ink.loaded.size)
    }
}
