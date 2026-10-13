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
class ConcertReaderTest {
    private val dispatcher = StandardTestDispatcher()
    private val views = ViewModelStore()
    private val saved = SavedStateHandle()
    private var missing: String? = null
    private val rendered = mutableListOf<PdfPageRef>()
    private val items = (0..4).map { n -> ResolvedConcertItem("i$n", "p$n", "Piece $n", VisiblePart("part$n", "d${n%2}", "Part ${n%2}"),
        listOf(ResolvedScoreSegment("b$n", null, "pdf$n", "v$n", "file$n", "hash$n", 2+n%2, 3+n%2, CacheStatus.NOT_CACHED))) }
    private val concert = ResolvedConcert("concert", "Five pieces", items)
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            if (segments.any { it.versionId == missing }) throw PdfFailure(ReaderError.FILE_MISSING)
            return listOf(PageSize(600, 900))
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            rendered += page
            return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(600,900),width,height,mode,scroll))
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { views.clear(); Dispatchers.resetMain() }
    private fun model() = PdfReaderViewModel(engine, saved, PerformancePreparation(engine, TestPerformanceAnnotations())).also { views.put("reader", it) }
    private fun open(vm: PdfReaderViewModel) { vm.bind("member", "i0", items[0], concert); vm.viewport(800,1200) }
    @Test fun cursorPreservesDomainOrderAndTraversesBothBoundaries() {
        val cursor = ConcertCursor(concert)
        var position = ConcertPosition(0,0)
        assertNull(cursor.move(position, PedalAction.PREVIOUS_PAGE))
        repeat(9) { position = cursor.move(position, PedalAction.NEXT_PAGE)!! }
        assertEquals(ConcertPosition(4,1), position)
        assertNull(cursor.move(position, PedalAction.NEXT_PAGE))
        repeat(9) { position = cursor.move(position, PedalAction.PREVIOUS_PAGE)!! }
        assertEquals(ConcertPosition(0,0), position)
        assertThrows(IllegalArgumentException::class.java) { ConcertCursor(concert.copy(items = listOf(items[0],items[0]))) }
        assertThrows(IllegalArgumentException::class.java) { ConcertCursor(concert.copy(items = emptyList())) }
    }
    @Test fun fivePiecesUseAssignedPartsRangesAndNoWrapOrPopup() = runTest(dispatcher) {
        val vm = model(); open(vm); runCurrent()
        repeat(9) { vm.nextPage(); runCurrent() }
        assertEquals(items[4], vm.binding.value!!.second)
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        assertEquals(listOf("v0","v0","v1","v1","v2","v2","v3","v3","v4","v4"), rendered.map { it.segment.versionId })
        assertEquals(listOf(1,2,2,3,1,2,2,3,1,2), rendered.map { it.pageIndex })
        vm.nextPage(); runCurrent(); assertEquals(10, rendered.size); assertEquals("已到最后一页", vm.notice.value)
        repeat(9) { vm.previousPage(); runCurrent() }
        assertEquals(items[0], vm.binding.value!!.second)
        vm.previousPage(); runCurrent(); assertEquals("已到第一页", vm.notice.value)
    }
    @Test fun cursorModeAndPageRestoreButCrossingIgnoresOldBookmark() = runTest(dispatcher) {
        var vm = model(); open(vm); runCurrent()
        repeat(3) { vm.nextPage(); runCurrent() }
        vm.fit(FitMode.WIDTH); runCurrent(); vm.action(PedalAction.TOGGLE_PLAY_MODE); runCurrent()
        vm.detach(); vm = model(); open(vm); runCurrent()
        assertEquals(items[1], vm.binding.value!!.second)
        assertEquals(4, (vm.state.value as PdfState.Ready).sourcePage)
        assertEquals(FitMode.WIDTH, (vm.state.value as PdfState.Ready).mode)
        assertTrue(vm.playMode.value)
        vm.previousPage(); runCurrent(); vm.previousPage(); runCurrent()
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        vm.nextPage(); runCurrent()
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        assertEquals(items[1], vm.binding.value!!.second)
    }
    @Test fun changedProgrammeStaysClosedAcrossResizeFitAndKeysUntilRetry() = runTest(dispatcher) {
        val vm = model(); open(vm); runCurrent()
        val changedItem = items[1].copy(segments = items[1].segments.map { it.copy(versionId = "new") })
        val changed = concert.copy(items = listOf(items[0],changedItem)+items.drop(2))
        vm.bind("member", "i0", items[0], changed)
        assertEquals(PdfState.Failure(ReaderError.PROGRAM_CHANGED), vm.state.value)
        vm.viewport(1200,1800); vm.fit(FitMode.WIDTH); vm.nextPage(); runCurrent()
        assertEquals(PdfState.Failure(ReaderError.PROGRAM_CHANGED), vm.state.value)
        assertNull(vm.binding.value)
        vm.retry(); runCurrent(); repeat(2) { vm.nextPage(); runCurrent() }
        assertEquals("new", (vm.state.value as PdfState.Ready).reference.segment.versionId)
    }
    @Test fun missingNextPieceNeverSkipsAndPreviousRecovers() = runTest(dispatcher) {
        val vm = model(); open(vm); runCurrent(); missing = "v1"
        repeat(2) { vm.nextPage(); runCurrent() }
        assertEquals(PdfState.Failure(ReaderError.FILE_MISSING), vm.state.value)
        vm.nextPage(); runCurrent(); assertEquals(items[1], vm.binding.value!!.second)
        vm.previousPage(); runCurrent(); assertEquals(items[0], vm.binding.value!!.second)
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun loadingDoesNotAccumulateExtraPressesAndNewMemberCannotRestoreOldCursor() = runTest(dispatcher) {
        val vm = model(); open(vm)
        repeat(10) { vm.nextPage() }; runCurrent()
        assertEquals(2, (vm.state.value as PdfState.Ready).sourcePage)
        repeat(4) { vm.nextPage(); runCurrent() }
        vm.bind("other", "i0", items[0], concert); runCurrent()
        assertEquals(items[0], vm.binding.value!!.second)
        assertEquals(2, (vm.state.value as PdfState.Ready).sourcePage)
        assertFalse(vm.playMode.value)
    }
    @Test fun relocatedFutureFileAlsoInvalidatesProgrammeInsteadOfReadingOldPath() = runTest(dispatcher) {
        val vm = model(); open(vm); runCurrent()
        val updated = items[4].copy(segments = items[4].segments.map { it.copy(managedFilePath = "replacement") })
        vm.bind("member", "i0", items[0], concert.copy(items = items.take(4) + updated))
        assertEquals(PdfState.Failure(ReaderError.PROGRAM_CHANGED), vm.state.value)
    }
}
