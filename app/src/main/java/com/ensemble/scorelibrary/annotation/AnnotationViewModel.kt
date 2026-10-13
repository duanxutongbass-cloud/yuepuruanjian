package com.ensemble.scorelibrary.annotation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InkComputation

data class AnnotationState(val scope: AnnotationScope? = null, val data: AnnotationData? = null,
    val busy: Boolean = false, val error: String? = null, val layer: AnnotationLayer = AnnotationLayer.PERSONAL,
    val tool: AnnotationTool = AnnotationTool.READ, val color: Long = 0xffd32f2f, val width: Float = .003f,
    val symbol: String = "f", val showPersonal: Boolean = true, val showShared: Boolean = true,
    val draftMode: Boolean = false, val selection: Set<String> = emptySet(), val undo: Boolean = false, val redo: Boolean = false,
    val gestureActive: Boolean = false, val editLocked: Boolean = false,
    val saving: Boolean = false, val pending: Boolean = false, val inputToken: Long = 0,
    val inputNotice: String? = null) {
    val transitionBlocked get() = busy || saving || pending || gestureActive
    val editable get() = data != null && !editLocked && !busy && (error == null || pending) &&
        (layer == AnnotationLayer.PERSONAL && showPersonal || layer == AnnotationLayer.SHARED && data.admin && data.concert == null && draftMode && showShared)
    val marks get() = if (layer == AnnotationLayer.PERSONAL) data?.personal.orEmpty() else data?.draft.orEmpty()
}

@HiltViewModel
class AnnotationViewModel @Inject constructor(private val repository: AnnotationStore,
    @param:InkComputation private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()) : ViewModel() {
    private val mutable = MutableStateFlow(AnnotationState())
    val state = mutable.asStateFlow()
    private val undoStack = ArrayDeque<List<InkMark>>()
    private val redoStack = ArrayDeque<List<InkMark>>()
    private var epoch = 0L
    private var writeJob: Job? = null
    // At most an in-flight snapshot and the latest accumulated edit. Source identity is immutable.
    private data class Pending(val marks: List<InkMark>, val generation: Long)
    private val pending = ArrayDeque<Pending>()
    private data class Draft(val state: AnnotationState, val writes: List<Pending>,
        val undo: List<List<InkMark>>, val redo: List<List<InkMark>>)
    private val drafts = mutableMapOf<AnnotationScope, Draft>()

    /** Forced identity/navigation loss: stop writes, retain only in this ViewModel's memory. */
    fun suspendSession() {
        val before = mutable.value
        before.scope?.let { scope -> if (pending.isNotEmpty()) drafts[scope] = Draft(
            before.copy(saving = false, busy = false, gestureActive = false), pending.toList(), undoStack.toList(), redoStack.toList()) }
        epoch++
        writeJob?.cancel(); writeJob = null
        pending.clear(); undoStack.clear(); redoStack.clear()
        mutable.value = AnnotationState(inputToken = epoch, color = before.color, width = before.width, symbol = before.symbol)
    }
    fun bind(scope: AnnotationScope) {
        if (mutable.value.scope == scope && (mutable.value.data != null || mutable.value.busy || mutable.value.error != null)) return
        // Ordinary page transitions must first finish/discard edits. Identity changes are forced isolation.
        if (mutable.value.scope?.member == scope.member && (mutable.value.pending || mutable.value.gestureActive)) return
        suspendSession()
        val draft = drafts.remove(scope)
        if (draft != null) {
            pending.addAll(draft.writes); undoStack.addAll(draft.undo); redoStack.addAll(draft.redo)
            // Retained drafts are private until this session has freshly revalidated authority.
            // busy alone is not a visibility boundary: ordinary readers can draw cached data.
            mutable.value = draft.state.copy(data = null, busy = true, inputToken = epoch,
                selection = emptySet(), undo = false, redo = false)
        } else mutable.value = AnnotationState(scope = scope, busy = true, inputToken = epoch,
            color = mutable.value.color, width = mutable.value.width, symbol = mutable.value.symbol,
            editLocked = saved.get<Boolean>("edit-lock-${scope.member}") ?: false)
        load(scope)
    }
    fun retry() {
        if (mutable.value.busy || mutable.value.saving || mutable.value.gestureActive) return
        if (pending.isNotEmpty()) {
            if (mutable.value.data == null) mutable.value.scope?.let { scope ->
                mutable.value = mutable.value.copy(data = null, busy = true, error = null); load(scope)
            } else drain()
            return
        }
        undoStack.clear(); redoStack.clear(); epoch++
        mutable.value.scope?.let { scope -> mutable.value = mutable.value.copy(data = null, busy = true, error = null,
            selection = emptySet(), undo = false, redo = false, inputToken = epoch); load(scope) }
    }
    /** Called only after explicit confirmation in the toolbar. */
    fun discardPending() {
        if (mutable.value.busy || mutable.value.saving || mutable.value.gestureActive) return
        pending.clear()
        mutable.value = mutable.value.copy(pending = false)
        retry()
    }
    fun refreshPerformance() {
        if (mutable.value.transitionBlocked) return
        mutable.value = mutable.value.copy(data = null)
        retry()
    }
    fun refreshAuthorization() {
        val scope = mutable.value.scope ?: return
        // Cancel queued writes before re-reading authority; repository rechecks every transaction too.
        suspendSession()
        bind(scope)
    }
    private fun load(scope: AnnotationScope) { val token = epoch; viewModelScope.launch {
        try {
            val data = repository.load(scope)
            if (mutable.value.scope == scope && epoch == token) {
                val local = mutable.value
                val restored = if (pending.isEmpty()) data else if (local.layer == AnnotationLayer.PERSONAL)
                    data.copy(personal = pending.last().marks, personalGeneration = pending.first().generation)
                else data.copy(draft = if (data.admin && data.concert == null) pending.last().marks else emptyList(),
                    sharedGeneration = pending.first().generation)
                mutable.value = local.copy(data = restored, busy = false,
                    undo = undoStack.isNotEmpty(), redo = redoStack.isNotEmpty(),
                    error = if (pending.isEmpty()) null else "有未保存批注，请重试保存；仅保留在当前进程内")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (mutable.value.scope == scope && epoch == token) mutable.value = mutable.value.copy(busy = false, data = null,
            error = safeError(failure)) }
    } }
    private fun safeError(failure: Exception) = if (failure is IllegalStateException || failure is IllegalArgumentException)
        failure.message ?: "批注读取或保存失败" else "批注读取或保存失败，请重试"
    fun tool(tool: AnnotationTool) { mutable.value = mutable.value.copy(tool = tool) }
    fun toggleEditLock() {
        if (mutable.value.transitionBlocked) return
        mutable.value = mutable.value.copy(editLocked = !mutable.value.editLocked, tool = AnnotationTool.READ, selection = emptySet())
        mutable.value.scope?.member?.let { saved["edit-lock-$it"] = mutable.value.editLocked }
    }
    fun gesture(scope: AnnotationScope?, active: Boolean, token: Long = mutable.value.inputToken) {
        if (scope != null && mutable.value.scope == scope && token == epoch) mutable.value = mutable.value.copy(gestureActive = active, inputNotice = if (active) null else mutable.value.inputNotice)
    }
    fun cancelGesture(scope: AnnotationScope?, token: Long) {
        if (scope == mutable.value.scope && token == epoch) mutable.value = mutable.value.copy(
            gestureActive = false, inputNotice = "本次未完成笔画已取消，请重新书写；已完成笔画仍保留")
    }
    fun color(color: Long) { mutable.value = mutable.value.copy(color = color) }
    fun width(width: Float) { mutable.value = mutable.value.copy(width = width.coerceIn(.001f, .012f)) }
    fun symbol(symbol: String) { require(symbol in musicSymbols); mutable.value = mutable.value.copy(symbol = symbol) }
    fun visible(layer: AnnotationLayer, visible: Boolean) {
        mutable.value = if (layer == AnnotationLayer.PERSONAL) mutable.value.copy(showPersonal = visible) else mutable.value.copy(showShared = visible)
    }
    fun layer(layer: AnnotationLayer) {
        if (mutable.value.transitionBlocked) return
        undoStack.clear(); redoStack.clear()
        mutable.value = mutable.value.copy(layer = layer, draftMode = layer == AnnotationLayer.SHARED,
            selection = emptySet(), undo = false, redo = false, tool = AnnotationTool.READ)
    }
    fun selection(polygon: List<InkPoint>) {
        val before = mutable.value; val token = epoch
        if (!before.editable) return
        mutable.value = before.copy(busy = true)
        viewModelScope.launch {
            val selection = withContext(computation) { InkGeometry.selected(before.marks, polygon) }
            if (epoch == token) mutable.value = mutable.value.copy(selection = selection, busy = false,
                tool = if (selection.isEmpty()) AnnotationTool.LASSO else AnnotationTool.MOVE)
        }
    }
    fun stroke(points: List<InkPoint>, scope: AnnotationScope? = mutable.value.scope, token: Long = mutable.value.inputToken) {
        if (scope != mutable.value.scope || token != epoch) return
        val state = mutable.value
        if (!state.editable || points.isEmpty()) return
        if (state.tool == AnnotationTool.ERASER || state.tool == AnnotationTool.MOVE) {
            val token = epoch
            mutable.value = state.copy(busy = true)
            viewModelScope.launch {
                val updated = withContext(computation) {
                    if (state.tool == AnnotationTool.ERASER) InkGeometry.erase(state.marks, points, .015f)
                    else InkGeometry.move(state.marks, state.selection, points.last().x - points.first().x, points.last().y - points.first().y)
                }
                if (epoch == token) { mutable.value = mutable.value.copy(busy = false); edit(updated) }
            }
            return
        }
        val updated = when (state.tool) {
            AnnotationTool.PEN -> state.marks + InkMark(kind = MarkKind.PEN, points = points, color = state.color, width = state.width)
            AnnotationTool.HIGHLIGHT -> state.marks + InkMark(kind = MarkKind.HIGHLIGHT, points = points, color = state.color, width = state.width * 3)
            AnnotationTool.SYMBOL -> state.marks + InkMark(kind = MarkKind.SYMBOL, points = listOf(points.first()), color = state.color, width = state.width, symbol = state.symbol)
            AnnotationTool.ERASER -> InkGeometry.erase(state.marks, points, .015f)
            AnnotationTool.LASSO -> { selection(points); return }
            AnnotationTool.MOVE -> InkGeometry.move(state.marks, state.selection, points.last().x - points.first().x, points.last().y - points.first().y)
            AnnotationTool.READ -> return
        }
        edit(updated)
    }
    fun deleteSelection() = edit(mutable.value.marks.filterNot { it.id in mutable.value.selection })
    private fun edit(updated: List<InkMark>, history: Int = 0) {
        val before = mutable.value
        if (!before.editable || before.marks == updated) return
        val data = before.data ?: return
        if (drafts.size >= 16 && pending.isEmpty()) {
            mutable.value = before.copy(inputNotice = "已有 16 页未保存批注，本次修改未接收；请返回原页面保存或放弃后继续")
            return
        }
        if (updated.size > 2000 || updated.sumOf { it.points.size.toLong() } > 100_000) {
            mutable.value = before.copy(inputNotice = "本页笔画达到上限，本次修改未接收；请撤销或精简后重试")
            return
        }
        when (history) {
            -1 -> { undoStack.removeLast(); redoStack.addLast(before.marks) }
            1 -> { redoStack.removeLast(); undoStack.addLast(before.marks) }
            else -> { undoStack.addLast(before.marks); redoStack.clear() }
        }
        while (undoStack.size > 20 || undoStack.sumOf { marks -> marks.sumOf { it.points.size.toLong() } } > 400_000) undoStack.removeFirst()
        while (redoStack.size > 20 || redoStack.sumOf { marks -> marks.sumOf { it.points.size.toLong() } } > 400_000) redoStack.removeFirst()
        val generation = if (before.layer == AnnotationLayer.PERSONAL) data.personalGeneration else data.sharedGeneration
        if (pending.size == 2) pending.removeLast()
        pending.addLast(Pending(updated, if (pending.isEmpty()) generation else pending.first().generation + 1))
        val after = if (before.layer == AnnotationLayer.PERSONAL) data.copy(personal = updated) else data.copy(draft = updated)
        mutable.value = before.copy(data = after, pending = true, selection = emptySet(), inputNotice = null,
            undo = undoStack.isNotEmpty(), redo = redoStack.isNotEmpty())
        if (before.error == null) drain()
    }
    private fun drain() {
        val before = mutable.value
        val scope = before.scope ?: return
        if (before.saving || pending.isEmpty()) return
        val token = epoch
        val layer = before.layer
        mutable.value = before.copy(saving = true, error = null)
        writeJob = viewModelScope.launch {
            try {
                while (pending.isNotEmpty() && epoch == token) {
                    val write = pending.first()
                    repository.save(scope, layer, write.marks, write.generation)
                    if (epoch != token) return@launch
                    pending.removeFirst()
                    val data = mutable.value.data
                    val after = if (layer == AnnotationLayer.PERSONAL) data?.copy(personalGeneration = write.generation + 1)
                        else data?.copy(sharedGeneration = write.generation + 1)
                    mutable.value = mutable.value.copy(data = after, pending = pending.isNotEmpty())
                }
                if (epoch == token) mutable.value = mutable.value.copy(saving = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == token) mutable.value = mutable.value.copy(saving = false, error = safeError(failure))
            }
        }
    }
    fun undo() { undoStack.lastOrNull()?.let { edit(it, -1) } }
    fun redo() { redoStack.lastOrNull()?.let { edit(it, 1) } }
    fun publish() = operation { repository.publish(it) }
    fun freeze() = operation { repository.freeze(it) }
    private fun operation(block: suspend (AnnotationScope) -> Unit) {
        val scope = mutable.value.scope ?: return
        if (mutable.value.transitionBlocked) return
        val token = epoch
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block(scope); if (mutable.value.scope == scope && epoch == token) load(scope) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mutable.value.scope == scope && epoch == token) mutable.value = mutable.value.copy(busy = false, error = safeError(failure)) }
        }
    }
}
