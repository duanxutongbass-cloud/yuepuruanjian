package com.ensemble.scorelibrary.annotation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.pdf.PagePoint
import com.ensemble.scorelibrary.pdf.PdfPageTransform

val LocalAnnotations = staticCompositionLocalOf<AnnotationViewModel?> { null }
fun musicSymbolLabel(symbol: String) = when (symbol) {
    "<" -> "渐强"; ">" -> "渐弱"; "fermata" -> "延长"; "accent" -> "重音"
    "staccato" -> "断奏"; "tenuto" -> "保持"; else -> symbol
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
fun AnnotationToolbar(state: AnnotationState, model: AnnotationViewModel) {
    Column(Modifier.fillMaxWidth()) {
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
            val tools = listOf(Triple(AnnotationTool.READ, "阅读", Icons.Outlined.PanTool),
                Triple(AnnotationTool.PEN, "手写", Icons.Outlined.Edit), Triple(AnnotationTool.HIGHLIGHT, "荧光", Icons.Outlined.Highlight),
                Triple(AnnotationTool.ERASER, "橡皮", Icons.Outlined.AutoFixNormal), Triple(AnnotationTool.LASSO, "套索", Icons.Outlined.Gesture),
                Triple(AnnotationTool.MOVE, "移动", Icons.Outlined.OpenWith), Triple(AnnotationTool.SYMBOL, "音乐符号", Icons.Outlined.MusicNote))
            tools.forEach { (tool, name, icon) -> ToolButton(name, icon, tool == AnnotationTool.READ || state.editable,
                selected = state.tool == tool) { model.tool(tool) } }
            ToolButton("撤销", Icons.AutoMirrored.Outlined.Undo, state.editable && state.undo, action = model::undo)
            ToolButton("重做", Icons.AutoMirrored.Outlined.Redo, state.editable && state.redo, action = model::redo)
            ToolButton("删除选中", Icons.Outlined.Delete, state.editable && state.selection.isNotEmpty(), action = model::deleteSelection)
            listOf(0xffd32f2fL, 0xff1565c0L, 0xff222222L, 0xfff9a825L, 0xff2e7d32L).forEachIndexed { index, color ->
                Box(Modifier.size(40.dp).padding(7.dp).background(Color(color), CircleShape)
                    .border(if (state.color == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .semantics { contentDescription = listOf("红色", "蓝色", "黑色", "黄色", "绿色")[index] }
                    .clickable(enabled = state.editable) { model.color(color) })
            }
            Slider(state.width, model::width, valueRange = .001f.. .012f, enabled = state.editable, modifier = Modifier.width(140.dp)
                .semantics { contentDescription = "笔宽" })
        }
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { model.tool(AnnotationTool.SYMBOL); menu = true }, enabled = state.editable, modifier = Modifier.testTag("music-symbol-picker")) {
                Icon(Icons.Outlined.MusicNote, null); Text(musicSymbolLabel(state.symbol))
            }
            if (state.selection.isNotEmpty()) {
                Text("已选 ${state.selection.size} 项", Modifier.padding(horizontal = 12.dp))
                ToolButton("移动选中", Icons.Outlined.OpenWith, state.editable, state.tool == AnnotationTool.MOVE) { model.tool(AnnotationTool.MOVE) }
                ToolButton("删除选中内容", Icons.Outlined.Delete, state.editable, action = model::deleteSelection)
            }
        }
        if (menu && state.editable) AlertDialog(onDismissRequest = { menu = false }, title = { Text("音乐符号") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    musicSymbolGroups.forEach { (group, symbols) ->
                        Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                        symbols.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth()) { row.forEach { symbol ->
                            TextButton(onClick = { model.symbol(symbol); menu = false }, modifier = Modifier.weight(1f).testTag("symbol-$symbol")) { Text(musicSymbolLabel(symbol)) }
                        } } }
                    }
                }
            }, confirmButton = { TextButton(onClick = { menu = false }) { Text("关闭") } })
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (state.busy || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.pending) Text(if (state.saving) "正在保存批注，可继续书写；保存完成前请勿切页或入场"
            else "批注尚未保存，仅在当前进程内保留；请重试，关闭应用可能丢失", Modifier.padding(horizontal = 12.dp).testTag("annotation-pending"))
        state.inputNotice?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("annotation-input-notice")) }
        state.error?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("annotation-error"), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun AnnotationOverlay(transform: PdfPageTransform, state: AnnotationState, model: AnnotationViewModel) {
    var trail by remember(state.scope, state.inputToken, state.tool, transform) { mutableStateOf<List<InkPoint>>(emptyList()) }
    val input = if (state.tool == AnnotationTool.READ || !state.editable) Modifier else Modifier.pointerInput(state.scope, state.inputToken, state.tool, state.editable, transform) {
        if (state.tool != AnnotationTool.READ && state.editable) awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val first = transform.screenToNormalized(PagePoint(down.position.x, down.position.y))
            if (first != null) {
                model.gesture(state.scope, true, state.inputToken)
                var completed = false
                try {
                down.consume(); trail = listOf(InkPoint(first.x, first.y))
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    // Compose cancellation delivers consumed synthetic releases; these are not completed strokes.
                    if (change.isConsumed) return@awaitEachGesture
                    change.consume()
                    transform.screenToNormalized(PagePoint(change.position.x, change.position.y))?.let { point ->
                        if (trail.size < 4096) trail = trail + InkPoint(point.x, point.y)
                        else return@awaitEachGesture
                    }
                    val pressed = change.pressed
                } while (pressed)
                model.stroke(trail, state.scope, state.inputToken); completed = true; trail = emptyList()
                } finally {
                    if (!completed) model.cancelGesture(state.scope, state.inputToken)
                    model.gesture(state.scope, false, state.inputToken); trail = emptyList()
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize().testTag("annotation-canvas").then(input)) {
        fun screen(point: InkPoint): Offset { val p = transform.normalizedToScreen(point.x, point.y); return Offset(p.x, p.y) }
        fun drawMark(mark: InkMark, selected: Boolean = false) {
            val color = Color(mark.color).let { if (mark.kind == MarkKind.HIGHLIGHT) it.copy(alpha = .28f) else it }
            val width = mark.width * transform.page.width * transform.scale
            if (mark.kind == MarkKind.SYMBOL) {
                val p = screen(mark.points.first())
                val size = (.032f * transform.page.width * transform.scale).coerceAtLeast(12f)
                val stroke = (size / 12).coerceAtLeast(1f)
                when (mark.symbol) {
                    "<", ">", "accent" -> {
                        val left = mark.symbol == "<"
                        val span = if (mark.symbol == "accent") size else size * 2
                        val point = if (left) p else p + Offset(span, -size / 4)
                        val endX = if (left) p.x + span else p.x
                        drawLine(color, point, Offset(endX, p.y - size / 2), stroke)
                        drawLine(color, point, Offset(endX, p.y), stroke)
                    }
                    "staccato" -> drawCircle(color, size / 10, p)
                    "tenuto" -> drawLine(color, p, p + Offset(size, 0f), stroke)
                    "fermata" -> {
                        drawArc(color, 180f, 180f, false, p + Offset(0f, -size / 2), androidx.compose.ui.geometry.Size(size, size), style = Stroke(stroke))
                        drawCircle(color, size / 12, p + Offset(size / 2, -size / 8))
                    }
                    else -> drawContext.canvas.nativeCanvas.drawText(mark.symbol, p.x, p.y,
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color.toArgb(); textSize = (.032f * transform.page.width * transform.scale).coerceAtLeast(12f)
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
                    })
                }
            } else if (mark.points.size == 1) drawCircle(color, width / 2, screen(mark.points.first()))
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
            if (trail.isNotEmpty() && state.tool == AnnotationTool.ERASER) {
                drawCircle(Color.Gray, .015f * transform.page.width * transform.scale, screen(trail.last()), style = Stroke(2f))
            } else if (trail.isNotEmpty() && state.tool != AnnotationTool.MOVE) {
                val kind = if (state.tool == AnnotationTool.HIGHLIGHT) MarkKind.HIGHLIGHT else MarkKind.PEN
                drawMark(InkMark(kind = kind, points = trail, color = state.color,
                    width = if (kind == MarkKind.HIGHLIGHT) state.width * 3 else state.width))
            }
        }
    }
}
