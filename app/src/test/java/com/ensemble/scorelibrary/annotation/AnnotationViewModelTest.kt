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
    private var loads = 0
    private var barrier: CompletableDeferred<Unit>? = null
    private val records = mutableMapOf<AnnotationScope, AnnotationData>()
    private val repository = object : AnnotationStore {
        override suspend fun load(scope: AnnotationScope): AnnotationData {
            loads++; check(!failLoad) { "读取失败" }
            return records[scope] ?: empty
        }
        override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) {
            barrier?.await()
            check(!fail) { "保存失败" }
            val before = records[scope] ?: empty
            records[scope] = if (layer == AnnotationLayer.PERSONAL) before.copy(personal = marks, personalGeneration = generation + 1)
                else before.copy(draft = marks, sharedGeneration = generation + 1)
        }
        override suspend fun publish(scope: AnnotationScope) { val data = records[scope] ?: empty; records[scope] = data.copy(shared = data.draft) }
        override suspend fun freeze(scope: AnnotationScope) { records[scope] = (records[scope] ?: empty).copy(locked = true) }
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
    @Test fun failureDoesNotPretendToSaveAndRetryClearsStaleHistory() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        fail = true; vm.stroke(points); runCurrent()
        assertTrue(vm.state.value.marks.isEmpty()); assertEquals("保存失败", vm.state.value.error)
        assertFalse(vm.state.value.editable); assertFalse(vm.state.value.undo)
        fail = false; vm.retry(); runCurrent(); assertTrue(vm.state.value.editable)
        vm.stroke(points); runCurrent(); assertTrue(vm.state.value.undo)
        vm.retry(); runCurrent(); assertFalse(vm.state.value.undo)
    }
    @Test fun delayedSaveCannotOverwriteNewMemberOrPage() = runTest(dispatcher) {
        val vm = model(); vm.bind(scope); runCurrent(); vm.tool(AnnotationTool.PEN)
        val release = CompletableDeferred<Unit>(); barrier = release
        vm.stroke(points); runCurrent(); assertTrue(vm.state.value.busy)
        val other = scope.copy(member = "other", page = 2)
        vm.bind(other); runCurrent(); assertTrue(vm.state.value.marks.isEmpty())
        release.complete(Unit); runCurrent()
        assertEquals(other, vm.state.value.scope); assertTrue(vm.state.value.marks.isEmpty())
        assertFalse(vm.state.value.undo); assertEquals(1, records[scope]!!.personal.size)
    }
}
