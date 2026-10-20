package com.ensemble.scorelibrary.pdf

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PdfReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val saved = SavedStateHandle()
    private val rendered = mutableListOf<PdfPageRef>()
    private var failWith: ReaderError? = null
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            failWith?.let { throw PdfFailure(it) }; return listOf(PageSize(600, 900))
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            rendered += page
            return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(600, 900), width, height, mode, scroll))
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun score(version: String = "v") = ResolvedConcertItem("item", "p", "Piece", VisiblePart("part", "d", "I"),
        listOf(ResolvedScoreSegment("b", "movement", "pdf", version, "file", "hash", 3, 5, CacheStatus.NOT_CACHED)))
    private fun model() = PdfReaderViewModel(engine, saved, PerformancePreparation(engine, TestPerformanceAnnotations())).also { store.put("model", it) }

    @Test fun stalledNativePageEndsSpinnerAndCannotPublishLateFrame() = runTest(dispatcher) {
        val release=CompletableDeferred<Unit>();var late:Bitmap?=null;var stalled=true
        val delayed=object:PdfEngine {
            override suspend fun inspect(segments:List<ResolvedScoreSegment>)=listOf(PageSize(600,900))
            override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame {
                if(stalled)withContext(NonCancellable){release.await()}
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                if(stalled)late=bitmap
                return PdfFrame(bitmap,PdfPageTransform.fit(PageSize(600,900),width,height,mode))
            }
        }
        val vm=PdfReaderViewModel(delayed,saved,PerformancePreparation(delayed,TestPerformanceAnnotations())).also {store.put("model",it)}
        vm.bind("member","library",score());vm.viewport(800,1000);runCurrent()
        assertEquals(PdfState.Loading,vm.state.value)
        advanceTimeBy(30_001);runCurrent()
        assertEquals(ReaderError.RENDER_TIMEOUT,(vm.state.value as PdfState.Failure).error)
        release.complete(Unit);runCurrent();assertTrue(late!!.isRecycled)
        assertTrue(vm.state.value is PdfState.Failure)
        stalled=false;vm.retry();runCurrent();assertTrue(vm.state.value is PdfState.Ready)
    }

    @Test fun delayedPageTurnKeepsPreviousFrameVisibleAndBlocksRepeatedNavigation() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val delayed = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(PageSize(600, 900))
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
                if (page.pageIndex == 3) gate.await()
                return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(600, 900), width, height, mode))
            }
        }
        val vm = PdfReaderViewModel(delayed, saved, PerformancePreparation(delayed, TestPerformanceAnnotations()))
        store.put("delayed", vm)
        vm.bind("member", "library", score()); vm.viewport(800, 1000); runCurrent()
        val previous = vm.state.value as PdfState.Ready
        vm.nextPage(); runCurrent()
        val waiting = vm.state.value as PdfState.Ready
        assertTrue(waiting.rendering)
        assertSame(previous.frame, waiting.frame)
        assertEquals(3, waiting.sourcePage)
        vm.nextPage(); runCurrent()
        gate.complete(Unit); runCurrent()
        val ready = vm.state.value as PdfState.Ready
        assertFalse(ready.rendering)
        assertEquals(4, ready.sourcePage)
    }

    @Test fun failedReplacementClearsTheRetainedPageInsteadOfLeavingStaleContent() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val delayed = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(PageSize(600, 900))
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
                if (page.pageIndex == 3) { gate.await(); throw PdfFailure(ReaderError.CORRUPTED) }
                return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(600, 900), width, height, mode))
            }
        }
        val vm = PdfReaderViewModel(delayed, saved, PerformancePreparation(delayed, TestPerformanceAnnotations()))
        store.put("failure", vm)
        vm.bind("member", "library", score()); vm.viewport(800, 1000); runCurrent()
        vm.nextPage(); runCurrent(); assertTrue((vm.state.value as PdfState.Ready).rendering)
        gate.complete(Unit); runCurrent()
        assertEquals(PdfState.Failure(ReaderError.CORRUPTED), vm.state.value)
    }

    @Test fun readingStartsFullscreenAndTogglingToolsDoesNotRenderAnotherPdfFrame() = runTest(dispatcher) {
        val vm=model();vm.bind("member","library",score());vm.viewport(800,1000);runCurrent()
        assertTrue(vm.fullscreen.value)
        val count=rendered.size
        vm.setFullscreen(false);runCurrent();assertFalse(vm.fullscreen.value)
        vm.setFullscreen(true);runCurrent();assertTrue(vm.fullscreen.value)
        assertEquals(count,rendered.size)
        vm.setFullscreen(false);vm.detach();vm.bind("member","library",score());runCurrent()
        assertTrue(vm.fullscreen.value)
    }
    @Test fun startingAnnotationFitsTheWholePageAndResetsOldZoomOnlyOnce() = runTest(dispatcher) {
        val vm=model();vm.bind("member","library",score());vm.viewport(800,700);runCurrent()
        vm.fit(FitMode.WIDTH);runCurrent()
        vm.transform(2f,0f,0f);advanceUntilIdle()
        vm.fitForAnnotation();runCurrent()
        assertEquals(FitMode.PAGE,(vm.state.value as PdfState.Ready).mode)
        assertEquals(1f,vm.zoom(),.001f)
        val count=rendered.size
        vm.fitForAnnotation();runCurrent()
        assertEquals(count,rendered.size)
    }

    @Test fun zoomStateIsClampedRestoredAndSeparatedByMemberAndVersion() = runTest(dispatcher) {
        var vm = model(); vm.bind("member", "library", score()); vm.viewport(800, 1000); runCurrent()
        vm.transform(2f, 10f, -20f); advanceUntilIdle(); assertEquals(2f, vm.zoom(), .001f)
        vm.transform(Float.NaN, 0f, 0f); assertEquals(2f, vm.zoom(), .001f)
        vm = model(); vm.bind("member", "library", score()); vm.viewport(800, 1000); runCurrent()
        assertEquals(2f, vm.zoom(), .001f)
        vm.transform(100f, 0f, 0f); advanceUntilIdle(); assertEquals(4f, vm.zoom(), .001f)
        vm.resetView(); runCurrent(); assertEquals(1f, vm.zoom(), .001f)
        vm.transform(2f, 0f, 0f); advanceUntilIdle()
        vm.bind("other", "library", score()); runCurrent(); assertEquals(1f, vm.zoom(), .001f)
        vm.bind("member", "library", score("new")); runCurrent(); assertEquals(1f, vm.zoom(), .001f)
    }

    @Test fun boundPageNumbersAndActionsStayInsideAuthorizedRange() = runTest(dispatcher) {
        val vm = model(); vm.bind("member", "concert", score()); vm.viewport(800, 1000); runCurrent()
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        vm.previousPage(); runCurrent(); assertEquals(1, rendered.size)
        repeat(4) { vm.nextPage(); runCurrent() }
        assertEquals(listOf(2, 3, 4), rendered.map { it.pageIndex })
        assertEquals(5, (vm.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun sessionAndSavedHandleRestoreButNewVersionAndMemberStartSeparately() = runTest(dispatcher) {
        var vm = model(); vm.bind("member", "concert", score()); vm.viewport(800, 1000); runCurrent()
        vm.nextPage(); runCurrent(); vm.fit(FitMode.WIDTH); runCurrent()
        vm.detach(); vm.bind("member", "concert", score()); vm.viewport(800, 1000); runCurrent()
        assertEquals(4, (vm.state.value as PdfState.Ready).sourcePage)
        vm = model(); vm.bind("member", "concert", score()); vm.viewport(800, 1000); runCurrent()
        assertEquals(FitMode.WIDTH, (vm.state.value as PdfState.Ready).mode)
        assertEquals(4, (vm.state.value as PdfState.Ready).sourcePage)
        vm.bind("member", "concert", score("v2")); runCurrent()
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        assertEquals("v2", rendered.last().segment.versionId)
        vm.bind("other", "concert", score()); runCurrent()
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun resizeRerendersNativePixelsAndErrorRetryIsExplicit() = runTest(dispatcher) {
        val vm = model(); failWith = ReaderError.CORRUPTED
        vm.bind("member", "library", score()); vm.viewport(800, 1000); runCurrent()
        assertEquals(PdfState.Failure(ReaderError.CORRUPTED), vm.state.value)
        failWith = null; vm.retry(); runCurrent()
        vm.viewport(1200, 1600); runCurrent()
        val ready = vm.state.value as PdfState.Ready
        assertEquals(1200, ready.frame.bitmap.width); assertEquals(1600, ready.frame.bitmap.height)
        assertEquals(3, ready.sourcePage)
        vm.detach(); assertEquals(PdfState.Loading, vm.state.value); assertNull(vm.binding.value)
    }
    @Test fun delayedOldVersionCannotPublishAndItsBitmapIsRecycled() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var oldBitmap: Bitmap? = null
        val delayed = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(PageSize(600, 900))
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
                if (page.segment.versionId == "v") withContext(NonCancellable) { release.await() }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                if (page.segment.versionId == "v") oldBitmap = bitmap
                return PdfFrame(bitmap, PdfPageTransform.fit(PageSize(600, 900), width, height, mode))
            }
        }
        val vm = PdfReaderViewModel(delayed, saved, PerformancePreparation(delayed, TestPerformanceAnnotations())).also { store.put("model", it) }
        vm.bind("member", "library", score()); vm.viewport(800, 1000); runCurrent()
        vm.bind("member", "library", score("v2")); runCurrent()
        release.complete(Unit); runCurrent()
        assertTrue(oldBitmap!!.isRecycled)
        assertEquals("v2", (vm.state.value as PdfState.Ready).reference.segment.versionId)
    }
}
