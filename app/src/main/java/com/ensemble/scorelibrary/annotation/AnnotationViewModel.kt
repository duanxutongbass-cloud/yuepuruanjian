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
    val gestureActive: Boolean = false, val editLocked: Boolean = false) {
    val editable get() = data != null && !editLocked && !busy && error == null &&
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
    fun bind(scope: AnnotationScope) {
        if (mutable.value.scope == scope && (mutable.value.data != null || mutable.value.busy || mutable.value.error != null)) return
        undoStack.clear(); redoStack.clear()
        epoch++
        mutable.value = AnnotationState(scope = scope, busy = true, color = mutable.value.color, width = mutable.value.width,
            symbol = mutable.value.symbol, editLocked = saved.get<Boolean>("edit-lock-${scope.member}") ?: false)
        load(scope)
    }
    fun retry() { if (mutable.value.busy) return; undoStack.clear(); redoStack.clear(); epoch++
        mutable.value.scope?.let { scope -> mutable.value = mutable.value.copy(busy = true, error = null,
            selection = emptySet(), undo = false, redo = false); load(scope) } }
    fun refreshPerformance() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(data = null)
        retry()
    }
    private fun load(scope: AnnotationScope) { val token = epoch; viewModelScope.launch {
        try {
            val data = repository.load(scope)
            if (mutable.value.scope == scope && epoch == token) mutable.value = mutable.value.copy(data = data, busy = false, error = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (mutable.value.scope == scope && epoch == token) mutable.value = mutable.value.copy(busy = false, data = null,
            error = safeError(failure)) }
    } }
    private fun safeError(failure: Exception) = if (failure is IllegalStateException || failure is IllegalArgumentException)
        failure.message ?: "批注读取或保存失败" else "批注读取或保存失败，请重试"
    fun tool(tool: AnnotationTool) { mutable.value = mutable.value.copy(tool = tool) }
    fun toggleEditLock() {
        if (mutable.value.busy || mutable.value.gestureActive) return
        mutable.value = mutable.value.copy(editLocked = !mutable.value.editLocked, tool = AnnotationTool.READ, selection = emptySet())
        mutable.value.scope?.member?.let { saved["edit-lock-$it"] = mutable.value.editLocked }
    }
    fun gesture(scope: AnnotationScope?, active: Boolean) {
        if (scope != null && mutable.value.scope == scope) mutable.value = mutable.value.copy(gestureActive = active)
    }
    fun color(color: Long) { mutable.value = mutable.value.copy(color = color) }
    fun width(width: Float) { mutable.value = mutable.value.copy(width = width.coerceIn(.001f, .012f)) }
    fun symbol(symbol: String) { require(symbol in musicSymbols); mutable.value = mutable.value.copy(symbol = symbol) }
    fun visible(layer: AnnotationLayer, visible: Boolean) {
        mutable.value = if (layer == AnnotationLayer.PERSONAL) mutable.value.copy(showPersonal = visible) else mutable.value.copy(showShared = visible)
    }
    fun layer(layer: AnnotationLayer) {
        if (mutable.value.busy) return
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
    fun stroke(points: List<InkPoint>) {
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
        val scope = before.scope ?: return
        val data = before.data ?: return
        val token = epoch
        mutable.value = before.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                repository.save(scope, before.layer, updated, if (before.layer == AnnotationLayer.PERSONAL) data.personalGeneration else data.sharedGeneration)
                if (mutable.value.scope != scope || epoch != token) return@launch
                when (history) {
                    -1 -> { undoStack.removeLast(); redoStack.addLast(before.marks) }
                    1 -> { redoStack.removeLast(); undoStack.addLast(before.marks) }
                    else -> { undoStack.addLast(before.marks); redoStack.clear() }
                }
                while (undoStack.size > 20 || undoStack.sumOf { marks -> marks.sumOf { it.points.size.toLong() } } > 400_000) undoStack.removeFirst()
                while (redoStack.size > 20 || redoStack.sumOf { marks -> marks.sumOf { it.points.size.toLong() } } > 400_000) redoStack.removeFirst()
                val after = if (before.layer == AnnotationLayer.PERSONAL) data.copy(personal = updated, personalGeneration = data.personalGeneration + 1)
                    else data.copy(draft = updated, sharedGeneration = data.sharedGeneration + 1)
                mutable.value = mutable.value.copy(data = after, busy = false, selection = emptySet(), undo = undoStack.isNotEmpty(), redo = redoStack.isNotEmpty())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mutable.value.scope == scope && epoch == token) mutable.value = mutable.value.copy(busy = false, error = safeError(failure)) }
        }
    }
    fun undo() { undoStack.lastOrNull()?.let { edit(it, -1) } }
    fun redo() { redoStack.lastOrNull()?.let { edit(it, 1) } }
    fun publish() = operation { repository.publish(it) }
    fun freeze() = operation { repository.freeze(it) }
    private fun operation(block: suspend (AnnotationScope) -> Unit) {
        val scope = mutable.value.scope ?: return
        if (mutable.value.busy) return
        val token = epoch
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block(scope); if (mutable.value.scope == scope && epoch == token) load(scope) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mutable.value.scope == scope && epoch == token) mutable.value = mutable.value.copy(busy = false, error = safeError(failure)) }
        }
    }
}
