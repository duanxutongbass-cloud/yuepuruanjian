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

data class InkRecoveryConflict(val local:List<InkMark>,val saved:List<InkMark>,val generation:Long,val source:RecoverySource)

data class AnnotationState(val scope: AnnotationScope? = null, val data: AnnotationData? = null,
    val busy: Boolean = false, val error: String? = null, val layer: AnnotationLayer = AnnotationLayer.PERSONAL,
    val tool: AnnotationTool = AnnotationTool.READ, val color: Long = 0xffd32f2f, val width: Float = .003f,
    val symbol: String = "f", val showPersonal: Boolean = true, val showShared: Boolean = true,
    val symbolScale: Float = 1f, val hairpinLength: Float = .18f, val inputMode: InkInputMode = InkInputMode.TOUCH,
    val pressureEnabled:Boolean=false,val sideButton:InkSideButton=InkSideButton.OFF,
    val draftMode: Boolean = false, val selection: Set<String> = emptySet(), val undo: Boolean = false, val redo: Boolean = false,
    val gestureActive: Boolean = false, val editLocked: Boolean = false,
    val saving: Boolean = false, val pending: Boolean = false, val inputToken: Long = 0,
    val inputNotice: String? = null, val recoveryDurable:Boolean = false, val recoveryBlocked:Boolean = false,
    val recoveryConflict:InkRecoveryConflict? = null) {
    val transitionBlocked get() = busy || saving || pending || gestureActive
    val editable get() = data != null && !editLocked && !busy && !recoveryBlocked && (error == null || pending) &&
        (layer == AnnotationLayer.PERSONAL && showPersonal || layer == AnnotationLayer.SHARED && data.admin && data.concert == null && draftMode && showShared)
    val marks get() = if (layer == AnnotationLayer.PERSONAL) data?.personal.orEmpty() else data?.draft.orEmpty()
}

@HiltViewModel
class AnnotationViewModel @Inject constructor(private val repository: AnnotationStore,
    @param:InkComputation private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
    val cloud: com.ensemble.scorelibrary.sync.TeamInkSync? = null,
    private val operations: com.ensemble.scorelibrary.diagnostics.TestOperations? = null,
    private val recovery:InkRecovery? = null,
    private val inputSettings: InkInputSettings? = null) : ViewModel() {
    private val mutable = MutableStateFlow(AnnotationState(inputMode = inputSettings?.load()
        ?: saved.get<String>("ink-input-mode")?.let { value -> InkInputMode.entries.firstOrNull { it.name == value } ?: InkInputMode.PEN }
        ?: InkInputMode.TOUCH, pressureEnabled=inputSettings?.pressure() ?: saved.get<Boolean>("ink-pressure") ?: false,
        sideButton=inputSettings?.sideButton() ?: saved.get<String>("ink-side-button")?.let { name -> InkSideButton.entries.firstOrNull { it.name==name } } ?: InkSideButton.OFF))
    val state = mutable.asStateFlow()
    private val undoStack = ArrayDeque<List<InkMark>>()
    private val redoStack = ArrayDeque<List<InkMark>>()
    private var epoch = 0L
    private var writeJob: Job? = null
    // At most an in-flight snapshot and the latest accumulated edit. Source identity is immutable.
    private val pending = ArrayDeque<InkDraftEdit>()
    private data class Draft(val state: AnnotationState, val writes: List<InkDraftEdit>,
        val undo: List<List<InkMark>>, val redo: List<List<InkMark>>)
    private val drafts = mutableMapOf<AnnotationScope, Draft>()
    private var cloudHold: String? = null
    private var journal:InkRecovery.Session? = null
    private var checkpoint:InkReceipt? = null
    private fun releaseJournal() { journal?.let { recovery?.release(it) };journal=null;checkpoint=null }
    private fun holdCloud() {
        if (cloudHold == null) mutable.value.scope?.let { cloudHold = cloud?.hold(it, mutable.value.layer) }
    }
    private fun releaseCloud() { cloud?.release(cloudHold); cloudHold = null }
    override fun onCleared() { releaseCloud();releaseJournal(); super.onCleared() }
    init {
        if (cloud != null) viewModelScope.launch {
            cloud.events.collect { event ->
                val before = mutable.value; val scope = before.scope
                if (scope != null && event.version == scope.version && event.page == scope.page && cloud.current()?.key == event.key &&
                    !before.transitionBlocked) reloadCloud()
            }
        }
    }
    /** A cloud refresh swaps only ink after a guarded read; it never clears the rendered PDF. */
    private suspend fun reloadCloud() {
        val before = mutable.value; val scope = before.scope ?: return; val token = epoch
        val data = try { repository.load(scope) } catch (_: Exception) { return }
        if (epoch == token && mutable.value == before && !before.transitionBlocked) {
            if(recovery!=null && data.recoverySource!=before.data?.recoverySource) { reloadRecovery(false);return }
            if (data.personal != before.data?.personal || data.draft != before.data?.draft) { undoStack.clear(); redoStack.clear() }
            mutable.value = before.copy(data = data, undo = undoStack.isNotEmpty(), redo = redoStack.isNotEmpty())
        }
    }
    fun resolveCloud(conflict: com.ensemble.scorelibrary.sync.InkConflict, keepLocal: Boolean) = operation { scope ->
        val sync = checkNotNull(cloud); val collection = checkNotNull(sync.current())
        sync.resolve(collection, scope.version, scope.page, conflict, keepLocal)
    }

    /** Forced navigation loss cancels database work; accepted recovery writes continue independently. */
    fun suspendSession() {
        releaseCloud()
        val before = mutable.value
        before.scope?.let { scope -> if (pending.isNotEmpty()) drafts[scope] = Draft(
            before.copy(saving = false, busy = false, gestureActive = false), pending.toList(), undoStack.toList(), redoStack.toList()) }
        releaseJournal()
        epoch++
        writeJob?.cancel(); writeJob = null
        pending.clear(); undoStack.clear(); redoStack.clear()
        mutable.value = AnnotationState(inputToken = epoch, color = before.color, width = before.width, symbol = before.symbol,
            symbolScale = before.symbolScale, hairpinLength = before.hairpinLength, inputMode = before.inputMode,
            pressureEnabled=before.pressureEnabled,sideButton=before.sideButton)
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
            mutable.value = draft.state.copy(data = null, busy = true, inputToken = epoch, inputMode = mutable.value.inputMode,
                pressureEnabled=mutable.value.pressureEnabled,sideButton=mutable.value.sideButton,
                selection = emptySet(), undo = false, redo = false)
        } else mutable.value = AnnotationState(scope = scope, busy = true, inputToken = epoch,
            color = mutable.value.color, width = mutable.value.width, symbol = mutable.value.symbol, symbolScale = mutable.value.symbolScale,
            hairpinLength = mutable.value.hairpinLength,
            inputMode = mutable.value.inputMode,
            pressureEnabled=mutable.value.pressureEnabled,sideButton=mutable.value.sideButton,
            editLocked = saved.get<Boolean>("edit-lock-${scope.member}") ?: false)
        load(scope)
    }
    fun retry() {
        if (mutable.value.busy || mutable.value.saving || mutable.value.gestureActive) return
        if(recovery!=null) { reloadRecovery(true);return }
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
        if(recovery!=null) {
            val session=journal ?: return
            val token=epoch
            mutable.value=mutable.value.copy(busy=true)
            viewModelScope.launch {
                try {
                    recovery.await(recovery.submit(session,emptyList()))
                    if(epoch!=token)return@launch
                    pending.clear()
                    mutable.value=mutable.value.copy(busy=false,pending=false,recoveryConflict=null,recoveryBlocked=false,error=null)
                    reloadRecovery(false)
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { if(epoch==token)mutable.value=mutable.value.copy(busy=false,error=safeError(e)) }
            }
            return
        }
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
    private fun reloadRecovery(save:Boolean) {
        val scope=mutable.value.scope ?: return
        releaseJournal();epoch++;undoStack.clear();redoStack.clear()
        mutable.value=mutable.value.copy(data=null,busy=true,error=null,inputToken=epoch,selection=emptySet(),undo=false,redo=false,
            recoveryConflict=null,recoveryBlocked=false)
        load(scope,save)
    }
    private fun load(scope: AnnotationScope,retrySave:Boolean=false) { val token = epoch; viewModelScope.launch {
        try {
            if(recovery!=null) { loadRecovery(scope,token,retrySave);return@launch }
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
    private fun marks(data:AnnotationData,layer:AnnotationLayer)=if(layer==AnnotationLayer.PERSONAL)data.personal else data.draft
    private fun generation(data:AnnotationData,layer:AnnotationLayer)=if(layer==AnnotationLayer.PERSONAL)data.personalGeneration else data.sharedGeneration
    private fun allowed(data:AnnotationData,layer:AnnotationLayer)=layer==AnnotationLayer.PERSONAL || data.admin && data.concert==null
    private suspend fun loadRecovery(scope:AnnotationScope,token:Long,retrySave:Boolean) {
        val service=checkNotNull(recovery)
        val first=repository.load(scope)
        if(epoch!=token || mutable.value.scope!=scope)return
        val source=checkNotNull(first.recoverySource) { "缺少原乐谱身份，未读取恢复笔迹" }
        var layer=mutable.value.layer
        var candidate:InkRecovery.Session?=null
        try {
            candidate=service.open(InkDraftKey(scope,layer,source.space),source)
            if(epoch!=token)return
            // A cold reader normally starts on the personal layer. Also discover its own admin draft.
            if(layer==AnnotationLayer.PERSONAL && candidate.recovered==null && allowed(first,AnnotationLayer.SHARED)) {
                val shared=service.open(InkDraftKey(scope,AnnotationLayer.SHARED,source.space),source)
                if(shared.recovered!=null) { service.release(candidate);candidate=shared;layer=AnnotationLayer.SHARED }
                else service.release(shared)
            }
            if(epoch!=token)return
            mutable.value=mutable.value.copy(layer=layer,draftMode=layer==AnnotationLayer.SHARED)
            if(candidate.recovered!=null)holdCloud()
            // Claiming a journal may wait for slow disk IO; recheck authority after that wait.
            val data=repository.load(scope)
            if(epoch!=token)return
            check(data.recoverySource==source) { "恢复期间原乐谱或账号已变化，请重新打开；草稿保留" }
            val record=candidate.recovered
            val decision=record?.let { withContext(computation) {
                InkDraftReconciler.compare(it,source,marks(data,layer),generation(data,layer),allowed(data,layer))
            } }
            if(epoch!=token)return
            journal=candidate;checkpoint=null;candidate=null
            pending.clear()
            val local=mutable.value
            when(decision) {
                null -> mutable.value=local.copy(data=data,busy=false,pending=false,error=null,recoveryBlocked=false,recoveryConflict=null,
                    recoveryDurable=false,undo=undoStack.isNotEmpty(),redo=redoStack.isNotEmpty())
                is InkDraftDecision.Restore -> {
                    var before=marks(data,layer)
                    decision.writes.forEach { pending.addLast(InkDraftEdit(it.marks,it.generation,before));before=it.marks }
                    if(pending.isEmpty())service.await(service.submit(checkNotNull(journal),emptyList()))
                    if(epoch!=token)return
                    val restored=if(pending.isEmpty())data else if(layer==AnnotationLayer.PERSONAL)
                        data.copy(personal=pending.last().marks) else data.copy(draft=pending.last().marks)
                    mutable.value=local.copy(data=restored,busy=false,pending=pending.isNotEmpty(),recoveryDurable=true,
                        recoveryBlocked=false,recoveryConflict=null,undo=undoStack.isNotEmpty(),redo=redoStack.isNotEmpty(),
                        error=if(pending.isEmpty())null else "已恢复本机未保存批注，请重试保存")
                    if(retrySave && pending.isNotEmpty())drain()
                }
                else -> {
                    undoStack.clear();redoStack.clear()
                    val conflict=if(decision==InkDraftDecision.Conflict) InkRecoveryConflict(checkNotNull(record).writes.last().marks,
                        marks(data,layer),generation(data,layer),source) else null
                    mutable.value=local.copy(data=data,busy=false,pending=true,recoveryDurable=true,recoveryBlocked=true,
                        recoveryConflict=conflict,undo=false,redo=false,error=when(decision) {
                            InkDraftDecision.DifferentSource -> "原 PDF 已变化，旧谱未保存笔迹已保留，未贴到新谱；可保留离开或明确放弃"
                            InkDraftDecision.Hidden -> "共享草稿编辑权限已变化，原草稿保留且内容隐藏；可保留离开或明确放弃"
                            else -> "未保存笔迹与已保存版本不同，双方已保留，请比较后选择"
                        })
                }
            }
        } finally { candidate?.let(service::release) }
    }
    fun resolveRecovery(keepLocal:Boolean) {
        val before=mutable.value;val conflict=before.recoveryConflict ?: return
        val scope=before.scope ?: return;val service=recovery ?: return;val session=journal ?: return
        if(before.busy || before.saving || before.gestureActive)return
        val token=epoch
        mutable.value=before.copy(busy=true,error=null)
        viewModelScope.launch {
            try {
                val fresh=repository.load(scope)
                if(epoch!=token)return@launch
                check(fresh.recoverySource==conflict.source && allowed(fresh,before.layer) &&
                    generation(fresh,before.layer)==conflict.generation && marks(fresh,before.layer)==conflict.saved) {
                    "选择期间账号、原谱或已保存笔迹再次变化，请刷新后重新比较；双方保留"
                }
                if(keepLocal) {
                    pending.clear();pending.addLast(InkDraftEdit(conflict.local,conflict.generation,conflict.saved))
                    val updated=if(before.layer==AnnotationLayer.PERSONAL)fresh.copy(personal=conflict.local) else fresh.copy(draft=conflict.local)
                    mutable.value=mutable.value.copy(data=updated,busy=false,recoveryBlocked=false,recoveryConflict=null,pending=true,error=null)
                    checkpointQueue();drain()
                } else {
                    service.await(service.submit(session,emptyList()))
                    if(epoch!=token)return@launch
                    pending.clear();mutable.value=mutable.value.copy(busy=false,pending=false)
                    reloadRecovery(false)
                }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(epoch==token)mutable.value=mutable.value.copy(busy=false,data=null,recoveryConflict=null,
                recoveryBlocked=true,error=safeError(e)) }
        }
    }
    private fun safeError(failure: Exception) = if (failure is IllegalStateException || failure is IllegalArgumentException)
        failure.message ?: "批注读取或保存失败" else "批注读取或保存失败，请重试"
    fun tool(tool: AnnotationTool) {
        mutable.value = mutable.value.copy(tool = tool)
        if (tool != AnnotationTool.READ) holdCloud()
        else if (!mutable.value.pending && !mutable.value.saving && !mutable.value.gestureActive) releaseCloud()
    }
    fun inputMode(mode: InkInputMode) {
        if (mutable.value.gestureActive) return
        inputSettings?.save(mode)
        saved["ink-input-mode"] = mode.name
        mutable.value = mutable.value.copy(inputMode = mode)
    }
    fun toggleEditLock() {
        if (mutable.value.transitionBlocked) return
        mutable.value = mutable.value.copy(editLocked = !mutable.value.editLocked, tool = AnnotationTool.READ, selection = emptySet())
        mutable.value.scope?.member?.let { saved["edit-lock-$it"] = mutable.value.editLocked }
    }
    fun pressure(enabled:Boolean) {
        if(mutable.value.gestureActive)return
        inputSettings?.pressure(enabled);saved["ink-pressure"]=enabled
        mutable.value=mutable.value.copy(pressureEnabled=enabled)
    }
    fun sideButton(button:InkSideButton) {
        if(mutable.value.gestureActive)return
        inputSettings?.sideButton(button);saved["ink-side-button"]=button.name
        mutable.value=mutable.value.copy(sideButton=button)
    }
    fun gesture(scope: AnnotationScope?, active: Boolean, token: Long = mutable.value.inputToken) {
        if (scope != null && mutable.value.scope == scope && token == epoch) {
            if (active) holdCloud()
            mutable.value = mutable.value.copy(gestureActive = active, inputNotice = if (active) null else mutable.value.inputNotice)
        }
    }
    fun cancelGesture(scope: AnnotationScope?, token: Long) {
        if (scope == mutable.value.scope && token == epoch) mutable.value = mutable.value.copy(
            gestureActive = false, inputNotice = "本次未完成笔画已取消，请重新书写；已完成笔画仍保留")
    }
    fun color(color: Long) { mutable.value = mutable.value.copy(color = color) }
    fun width(width: Float) {
        if (width.isFinite() && !mutable.value.gestureActive)
            mutable.value = mutable.value.copy(width = width.coerceIn(.001f, .012f))
    }
    fun resizeSelectedStrokes() {
        val state = mutable.value
        if (!state.editable || state.transitionBlocked) return
        edit(state.marks.map { mark -> if (mark.id in state.selection && mark.kind != MarkKind.SYMBOL)
            mark.copy(width = state.width * if (mark.kind == MarkKind.HIGHLIGHT) 3 else 1) else mark })
    }
    fun symbol(symbol: String) { require(symbol in musicSymbols); mutable.value = mutable.value.copy(symbol = symbol) }
    fun symbolScale(scale: Float) {
        if (scale.isFinite() && !mutable.value.gestureActive) mutable.value = mutable.value.copy(symbolScale = scale.coerceIn(.5f, 2f))
    }
    fun hairpinLength(length: Float) {
        if (length.isFinite() && !mutable.value.gestureActive)
            mutable.value = mutable.value.copy(hairpinLength = length.coerceIn(.04f, .65f))
    }
    fun resizeSelectedSymbols() {
        val state = mutable.value
        if (!state.editable || state.transitionBlocked) return
        operations?.record(com.ensemble.scorelibrary.diagnostics.TestEvent.STAMP_RESIZE,
            count = state.marks.count { it.id in state.selection && it.kind == MarkKind.SYMBOL })
        edit(state.marks.map { if (it.id in state.selection && it.kind == MarkKind.SYMBOL)
            if (HairpinGeometry.isHairpin(it.symbol)) HairpinGeometry.resize(it, state.hairpinLength, state.symbolScale)
            else it.copy(symbolScale = state.symbolScale) else it })
    }
    fun visible(layer: AnnotationLayer, visible: Boolean) {
        mutable.value = if (layer == AnnotationLayer.PERSONAL) mutable.value.copy(showPersonal = visible) else mutable.value.copy(showShared = visible)
    }
    fun layer(layer: AnnotationLayer) {
        if (mutable.value.transitionBlocked) return
        releaseCloud()
        undoStack.clear(); redoStack.clear()
        mutable.value = mutable.value.copy(layer = layer, draftMode = layer == AnnotationLayer.SHARED,
            selection = emptySet(), undo = false, redo = false, tool = AnnotationTool.READ)
        if(recovery!=null)reloadRecovery(false)
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
    fun stroke(points: List<InkPoint>, scope: AnnotationScope? = mutable.value.scope, token: Long = mutable.value.inputToken,
        hardwareEraser: Boolean = false, pressures:List<Float>?=null) {
        if (scope != mutable.value.scope || token != epoch) return
        val state = mutable.value
        if (!state.editable || points.isEmpty() || state.tool == AnnotationTool.READ) return
        val tool = if (hardwareEraser) AnnotationTool.ERASER else state.tool
        if (tool == AnnotationTool.ERASER || tool == AnnotationTool.MOVE) {
            val token = epoch
            mutable.value = state.copy(busy = true)
            viewModelScope.launch {
                val updated = withContext(computation) {
                    if (tool == AnnotationTool.ERASER) InkGeometry.erase(state.marks, points, .015f)
                    else InkGeometry.move(state.marks, state.selection, points.last().x - points.first().x, points.last().y - points.first().y)
                }
                if (epoch == token) { mutable.value = mutable.value.copy(busy = false); edit(updated) }
            }
            return
        }
        val updated = when (tool) {
            AnnotationTool.PEN -> state.marks + InkMark(kind = MarkKind.PEN, points = points.toList(), color = state.color, width = state.width,
                pressures=pressures?.toList())
            AnnotationTool.HIGHLIGHT -> state.marks + InkMark(kind = MarkKind.HIGHLIGHT, points = points.toList(), color = state.color, width = state.width * 3)
            AnnotationTool.SYMBOL -> state.marks + InkMark(kind = MarkKind.SYMBOL,
                points = if (HairpinGeometry.isHairpin(state.symbol)) HairpinGeometry.placement(points, state.hairpinLength) else listOf(points.first()),
                color = state.color, width = state.width, symbol = state.symbol, symbolScale = state.symbolScale)
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
        pending.addLast(InkDraftEdit(updated, if (pending.isEmpty()) generation else pending.first().generation + 1,
            if(pending.isEmpty())before.marks else pending.first().marks))
        val after = if (before.layer == AnnotationLayer.PERSONAL) data.copy(personal = updated) else data.copy(draft = updated)
        mutable.value = before.copy(data = after, pending = true, selection = emptySet(), inputNotice = null,
            undo = undoStack.isNotEmpty(), redo = redoStack.isNotEmpty())
        checkpointQueue()
        if (before.error == null) drain()
    }
    private fun checkpointQueue():InkReceipt? {
        val service=recovery ?: return null
        val token=epoch
        return try {
            val receipt=service.submitEdits(checkNotNull(journal),pending.toList())
            checkpoint=receipt
            mutable.value=mutable.value.copy(recoveryDurable=false)
            viewModelScope.launch {
                try {
                    service.await(receipt)
                    if(epoch==token && checkpoint===receipt)mutable.value=mutable.value.copy(recoveryDurable=true)
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { if(epoch==token && checkpoint===receipt)mutable.value=mutable.value.copy(recoveryDurable=false,error=safeError(e)) }
            }
            receipt
        } catch(e:Exception) { mutable.value=mutable.value.copy(recoveryDurable=false,error=safeError(e));null }
    }
    private fun drain() {
        val before = mutable.value
        val scope = before.scope ?: return
        if (before.saving || pending.isEmpty() || before.recoveryBlocked) return
        val token = epoch
        val layer = before.layer
        mutable.value = before.copy(saving = true, error = null)
        writeJob = viewModelScope.launch {
            try {
                while (pending.isNotEmpty() && epoch == token) {
                    val write = pending.first()
                    if(recovery!=null) {
                        recovery.await(checkNotNull(checkpoint ?: checkpointQueue()) { "未保存笔迹尚未安全写入本机，请重试" })
                        if(epoch!=token)return@launch
                        repository.save(scope,layer,write.marks,write.generation,checkNotNull(journal).source)
                    } else repository.save(scope, layer, write.marks, write.generation)
                    if (epoch != token) return@launch
                    pending.removeFirst()
                    val data = mutable.value.data
                    val after = if (layer == AnnotationLayer.PERSONAL) data?.copy(personalGeneration = write.generation + 1)
                        else data?.copy(sharedGeneration = write.generation + 1)
                    mutable.value = mutable.value.copy(data = after, pending = pending.isNotEmpty())
                    if(recovery!=null)recovery.await(checkNotNull(checkpointQueue()) { "恢复记录清理未完成，请重试" })
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
