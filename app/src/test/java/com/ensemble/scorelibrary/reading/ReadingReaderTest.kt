package com.ensemble.scorelibrary.reading

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadingReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 12 }, "AES")
    private val storage get() = ReadingActivityStore(temporary.root) { key }
    private val dispatcher = StandardTestDispatcher()
    private val worker = CoroutineScope(SupervisorJob() + dispatcher)
    private val models = ViewModelStore()
    private var currentMember = "m"
    private val activity by lazy { ReadingActivity(storage, { member ->
        ReadingIdentity(ReadingOwner("local", member)) { check(member == currentMember) }
    }, flowOf(Unit), worker) }
    private var failure = false
    private val rendered = mutableListOf<Int>()
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            if (failure) throw PdfFailure(ReaderError.LOAD_FAILED)
            return listOf(PageSize(60, 90))
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            rendered += page.pageIndex
            return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                PdfPageTransform.fit(PageSize(60, 90), width, height, mode, scroll))
        }
    }
    private fun score(version: String = "v") = ResolvedConcertItem("", "p", "Score", VisiblePart("part", "d", "Part"),
        listOf(ResolvedScoreSegment("b", null, "pdf", version, "file", "a".repeat(64), 3, 6, CacheStatus.NOT_CACHED)))
    private fun model(service: ReadingActivity = activity, saved: SavedStateHandle = SavedStateHandle()) =
        PdfReaderViewModel(engine, saved, PerformancePreparation(engine, TestPerformanceAnnotations()), activity = service)
            .also { models.put("reader", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); worker.cancel(); Dispatchers.resetMain() }
    private suspend fun savedPage(page: Int, item: ResolvedConcertItem = score()) {
        storage.record(ReadingOwner("local", "m"), RecentReading(item.pieceId, item.part.id, ReadingActivityStore.source(item), page))
    }
    private suspend fun settle(vm: PdfReaderViewModel) {
        // Real encrypted disk IO is not driven by advanceUntilIdle's virtual clock.
        withContext(Dispatchers.Default) { withTimeout(10000) { vm.state.first { it !is PdfState.Loading } } }
        activity.awaitIdle()
    }

    @Test fun coldReaderRestoresExactSourceWithoutRenderingFirstPageOrRecreatingScene() = runTest(dispatcher) {
        savedPage(2)
        val vm = model(); vm.bind("m", "library", score()); vm.viewport(80, 100)
        settle(vm)
        assertEquals(listOf(4), rendered)
        assertEquals(5, (vm.state.value as PdfState.Ready).sourcePage)
        assertNull(vm.running.value); assertFalse(vm.playMode.value)
    }
    @Test fun successfullyRenderedPageIsDurableAfterViewModelDestructionAndNewHandle() = runTest(dispatcher) {
        val service = activity; val vm = model(service)
        vm.bind("m", "library", score()); vm.viewport(80, 100); settle(vm)
        vm.nextPage(); settle(vm); service.awaitIdle(); models.clear()
        val reopened = model(); reopened.bind("m", "library", score()); reopened.viewport(80, 100); settle(reopened)
        assertEquals(4, (reopened.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun changedVersionStartsFromFirstAuthorizedPageAndReplacesOnlyThatPartsHistory() = runTest(dispatcher) {
        savedPage(3)
        val vm = model(); vm.bind("m", "library", score("new")); vm.viewport(80, 100); settle(vm)
        assertEquals(listOf(2), rendered)
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        assertEquals(ReadingActivityStore.source(score("new")), storage.read(ReadingOwner("local", "m")).recent.single().source)
    }
    @Test fun failedOrMerelyBoundPdfDoesNotCreateReadingHistory() = runTest(dispatcher) {
        val vm = model(); vm.bind("m", "library", score()); advanceUntilIdle()
        assertTrue(storage.read(ReadingOwner("local", "m")).recent.isEmpty())
        failure = true; vm.viewport(80, 100); settle(vm)
        assertTrue(vm.state.value is PdfState.Failure); assertTrue(storage.read(ReadingOwner("local", "m")).recent.isEmpty())
    }
    @Test fun cancelledResumeCannotFlashOldAccountFrameOrOverwriteItsStoredPosition() = runTest(dispatcher) {
        savedPage(3)
        val vm = model(); vm.bind("m", "library", score()); vm.viewport(80, 100)
        vm.detach(); currentMember = "other"; vm.bind("other", "library", score()); vm.viewport(80, 100)
        settle(vm)
        assertEquals(listOf(2), rendered)
        assertEquals(3, storage.read(ReadingOwner("local", "m")).recent.single().page)
        assertEquals(0, storage.read(ReadingOwner("local", "other")).recent.single().page)
    }
    @Test fun savedHandleWithinCurrentReadingSessionTakesPrecedenceOverOlderDiskPage() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val service = activity; val vm = model(service, handle)
        vm.bind("m", "library", score()); vm.viewport(80, 100); settle(vm)
        vm.nextPage(); settle(vm); service.awaitIdle(); vm.detach()
        savedPage(3)
        val reopened = model(activity, handle); reopened.bind("m", "library", score()); reopened.viewport(80, 100); settle(reopened)
        assertEquals(4, (reopened.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun concertDoesNotJumpToPersistentLibraryPageOrEnterPerformance() = runTest(dispatcher) {
        val item = score().copy(itemId = "item"); savedPage(3, item)
        val vm = model(); vm.bind("m", "item", item, ResolvedConcert("concert", "Program", listOf(item)))
        vm.viewport(80, 100); settle(vm)
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        assertNull(vm.running.value); assertFalse(vm.playMode.value)
    }
    @Test fun identityExpiringDuringHistoryReadCannotRenderAnOldFrame() = runTest(dispatcher) {
        savedPage(3)
        val vm = model(); vm.bind("m", "library", score()); vm.viewport(80, 100)
        currentMember = "other"; settle(vm)
        assertTrue(vm.state.value is PdfState.Failure); assertTrue(rendered.isEmpty())
        assertEquals(3, storage.read(ReadingOwner("local", "m")).recent.single().page)
    }
    @Test fun damagedOptionalHistoryDoesNotPreventFreshAuthorizedReadingOrOverwriteEvidence() = runTest(dispatcher) {
        val owner = ReadingOwner("local", "m"); storage.file(owner).writeBytes(byteArrayOf(3, 2, 1))
        val vm = model(); vm.bind("m", "library", score()); vm.viewport(80, 100); settle(vm)
        assertEquals(3, (vm.state.value as PdfState.Ready).sourcePage)
        assertArrayEquals(byteArrayOf(3, 2, 1), storage.file(owner).readBytes())
        assertEquals(owner, activity.issue.value?.owner)
    }
}
