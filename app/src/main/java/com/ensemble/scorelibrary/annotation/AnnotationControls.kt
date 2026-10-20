package com.ensemble.scorelibrary.annotation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.pdf.PdfPageTransform
import com.ensemble.scorelibrary.diagnostics.LocalTestOperations
import com.ensemble.scorelibrary.diagnostics.TestEvent

val LocalAnnotations = staticCompositionLocalOf<AnnotationViewModel?> { null }
fun musicSymbolLabel(symbol: String) = when (symbol) {
    "<" -> "渐强"; ">" -> "渐弱"; "fermata" -> "延长"; "accent" -> "重音"
    "staccato" -> "断奏"; "tenuto" -> "保持"; "downbow" -> "下弓"; "upbow" -> "上弓"; else -> symbol
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(name: String, icon: ImageVector, enabled: Boolean = true, selected: Boolean = false,
    action: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(name) } }, state = rememberTooltipState()) {
        IconButton(onClick = action, enabled = enabled, modifier = Modifier.testTag("annotation-$name"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)) {
            Icon(icon, name)
        }
    }
}

@Composable
fun AnnotationToolbar(state: AnnotationState, model: AnnotationViewModel, onSymbols: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var penSettings by remember { mutableStateOf(false) }
    var stylusSettings by remember { mutableStateOf(false) }
    var stampSettings by remember { mutableStateOf(false) }
    val operations = LocalTestOperations.current
    fun openSymbols() {
        operations?.record(TestEvent.STAMP_OPEN)
        model.tool(AnnotationTool.SYMBOL); if (onSymbols != null) onSymbols() else menu = true
    }
    Column(Modifier.fillMaxWidth()) {
        val conflict = if (state.layer == AnnotationLayer.PERSONAL) state.data?.personalConflict else state.data?.sharedConflict
        if (conflict != null) com.ensemble.scorelibrary.sync.InkConflictPanel(conflict, !state.transitionBlocked) { model.resolveCloud(conflict, it) }
        state.recoveryConflict?.let { InkRecoveryPanel(it,!state.busy && !state.saving && !state.gestureActive,model::resolveRecovery) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(state.layer == AnnotationLayer.PERSONAL, { model.layer(AnnotationLayer.PERSONAL) },
                    SegmentedButtonDefaults.itemShape(0, 2), enabled = !state.transitionBlocked, modifier = Modifier.widthIn(min = 128.dp)) { Text("我的批注", maxLines = 1) }
                SegmentedButton(state.layer == AnnotationLayer.SHARED, { model.layer(AnnotationLayer.SHARED) },
                    SegmentedButtonDefaults.itemShape(1, 2), enabled = !state.transitionBlocked, modifier = Modifier.widthIn(min = 128.dp)) { Text(if (state.data?.admin == true && state.data.concert == null) "共享草稿" else "共享批注", maxLines = 1) }
            }
            Checkbox(state.showPersonal, { model.visible(AnnotationLayer.PERSONAL, it) }); Text("我的")
            Checkbox(state.showShared, { model.visible(AnnotationLayer.SHARED, it) }); Text("共享")
            ToolButton(if (state.editLocked) "解除编辑锁定" else "锁定编辑", if (state.editLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                !state.transitionBlocked, selected = state.editLocked, action = model::toggleEditLock)
            if (state.data?.locked == true) { Icon(Icons.Outlined.Inventory2, "本场共享版本已冻结"); Text("本场共享版本已冻结") }
            else if (state.data?.concert != null) Text("演出批注未锁定")
            if (state.data?.admin == true && state.data.concert == null && state.layer == AnnotationLayer.SHARED) {
                ToolButton("发布当前页", Icons.Outlined.Publish, !state.transitionBlocked && state.error == null, action = model::publish)
            }
            if (state.data?.admin == true && state.data.concert != null && !state.data.locked) {
                var confirm by remember { mutableStateOf(false) }
                ToolButton("冻结本场共享版本", Icons.Outlined.Inventory2, !state.transitionBlocked && state.error == null) { confirm = true }
                if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("冻结本场共享版本？") },
                    text = { Text("本场使用当前已发布共享批注，之后不再随草稿更新，节目项与引用曲谱结构也将受到保护。仍可阅读和编辑自己的批注；后续修改请编辑共享草稿并新建场次。此操作不覆盖或解冻历史版本。") },
                    confirmButton = { TextButton(onClick = { confirm = false; model.freeze() }) { Text("冻结本场") } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
            }
            ToolButton(if (state.pending) "重试保存" else "刷新批注", Icons.Outlined.Refresh,
                !state.busy && !state.saving && !state.gestureActive, action = model::retry)
            if (state.pending) {
                var discard by remember { mutableStateOf(false) }
                ToolButton("放弃未保存批注", Icons.Outlined.Delete, !state.saving && !state.busy && !state.gestureActive) { discard = true }
                if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存批注？") },
                    text = { Text("将丢弃本页待保存的修改，并重新读取已保存版本。此操作无法撤销。") },
                    confirmButton = { TextButton(onClick = { discard = false; model.discardPending() }) { Text("放弃并重新读取") } },
                    dismissButton = { TextButton(onClick = { discard = false }) { Text("继续保留") } })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            InkInputModePicker(state.inputMode, !state.gestureActive, model::inputMode)
            val tools = listOf(Triple(AnnotationTool.READ, "阅读", Icons.Outlined.PanTool),
                Triple(AnnotationTool.PEN, "手写", Icons.Outlined.Edit), Triple(AnnotationTool.HIGHLIGHT, "荧光", Icons.Outlined.Highlight),
                Triple(AnnotationTool.ERASER, "橡皮", Icons.Outlined.AutoFixNormal), Triple(AnnotationTool.LASSO, "套索", Icons.Outlined.Gesture),
                Triple(AnnotationTool.MOVE, "移动", Icons.Outlined.OpenWith), Triple(AnnotationTool.SYMBOL, "音乐符号", Icons.Outlined.MusicNote))
            tools.forEach { (tool, name, icon) -> ToolButton(name, icon, tool == AnnotationTool.READ || state.editable,
                selected = state.tool == tool) { if (tool == AnnotationTool.SYMBOL) openSymbols() else model.tool(tool) } }
            ToolButton("撤销", Icons.AutoMirrored.Outlined.Undo, state.editable && state.undo, action = model::undo)
            ToolButton("重做", Icons.AutoMirrored.Outlined.Redo, state.editable && state.redo, action = model::redo)
            ToolButton("删除选中", Icons.Outlined.Delete, state.editable && state.selection.isNotEmpty(), action = model::deleteSelection)
            listOf(0xffd32f2fL, 0xff1565c0L, 0xff222222L, 0xfff9a825L, 0xff2e7d32L).forEachIndexed { index, color ->
                Box(Modifier.size(40.dp).padding(7.dp).background(Color(color), CircleShape)
                    .border(if (state.color == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .semantics { contentDescription = listOf("红色", "蓝色", "黑色", "黄色", "绿色")[index] }
                    .clickable(enabled = state.editable) { model.color(color) })
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { penSettings = true }, enabled = state.editable && !state.gestureActive,
                modifier = Modifier.testTag("pen-width-settings")) { Text("笔画粗细") }
            OutlinedButton(onClick={stylusSettings=true},enabled=!state.gestureActive,modifier=Modifier.testTag("stylus-settings")) { Text("手写笔设置") }
            if(state.pressureEnabled)Text("压感已开启",Modifier.padding(horizontal=8.dp),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = ::openSymbols, enabled = state.editable, modifier = Modifier.testTag("music-symbol-picker")) {
                Icon(Icons.Outlined.MusicNote, null); Text(musicSymbolLabel(state.symbol))
            }
            TextButton(onClick = { stampSettings = true }, enabled = state.editable && !state.gestureActive,
                modifier = Modifier.testTag("stamp-settings")) {
                Text(if (HairpinGeometry.isHairpin(state.symbol)) "长度与开口" else "印章大小 ${(state.symbolScale * 100).toInt()}%")
            }
            if (state.selection.isNotEmpty()) {
                Text("已选 ${state.selection.size} 项", Modifier.padding(horizontal = 12.dp))
                ToolButton("移动选中", Icons.Outlined.OpenWith, state.editable, state.tool == AnnotationTool.MOVE) { model.tool(AnnotationTool.MOVE) }
                ToolButton("删除选中内容", Icons.Outlined.Delete, state.editable, action = model::deleteSelection)
                if (state.marks.any { it.id in state.selection && it.kind == MarkKind.SYMBOL }) {
                    TextButton(onClick = model::resizeSelectedSymbols, enabled = state.editable && !state.transitionBlocked,
                        modifier = Modifier.testTag("resize-selected-stamps")) { Text("应用到选中印章") }
                }
            }
        }
        if (menu) MusicSymbolPalette(state, { model.symbol(it); menu = false }, { menu = false })
        if (penSettings) PenWidthSettings(state, model) { penSettings = false }
        if(stylusSettings) StylusSettings(state,model) { stylusSettings=false }
        if (stampSettings) StampSettings(state, model) {
            operations?.record(TestEvent.STAMP_SIZE, count = (model.state.value.symbolScale * 100).toInt())
            stampSettings = false
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.pending && state.error != null) Text(if(state.recoveryDurable)
            "批注尚未保存，恢复副本已加密保留在本机；请重试或处理冲突" else
            "批注尚未安全保存，仅在当前进程内保留；请重试，关闭应用可能丢失", Modifier.padding(horizontal = 12.dp).testTag("annotation-pending"))
        state.inputNotice?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("annotation-input-notice")) }
        state.error?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("annotation-error"), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun InkInputModePicker(mode: InkInputMode, enabled: Boolean, choose: (InkInputMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("ink-input-mode")) {
            Text(mode.label)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("关闭菜单") }, onClick = { expanded = false })
            InkInputMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, enabled = enabled,
                    leadingIcon = { if (mode == option) Icon(Icons.Outlined.Check, null) },
                    modifier = Modifier.testTag("ink-input-${option.name}"),
                    onClick = { choose(option); expanded = false })
            }
            Text("手写笔优先时手指不在谱面书写。\n翻页与缩放请先选“阅读”。\n不识别为笔的设备请选择“手指书写”。",
                Modifier.widthIn(max = 280.dp).padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Only opens on an explicit comparison action; normal saving adds no viewport-sized UI. */
@Composable
internal fun InkRecoveryPanel(conflict:InkRecoveryConflict,enabled:Boolean,resolve:(Boolean)->Unit) {
    var preview by remember(conflict) { mutableStateOf(false) }
    var choice by remember(conflict) { mutableStateOf<Boolean?>(null) }
    TextButton(onClick={preview=true},enabled=enabled,modifier=Modifier.testTag("ink-recovery-compare")) { Text("比较未保存笔迹") }
    if(preview)AlertDialog(onDismissRequest={preview=false},title={Text("比较两份笔迹")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("以下仅预览笔迹位置，不含谱面。选择前会再次核验原谱及已保存代次。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text("恢复副本 · ${conflict.local.size} 笔");com.ensemble.scorelibrary.sync.InkPreview(conflict.local) }
                Column(Modifier.weight(1f)) { Text("已保存 · ${conflict.saved.size} 笔");com.ensemble.scorelibrary.sync.InkPreview(conflict.saved) }
            }
        }},
        confirmButton={TextButton(onClick={choice=true},enabled=enabled,modifier=Modifier.testTag("ink-recovery-local")) { Text("使用恢复副本") }},
        dismissButton={Row {
            TextButton(onClick={choice=false},enabled=enabled,modifier=Modifier.testTag("ink-recovery-saved")) { Text("使用已保存版本") }
            TextButton(onClick={preview=false}) { Text("稍后处理") }
        }})
    choice?.let { local -> AlertDialog(onDismissRequest={choice=null},title={Text("确认笔迹选择")},
        text={Text(if(local) "将以刚才比较的已保存代次为基础，保存这份恢复笔迹；再次变化时会停止并保留。共享草稿仍需另外发布。"
            else "将放弃本页未保存的恢复副本，使用刚才比较的已保存笔迹；此操作无法撤销。")},
        confirmButton={TextButton(onClick={choice=null;preview=false;resolve(local)},enabled=enabled,
            modifier=Modifier.testTag("ink-recovery-confirm")) { Text("确认选择") }},
        dismissButton={TextButton(onClick={choice=null}) { Text("继续比较") }}) }
}

/** An in-window panel; the reader overlays it so opening it never resizes the PDF viewport. */
@Composable
fun MusicSymbolPalette(state: AnnotationState, choose: (String) -> Unit, close: () -> Unit, modifier: Modifier = Modifier) {
    var group by remember { mutableStateOf(musicSymbolGroups.entries.first { state.symbol in it.value }.key) }
    val operations = LocalTestOperations.current
    Surface(modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("music-symbol-palette"),
        shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("常用音乐符号", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { operations?.record(TestEvent.STAMP_CLOSE); close() }) { Text("收起符号") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                musicSymbolGroups.keys.forEach { name ->
                    TextButton(onClick = { group = name }, modifier = Modifier.testTag("symbol-group-$name")) {
                        Text(name, color = if (name == group) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            key(group) {
                Column(Modifier.fillMaxWidth().height(144.dp).verticalScroll(rememberScrollState())) {
                    musicSymbolGroups.getValue(group).chunked(4).forEach { row -> Row(Modifier.fillMaxWidth()) {
                        row.forEach { symbol ->
                            TextButton(onClick = { operations?.record(TestEvent.STAMP_CHOOSE); choose(symbol) }, enabled = state.editable,
                                modifier = Modifier.weight(1f).testTag("symbol-$symbol")) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val ink = MaterialTheme.colorScheme.onSurface
                                    Canvas(Modifier.size(64.dp, 24.dp)) { drawMusicStamp(symbol, Offset(4.dp.toPx(), 20.dp.toPx()),
                                        (if(HairpinGeometry.isHairpin(symbol)) 14.dp else 18.dp).toPx(), ink) }
                                    Text(musicSymbolLabel(symbol), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    } }
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AnnotationOverlay(transform: PdfPageTransform, state: AnnotationState, model: AnnotationViewModel) {
    val trail = remember(state.scope, state.inputToken, state.tool, state.inputMode, transform) { mutableStateListOf<InkPoint>() }
    val pressureTrail=remember(state.scope,state.inputToken,state.tool,state.inputMode,transform,state.pressureEnabled) { mutableStateListOf<Float>() }
    val pointer = remember(state.scope, state.inputToken, state.tool, state.inputMode, state.editable, transform,state.pressureEnabled,state.sideButton) {
        InkPointerInput(state.inputMode, transform, trail,
            started = { model.gesture(state.scope, true, state.inputToken) },
            finished = { points, erase -> model.stroke(points, state.scope, state.inputToken, hardwareEraser = erase,
                pressures=pressureTrail.toList().takeIf { !erase && it.size==points.size && state.pressureEnabled && state.tool==AnnotationTool.PEN }) },
            canceled = { model.cancelGesture(state.scope, state.inputToken) },
            ended = { model.gesture(state.scope, false, state.inputToken) },
            pressures=pressureTrail.takeIf { state.pressureEnabled && state.tool==AnnotationTool.PEN },sideButton=state.sideButton)
    }
    DisposableEffect(pointer) { onDispose { pointer.cancel() } }
    val input = if (state.tool == AnnotationTool.READ || !state.editable) Modifier
        else Modifier.pointerInteropFilter(onTouchEvent = pointer::event)
    Canvas(Modifier.fillMaxSize().testTag("annotation-canvas").then(input)) {
        fun screen(point: InkPoint): Offset { val p = transform.normalizedToScreen(point.x, point.y); return Offset(p.x, p.y) }
        fun drawMark(mark: InkMark, selected: Boolean = false) {
            val color = Color(mark.color).let { if (mark.kind == MarkKind.HIGHLIGHT) it.copy(alpha = .28f) else it }
            val width = mark.width * transform.page.width * transform.scale
            if (mark.kind == MarkKind.SYMBOL) {
                val p = screen(mark.points.first())
                val size = (.032f * transform.page.width * transform.scale).coerceAtLeast(12f) * mark.symbolScale
                if (HairpinGeometry.isHairpin(mark.symbol) && mark.points.size >= 2)
                    drawHairpin(mark.symbol, p, screen(mark.points.last()),
                        .012f * transform.page.width * transform.scale * mark.symbolScale, color,
                        (mark.width * transform.page.width * transform.scale * .45f).coerceAtLeast(1f))
                else drawMusicStamp(mark.symbol, p, size, color)
            } else if(mark.pressures!=null)drawPressureInk(mark,transform.page.width*transform.scale,color,::screen)
            else if (mark.points.size == 1) drawCircle(color, width / 2, screen(mark.points.first()))
            else {
                val path = Path().apply { val start = screen(mark.points.first()); moveTo(start.x, start.y)
                    mark.points.drop(1).forEach { val p = screen(it); lineTo(p.x, p.y) } }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (selected) {
                val points = mark.points.map(::screen)
                val topLeft = Offset(points.minOf { it.x } - 8, points.minOf { it.y } - 8)
                drawRect(Color(0xff1565c0), topLeft,
                    androidx.compose.ui.geometry.Size(points.maxOf { it.x } - topLeft.x + 8, points.maxOf { it.y } - topLeft.y + 8),
                    style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
            }
        }
        val x = transform.offsetX; val y = transform.offsetY
        clipRect(x.coerceAtLeast(0f), y.coerceAtLeast(0f), (x + transform.page.width * transform.scale).coerceAtMost(size.width),
            (y + transform.page.height * transform.scale).coerceAtMost(size.height)) {
            if (state.showShared) (if (state.draftMode && state.data?.admin == true && state.data.concert == null) state.data.draft else state.data?.shared.orEmpty())
                .forEach { drawMark(it, state.layer == AnnotationLayer.SHARED && it.id in state.selection) }
            if (state.showPersonal) state.data?.personal?.forEach { drawMark(it, state.layer == AnnotationLayer.PERSONAL && it.id in state.selection) }
            if (trail.isNotEmpty() && (state.tool == AnnotationTool.ERASER || pointer.erasing)) {
                drawCircle(Color.Gray, .015f * transform.page.width * transform.scale, screen(trail.last()), style = Stroke(2f))
            } else if (trail.isNotEmpty() && state.tool != AnnotationTool.MOVE) {
                val kind = when(state.tool) { AnnotationTool.HIGHLIGHT -> MarkKind.HIGHLIGHT; AnnotationTool.SYMBOL -> MarkKind.SYMBOL; else -> MarkKind.PEN }
                drawMark(InkMark(kind = kind, points = if(kind == MarkKind.SYMBOL)
                    if(HairpinGeometry.isHairpin(state.symbol)) HairpinGeometry.placement(trail,state.hairpinLength) else listOf(trail.first())
                    else trail.toList(), color = state.color, symbol = state.symbol, symbolScale = state.symbolScale,
                    width = if (kind == MarkKind.HIGHLIGHT) state.width * 3 else state.width,
                    pressures=pressureTrail.toList().takeIf { kind==MarkKind.PEN && state.pressureEnabled && it.size==trail.size }))
            }
        }
    }
}
