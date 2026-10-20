package com.ensemble.scorelibrary.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScorePreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val results = MutableSharedFlow<DomainResult<Presentation>>(replay = 1)
    private var gate: CompletableDeferred<Unit>? = null
    private var failure = false
    private var renderedPage = -1
    private val bitmaps = mutableListOf<Bitmap>()
    private val repository = object : PresentationRepository {
        override fun observe(memberId: String?, request: ScreenRequest) = results
    }
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            if (failure) throw PdfFailure(ReaderError.FILE_MISSING)
            return listOf(PageSize(600, 900))
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            val wait = gate
            if (wait != null) withContext(NonCancellable) { wait.await() }
            renderedPage = page.pageIndex
            return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmaps += it },
                PdfPageTransform.fit(PageSize(600, 900), width, height, mode, scroll))
        }
    }
    private fun ready(member: String = "m", piece: String = "p") = DomainResult.Success(Presentation(
        Dashboard(emptyList(), Profile(member, "Synthetic", "I", false)), DomainResult.Success(ScreenContent.Viewer(
            ResolvedConcertItem("", piece, "Synthetic", VisiblePart("part", "d", "I"), listOf(
                ResolvedScoreSegment("b", null, "pdf", "version", "fixture", "hash", 2, 3, CacheStatus.NOT_CACHED)))))))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }
    private fun model() = ScorePreviewViewModel(repository, engine).also { store.put("preview", it) }
    @Test fun noDefaultPartOffersSelectionWithoutRenderingAnArbitraryAuthorizedPart()=runTest(dispatcher) {
        results.emit(DomainResult.Success(Presentation(Dashboard(emptyList(),Profile("m","Synthetic",null,false)),
            DomainResult.Failure(DomainError(ErrorCode.DEFAULT_PART_MISSING,"m")))))
        val vm=model();vm.bind("m","p",100,150);runCurrent()
        val state=vm.state.value as ScorePreviewState.Failure
        assertTrue(state.choosePart);assertEquals("请先选择要阅读的声部",state.message)
        assertEquals(-1,renderedPage);assertTrue(bitmaps.isEmpty())
        results.emit(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"p")));runCurrent()
        assertFalse((vm.state.value as ScorePreviewState.Failure).choosePart)
    }
    @Test fun stalePaneDisposalCannotClearPreviewOwnedByNewPane() = runTest(dispatcher) {
        results.emit(ready());val vm=model();val old=Any();val current=Any()
        vm.bind("m","p",100,150,old);runCurrent()
        vm.bind("m","p",100,150,current);runCurrent()
        val ready=vm.state.value as ScorePreviewState.Ready
        vm.clear(old);assertSame(ready,vm.state.value)
        vm.clear(current);assertEquals(ScorePreviewState.Empty,vm.state.value)
    }
    @Test fun previewUsesFirstAuthorizedSourcePageAndBoundsBitmap() = runTest(dispatcher) {
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 2400, 3200); runCurrent()
        val state = vm.state.value as ScorePreviewState.Ready
        assertEquals(1, renderedPage)
        assertEquals(1200, state.frame.bitmap.width); assertEquals(1800, state.frame.bitmap.height)
        vm.bind("m", "p", 2400, 3200); assertSame(state, vm.state.value)
    }
    @Test fun repeatedUnchangedSyncResultsKeepTheExistingBitmapWithoutLoadingAgain() = runTest(dispatcher) {
        results.emit(ready()); val vm=model(); vm.bind("m","p",100,150); runCurrent()
        val displayed=vm.state.value as ScorePreviewState.Ready
        repeat(5) { results.emit(ready());runCurrent();assertSame(displayed,vm.state.value) }
        assertEquals(1,bitmaps.size)
        assertFalse(displayed.frame.bitmap.isRecycled)
        results.emit(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"p")));runCurrent()
        assertTrue(vm.state.value is ScorePreviewState.Failure)
        assertTrue(displayed.frame.bitmap.isRecycled)
    }
    @Test fun unrelatedDashboardChangesDoNotClearOrRerenderTheSelectedPreview()=runTest(dispatcher) {
        val original=ready();results.emit(original)
        val vm=model();vm.bind("m","p",100,150);runCurrent()
        val displayed=vm.state.value as ScorePreviewState.Ready
        repeat(8) { i ->
            results.emit(DomainResult.Success(original.value.copy(dashboard=original.value.dashboard.copy(
                pieces=listOf(PieceSummary("other-$i","Other work $i",null)),
                concerts=listOf(ConcertSummary("concert-$i","Rehearsal $i")),
                profile=original.value.dashboard.profile!!.copy(name="Updated name $i",canManage=i%2==0)))))
            runCurrent();assertSame(displayed,vm.state.value)
        }
        assertEquals(1,bitmaps.size);assertFalse(displayed.frame.bitmap.isRecycled)
    }
    @Test fun profileMismatchOrContentRevocationCannotReuseTheOldPreview()=runTest(dispatcher) {
        val original=ready();results.emit(original)
        val vm=model();vm.bind("m","p",100,150);runCurrent()
        val displayed=vm.state.value as ScorePreviewState.Ready
        results.emit(DomainResult.Success(original.value.copy(dashboard=original.value.dashboard.copy(
            profile=original.value.dashboard.profile!!.copy(id="other-account")))))
        runCurrent();assertTrue(vm.state.value is ScorePreviewState.Failure)
        assertTrue(displayed.frame.bitmap.isRecycled)
        results.emit(original);runCurrent();assertTrue(vm.state.value is ScorePreviewState.Ready)
        val newFrame=vm.state.value as ScorePreviewState.Ready
        results.emit(DomainResult.Success(original.value.copy(content=DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"p")))))
        runCurrent();assertTrue(vm.state.value is ScorePreviewState.Failure);assertTrue(newFrame.frame.bitmap.isRecycled)
    }
    @Test fun memberSwitchAndClearRejectLateNonCancellableRender() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>(); gate = wait
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 100, 150); runCurrent()
        vm.bind("other", "p", 100, 150)
        assertTrue(vm.state.value is ScorePreviewState.Loading)
        runCurrent(); wait.complete(Unit); runCurrent()
        assertTrue(vm.state.value is ScorePreviewState.Failure)
        assertTrue(bitmaps.single().isRecycled)
        vm.clear(); assertEquals(ScorePreviewState.Empty, vm.state.value)
    }
    @Test fun revocationRemovesVisibleBitmapAndMissingFileNeverShowsReady() = runTest(dispatcher) {
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 100, 150); runCurrent()
        assertTrue(vm.state.value is ScorePreviewState.Ready)
        results.emit(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, "p"))); runCurrent()
        assertEquals(errorText(ErrorCode.UNAUTHORIZED), (vm.state.value as ScorePreviewState.Failure).message)
        failure = true; results.emit(ready()); runCurrent()
        assertEquals("本地曲谱文件缺失", (vm.state.value as ScorePreviewState.Failure).message)
    }
    @Test fun permissionChangeHidesBitmapBeforeWaitingForOldNativeRender() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>(); gate = wait
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 100, 150); runCurrent()
        results.emit(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, "p"))); runCurrent()
        assertTrue(vm.state.value is ScorePreviewState.Loading)
        wait.complete(Unit); runCurrent()
        assertTrue(vm.state.value is ScorePreviewState.Failure)
    }

    @Test fun clearRetiresImageButDetachesViewBeforeRecycling() = runTest(dispatcher) {
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 100, 150); runCurrent()
        val state = vm.state.value as ScorePreviewState.Ready
        val view = PreviewImageView(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        view.show(state.image)
        vm.clear()
        assertEquals(ScorePreviewState.Empty, vm.state.value)
        assertNull(state.image.acquire())
        assertFalse(state.image.canDisplay())
        assertFalse(state.frame.bitmap.isRecycled)
        view.clearPreview()
        assertNull(view.drawable)
        assertTrue(state.frame.bitmap.isRecycled)
        view.clearPreview() // Repeated disposal is harmless.
    }
    @Test fun viewModelDisposalReleasesUnattachedFrame() = runTest(dispatcher) {
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 100, 150); runCurrent()
        val image = (vm.state.value as ScorePreviewState.Ready).image
        store.clear()
        assertTrue(image.frame.bitmap.isRecycled)
        assertNull(image.acquire())
    }
    @Test fun retryReplacesFailedFrameAndReleasesPreviousOwner() = runTest(dispatcher) {
        results.emit(ready()); val vm = model(); vm.bind("m", "p", 100, 150); runCurrent()
        val old = (vm.state.value as ScorePreviewState.Ready).frame.bitmap
        failure = true; vm.retry(); runCurrent()
        assertTrue(old.isRecycled)
        assertTrue(vm.state.value is ScorePreviewState.Failure)
        failure = false; vm.retry(); runCurrent()
        assertTrue(vm.state.value is ScorePreviewState.Ready)
        assertNotSame(old, (vm.state.value as ScorePreviewState.Ready).frame.bitmap)
    }

}
