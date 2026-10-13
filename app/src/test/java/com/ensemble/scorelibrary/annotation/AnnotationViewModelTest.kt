package com.ensemble.scorelibrary.annotation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class AnnotationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val scope = AnnotationScope("m", "p", "part", "v", 1)
    private val empty = AnnotationData(emptyList(), emptyList(), emptyList(), 0, 0, true, null, false)
    private var fail = false
    private var failLoad = false
    private var writes = 0
    private var failAfterCommit = false
    private var revoked = false
    private var publishCalls = 0
    private var freezeCalls = 0
    private var loads = 0
    private var barrier: CompletableDeferred<Unit>? = null
    private var loadBarrier: CompletableDeferred<Unit>? = null
    private val records = mutableMapOf<AnnotationScope, AnnotationData>()
    private val repository = object : AnnotationStore {
        override suspend fun load(scope: AnnotationScope): AnnotationData {
            loads++; loadBarrier?.await(); check(!revoked) { "撤权" }; check(!failLoad) { "读取失败" }
            return records[scope] ?: empty
        }
        override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) {
            barrier?.await()
            check(!revoked) { "撤权" }
            check(!fail) { "保存失败" }
            val before = records[scope] ?: empty
            check(layer != AnnotationLayer.SHARED || before.admin && before.concert == null) { "角色已撤销" }
            val currentGeneration = if (layer == AnnotationLayer.PERSONAL) before.personalGeneration else before.sharedGeneration
            val currentMarks = if (layer == AnnotationLayer.PERSONAL) before.personal else before.draft
            if (currentGeneration == generation + 1 && currentMarks == marks) return
            check(currentGeneration == generation) { "版本冲突" }
            writes++
            records[scope] = if (layer == AnnotationLayer.PERSONAL) before.copy(personal = marks, personalGeneration = generation + 1)
                else before.copy(draft = marks, sharedGeneration = generation + 1)
            if (failAfterCommit) { failAfterCommit = false; error("确认丢失") }
        }
        override suspend fun publish(scope: AnnotationScope) { publishCalls++; val data = records[scope] ?: empty; records[scope] = data.copy(shared = data.draft) }
        override suspend fun freeze(scope: AnnotationScope) { freezeCalls++; records[scope] = (records[scope] ?: empty).copy(locked = true) }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }
    private fun model() = AnnotationViewModel(repository, dispatcher).also { store.put("ink", it) }
    private val points = listOf(InkPoint(.2f, .3f), InkPoint(.6f, .3f))
    @Test fun editLockIsReversibleAndDoesNotUnfreezeConcert() = runTest(dispatcher) {
        records[scope] = empty.copy(concert = "concert", locked = true)
        val vm = model(); vm.bind(scope); runCurrent()
        vm.toggleEditLock(); assertFalse(vm.state.value.editable)
        vm.tool(AnnotationTool.PEN); vm.stroke(points); runCurrent(); assertTrue(vm.state.value.marks.isEmpty())
        vm.toggleEditLock(); assertTrue(vm.state.value.editable); assertTrue(vm.state.value.data!!.locked)
        vm.tool(AnnotationTool.PEN); vm.stroke(points); runCurrent(); assertEquals(1, vm.state.value.marks.size)
        vm.toggleEditLock(); vm.bind(scope.copy(page = 2)); runCurrent(); assertTrue(vm.state.value.editLocked)
        vm.bind(scope.copy(member = "other")); runCurrent(); assertFalse(vm.state.value.editLocked)
    }
    @Test fun lassoSelectsThenEntersMoveAndExpandedSymbolsRoundTrip() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.SYMBOL)
        for (symbol in musicSymbols) { vm.symbol(symbol); vm.stroke(listOf(InkPoint(.5f, .5f))); runCurrent() }
        assertEquals(musicSymbols.size, vm.state.value.marks.size)
        assertEquals(vm.state.value.marks, InkCodec.decode(InkCodec.encode(vm.state.value.marks)))
        vm.tool(AnnotationTool.LASSO)
        vm.selection(listOf(InkPoint(.1f, .1f), InkPoint(.9f, .1f), InkPoint(.9f, .9f), InkPoint(.1f, .9f)))
        runCurrent(); assertEquals(musicSymbols.size, vm.state.value.selection.size)
        assertEquals(AnnotationTool.MOVE, vm.state.value.tool)
    }
    @Test fun loadErrorIsStableAcrossRebindingUntilExplicitRetry() = runTest(dispatcher) {
        val vm = model(); failLoad = true
        vm.bind(scope); runCurrent(); assertEquals("读取失败", vm.state.value.error)
        repeat(3) { vm.bind(scope); runCurrent() }
        assertEquals(1, loads); assertFalse(vm.state.value.busy)
        failLoad = false; vm.retry(); runCurrent()
        assertEquals(2, loads); assertNull(vm.state.value.error); assertNotNull(vm.state.value.data)
    }
    @Test fun toolsUndoRedoLayerVisibilityAndReload() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent()
        vm.tool(AnnotationTool.PEN); vm.stroke(points); runCurrent()
        assertEquals(1, vm.state.value.marks.size)
        vm.undo(); runCurrent(); assertTrue(vm.state.value.marks.isEmpty())
        vm.redo(); runCurrent(); assertEquals(1, vm.state.value.marks.size)
        vm.visible(AnnotationLayer.PERSONAL, false); assertFalse(vm.state.value.editable)
        vm.stroke(points); runCurrent(); assertEquals(1, vm.state.value.marks.size)
        vm.visible(AnnotationLayer.PERSONAL, true)
        vm.layer(AnnotationLayer.SHARED); assertFalse(vm.state.value.undo)
        vm.tool(AnnotationTool.HIGHLIGHT); vm.stroke(points); runCurrent()
        assertEquals(MarkKind.HIGHLIGHT, vm.state.value.marks.single().kind)
        vm.publish(); runCurrent(); assertEquals(vm.state.value.data!!.draft, vm.state.value.data!!.shared)
        vm.layer(AnnotationLayer.PERSONAL); assertEquals(1, vm.state.value.marks.size)
        val restored = model(); restored.bind(scope); runCurrent(); assertEquals(1, restored.state.value.marks.size)
    }
    @Test fun failureRetainsInkAndRetryActuallySavesWithoutLoadingOldData() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent()
        assertEquals(1, vm.state.value.marks.size); assertEquals("保存失败", vm.state.value.error)
        assertTrue(vm.state.value.pending); assertEquals(0, writes)
        val retained = vm.state.value.marks
        fail = false; vm.retry(); vm.retry(); runCurrent()
        assertEquals(retained, records[scope]!!.personal); assertEquals(1, writes)
        assertEquals(1, loads); assertFalse(vm.state.value.pending); assertNull(vm.state.value.error)
        vm.undo(); runCurrent(); assertTrue(records[scope]!!.personal.isEmpty())
    }
    @Test fun slowSaveAccumulatesOrderedStrokesWithoutOverwritingLatestState() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val release = CompletableDeferred<Unit>(); barrier = release
        vm.stroke(points); runCurrent(); assertTrue(vm.state.value.saving); assertTrue(vm.state.value.editable)
        repeat(12) { vm.stroke(points) }
        val retained = vm.state.value.marks
        assertEquals(13, retained.size)
        release.complete(Unit); runCurrent()
        assertEquals(retained, records[scope]!!.personal); assertEquals(2, writes)
        assertEquals(2L, records[scope]!!.personalGeneration); assertFalse(vm.state.value.pending)
    }
    @Test fun failureDuringNextGestureKeepsAcceptingLocalInkWithoutAutomaticRetry() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val release = CompletableDeferred<Unit>(); barrier = release
        vm.stroke(points); runCurrent(); vm.gesture(scope, true)
        fail = true; release.complete(Unit); runCurrent()
        assertTrue(vm.state.value.editable)
        vm.stroke(points); vm.gesture(scope, false); runCurrent()
        assertEquals(2, vm.state.value.marks.size); assertEquals(0, writes)
        fail = false; vm.retry(); runCurrent(); assertEquals(2, records[scope]!!.personal.size)
    }
    @Test fun pendingBlocksPageLayerPublicationFreezeAndPerformanceRefresh() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent()
        vm.bind(scope.copy(page = 2)); vm.layer(AnnotationLayer.SHARED); vm.publish(); vm.freeze(); vm.refreshPerformance(); runCurrent()
        assertEquals(scope, vm.state.value.scope); assertEquals(AnnotationLayer.PERSONAL, vm.state.value.layer)
        assertEquals(1, vm.state.value.marks.size); assertEquals(0, publishCalls); assertEquals(0, freezeCalls)
        assertEquals(1, loads); assertTrue(vm.state.value.transitionBlocked)
    }
    @Test fun memberSwitchCancelsOldWriteAndRestoresOnlyOriginalOwnersPendingInk() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val oldToken = vm.state.value.inputToken
        val release = CompletableDeferred<Unit>(); barrier = release
        vm.stroke(points); runCurrent()
        val other = scope.copy(member = "other", page = 2)
        vm.bind(other); runCurrent(); release.complete(Unit); runCurrent()
        assertEquals(other, vm.state.value.scope); assertTrue(vm.state.value.marks.isEmpty()); assertEquals(0, writes)
        vm.tool(AnnotationTool.PEN); vm.stroke(points, scope, oldToken); runCurrent(); assertTrue(vm.state.value.marks.isEmpty())
        vm.bind(scope); runCurrent(); assertEquals(1, vm.state.value.marks.size); assertTrue(vm.state.value.pending)
        vm.retry(); runCurrent(); assertEquals(1, records[scope]!!.personal.size); assertNull(records[other])
    }
    @Test fun explicitLeaveKeepsMemoryDraftButFreshViewModelDoesNotRecoverIt() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent(); vm.suspendSession()
        vm.bind(scope.copy(page = 2)); runCurrent(); assertTrue(vm.state.value.marks.isEmpty())
        vm.bind(scope); runCurrent(); assertEquals(1, vm.state.value.marks.size)
        val fresh = model(); fresh.bind(scope); runCurrent(); assertTrue(fresh.state.value.marks.isEmpty())
    }
    @Test fun conflictRetainsBothVersionsAndNeverRebasesAutomatically() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val remote = InkMark(kind = MarkKind.SYMBOL, points = points)
        records[scope] = empty.copy(personal = listOf(remote), personalGeneration = 1)
        vm.stroke(points); runCurrent(); val local = vm.state.value.marks
        repeat(3) { vm.retry(); runCurrent() }
        assertEquals("版本冲突", vm.state.value.error); assertEquals(local, vm.state.value.marks)
        assertEquals(listOf(remote), records[scope]!!.personal); assertEquals(0, writes)
        vm.discardPending(); runCurrent(); assertEquals(listOf(remote), vm.state.value.marks); assertFalse(vm.state.value.pending)
    }
    @Test fun acknowledgementLossIsRetriedIdempotentlyBeforeQueuedEdit() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        failAfterCommit = true
        vm.stroke(points); vm.stroke(points); runCurrent()
        assertTrue(vm.state.value.pending); assertEquals(1, writes)
        vm.retry(); runCurrent()
        assertEquals(2, records[scope]!!.personal.size); assertEquals(2, writes)
        assertEquals(2L, records[scope]!!.personalGeneration); assertFalse(vm.state.value.pending)
    }
    @Test fun revokedAuthorityStopsWriteAndRetainsPendingWithoutShowingAnotherMember() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val release = CompletableDeferred<Unit>(); barrier = release
        vm.stroke(points); runCurrent(); revoked = true; release.complete(Unit); runCurrent()
        vm.retry(); runCurrent(); assertEquals(0, writes); assertTrue(vm.state.value.pending)
        vm.refreshAuthorization(); runCurrent(); assertNull(vm.state.value.data)
        assertTrue(vm.state.value.pending); assertFalse(vm.state.value.editable)
        revoked = false; vm.retry(); runCurrent(); vm.retry(); runCurrent()
        assertEquals(1, records[scope]!!.personal.size)
    }
    @Test fun undoRedoDuringSlowSaveKeepsLatestIntentAndHistory() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val release = CompletableDeferred<Unit>(); barrier = release
        vm.stroke(points); runCurrent(); vm.stroke(points); vm.undo(); vm.redo()
        val intended = vm.state.value.marks; assertEquals(2, intended.size)
        release.complete(Unit); runCurrent(); assertEquals(intended, records[scope]!!.personal)
        vm.undo(); runCurrent(); assertEquals(1, records[scope]!!.personal.size)
        vm.redo(); runCurrent(); assertEquals(intended, records[scope]!!.personal)
    }
    @Test fun roleDowngradeHidesSharedDraftAndRefusesPendingRetry() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.layer(AnnotationLayer.SHARED); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent()
        records[scope] = empty.copy(admin = false)
        vm.refreshAuthorization(); runCurrent(); assertTrue(vm.state.value.data!!.draft.isEmpty())
        assertTrue(vm.state.value.pending); assertFalse(vm.state.value.editable)
        fail = false; vm.retry(); runCurrent(); assertEquals(0, writes)
        assertEquals("角色已撤销", vm.state.value.error)
    }
    @Test fun retainedPageLimitProvidesBackpressureWithoutEvictingExistingDrafts() = runTest(dispatcher) {
        val vm = model(); fail = true
        repeat(16) { page ->
            vm.bind(scope.copy(page = page)); runCurrent(); vm.tool(AnnotationTool.PEN)
            vm.stroke(points); runCurrent(); vm.suspendSession()
        }
        vm.bind(scope.copy(page = 16)); runCurrent(); vm.tool(AnnotationTool.PEN); vm.stroke(points)
        assertTrue(vm.state.value.marks.isEmpty()); assertNotNull(vm.state.value.inputNotice)
        vm.bind(scope.copy(page = 0)); runCurrent(); assertEquals(1, vm.state.value.marks.size)
        assertTrue(vm.state.value.pending)
    }
    @Test fun restoredPendingInkIsHiddenUntilAuthorizationSucceedsIncludingFailedRetries() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent(); val retained = vm.state.value.marks
        vm.suspendSession(); revoked = true
        val denied = CompletableDeferred<Unit>(); loadBarrier = denied
        vm.bind(scope)
        assertNull("bind must not publish the stored draft before load", vm.state.value.data)
        assertTrue(vm.state.value.marks.isEmpty()); assertTrue(vm.state.value.pending)
        runCurrent(); assertNull(vm.state.value.data)
        denied.complete(Unit); runCurrent(); assertNull(vm.state.value.data); assertNotNull(vm.state.value.error)
        val retryDenied = CompletableDeferred<Unit>(); loadBarrier = retryDenied
        vm.retry(); runCurrent(); assertNull(vm.state.value.data); assertTrue(vm.state.value.marks.isEmpty())
        retryDenied.complete(Unit); runCurrent(); assertNull(vm.state.value.data); assertEquals(0, writes)
        revoked = false; fail = false
        val allowed = CompletableDeferred<Unit>(); loadBarrier = allowed
        vm.retry(); runCurrent(); assertNull(vm.state.value.data)
        allowed.complete(Unit); runCurrent()
        assertEquals(retained, vm.state.value.marks); assertTrue(vm.state.value.pending); assertEquals(0, writes)
        vm.retry(); runCurrent(); assertEquals(retained, records[scope]!!.personal); assertEquals(1, writes)
    }
    @Test fun sharedDraftIsNotPublishedDuringDelayedRoleRevalidation() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.layer(AnnotationLayer.SHARED); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent(); val retained = vm.state.value.marks
        records[scope] = empty.copy(admin = false)
        val release = CompletableDeferred<Unit>(); loadBarrier = release
        vm.refreshAuthorization()
        assertNull(vm.state.value.data); assertTrue(vm.state.value.marks.isEmpty())
        runCurrent(); assertNull(vm.state.value.data)
        release.complete(Unit); runCurrent()
        assertFalse(vm.state.value.data!!.admin); assertTrue(vm.state.value.data!!.draft.isEmpty())
        assertTrue(vm.state.value.pending); assertFalse(vm.state.value.editable)
        fail = false; vm.retry(); runCurrent(); assertEquals(0, writes)
        records[scope] = empty
        val allowed = CompletableDeferred<Unit>(); loadBarrier = allowed
        vm.refreshAuthorization(); assertNull(vm.state.value.data); runCurrent(); assertNull(vm.state.value.data)
        allowed.complete(Unit); runCurrent(); assertEquals(retained, vm.state.value.marks)
        assertTrue(vm.state.value.pending); vm.retry(); runCurrent(); assertEquals(retained, records[scope]!!.draft)
    }
    @Test fun ordinaryReloadAlsoHidesPreviouslyLoadedInkUntilAuthorityReturns() = runTest(dispatcher) {
        val mark = InkMark(kind = MarkKind.PEN, points = points)
        records[scope] = empty.copy(personal = listOf(mark), draft = listOf(mark), shared = listOf(mark))
        val vm = model(); vm.bind(scope); runCurrent(); assertEquals(1, vm.state.value.marks.size)
        val release = CompletableDeferred<Unit>(); loadBarrier = release; revoked = true
        vm.retry(); assertNull("Reload must quarantine all previously authorized layers", vm.state.value.data)
        runCurrent(); assertNull(vm.state.value.data)
        release.complete(Unit); runCurrent(); assertNull(vm.state.value.data); assertFalse(vm.state.value.editable)
    }
    @Test fun delayedRestorationCannotExposeOriginalMembersDraftAfterIdentitySwitch() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent(); val retained = vm.state.value.marks; vm.suspendSession()
        val release = CompletableDeferred<Unit>(); loadBarrier = release
        vm.bind(scope); runCurrent(); assertNull(vm.state.value.data)
        loadBarrier = null
        val other = scope.copy(member = "other")
        vm.bind(other); runCurrent(); assertTrue(vm.state.value.marks.isEmpty())
        release.complete(Unit); runCurrent()
        assertEquals(other, vm.state.value.scope); assertTrue(vm.state.value.marks.isEmpty())
        vm.bind(scope); assertNull(vm.state.value.data); runCurrent()
        assertEquals(retained, vm.state.value.marks); assertTrue(vm.state.value.pending)
    }
    @Test fun staleGestureTokenCannotWriteAfterReturningToSamePage() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); val token = vm.state.value.inputToken
        vm.suspendSession(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        vm.stroke(points, scope, token); vm.gesture(scope, true, token)
        assertTrue(vm.state.value.marks.isEmpty()); assertFalse(vm.state.value.gestureActive)
    }
}
