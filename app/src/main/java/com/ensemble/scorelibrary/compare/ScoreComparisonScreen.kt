package com.ensemble.scorelibrary.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.domain.PieceSummary
import com.ensemble.scorelibrary.ui.PreviewImageView
import com.ensemble.scorelibrary.ui.ScorePalette
import com.ensemble.scorelibrary.ui.filterPieces

val LocalScoreComparison = staticCompositionLocalOf<ScoreComparisonViewModel?> { null }

@Composable
fun ScoreComparisonDialog(member: String, pieces: List<PieceSummary>, model: ScoreComparisonViewModel, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScoreComparisonContent(member, pieces, model, close)
    }
}

@Composable
internal fun ScoreComparisonContent(member: String, pieces: List<PieceSummary>, model: ScoreComparisonViewModel, close: () -> Unit) {
    val viewOwner = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var resumeGeneration by remember { mutableLongStateOf(0) }
    val sizes = remember { mutableStateListOf(IntSize.Zero, IntSize.Zero) }
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { resumed = false; model.clear(viewOwner) }
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; resumeGeneration++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.clear(viewOwner) }
    }
    val raw by model.state.collectAsStateWithLifecycle()
    var choosingSide by remember(member) { mutableStateOf<Int?>(null) }
    var help by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().testTag("comparison-screen"), color = ScorePalette.Workspace) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 700.dp
            var focus by rememberSaveable { mutableIntStateOf(if (wide) -1 else 0) }
            LaunchedEffect(member, model, resumed, resumeGeneration, sizes[0], sizes[1], focus) {
                if (resumed) {
                    model.open(member, viewOwner)
                    for (side in 0..1) model.viewport(side, if (focus == -1 || focus == side) sizes[side].width else 0,
                        if (focus == -1 || focus == side) sizes[side].height else 0)
                } else model.clear(viewOwner)
            }
            val current = resumed && model.current(member, viewOwner)
            val state = if (current && raw.member == member) raw else ComparisonState(member)
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("乐谱版本对比", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { help = true }) { Text("对比说明") }
                    IconButton(onClick = close, modifier = Modifier.testTag("comparison-close")) { Icon(Icons.Outlined.Close, "关闭对比") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-1 to "双谱对照", 0 to "放大 A", 1 to "放大 B").forEach { (choice, label) ->
                        FilterChip(focus == choice, { focus = choice }, label = { Text(label) }, modifier = Modifier.testTag("compare-focus-$choice"))
                    }
                }
                if (!current) Text("账号或离线核验已变化，请关闭后重新打开对比。", Modifier.testTag("comparison-unavailable"))
                val paneContent: @Composable (Int, Modifier) -> Unit = { side, modifier ->
                    val pane = state.panes[side]
                    val visible = pane.target == null || pieces.any { it.id == pane.target.piece }
                    ComparisonPaneView(side, if (visible) pane else ComparisonPane(error = "原曲目暂不可用，请重新选择"),
                        current, { choosingSide = side }, { model.choosePart(side, it) }, { model.reselectPart(side) },
                        { model.move(side, it) }, { model.retry(side) }, { sizes[side] = it }, modifier)
                }
                if (focus >= 0) paneContent(focus, Modifier.weight(1f).fillMaxWidth())
                else if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paneContent(0, Modifier.weight(1f).fillMaxHeight()); paneContent(1, Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    paneContent(0, Modifier.weight(1f).fillMaxWidth()); paneContent(1, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
    choosingSide?.let { side -> ComparisonPiecePicker(pieces, side, { choosingSide = null }) {
        if (model.current(member, viewOwner)) model.choosePiece(side, it)
        choosingSide = null
    } }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("原谱版本对照") },
        text = { Text("分别选择 A、B 曲目及获授权的声部，按编号、分类与乐器核对。两侧独立翻页，可放大 A 或 B。\n\n这里只读原谱，不叠加批注，也不改变活动版本、编号、最近阅读或演出状态。不同版本需要你明确选择；不会自动判断版本关系或标出差异。\n\n退出、切换账号或进入后台会结束本次对比；重新进入需要再次选择。") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
}

@Composable
private fun ComparisonPaneView(side: Int, pane: ComparisonPane, enabled: Boolean, choosePiece: () -> Unit,
    choosePart: (String) -> Unit, reselect: () -> Unit, move: (Int) -> Unit, retry: () -> Unit,
    size: (IntSize) -> Unit, modifier: Modifier) {
    val label = if (side == 0) "A" else "B"
    Surface(modifier, color = ScorePalette.Preview, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxSize().padding(8.dp).testTag("compare-pane-$side")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = choosePiece, enabled = enabled, modifier = Modifier.testTag("compare-select-$side")) { Text("选择曲目") }
                TextButton(onClick = reselect, enabled = enabled && pane.target?.part != null,
                    modifier = Modifier.testTag("compare-reselect-$side")) { Text("重选声部") }
            }
            Text(listOfNotNull(pane.summary?.organizerSequence, pane.score?.title ?: pane.summary?.title).joinToString(" · ")
                .ifBlank { "选择 $label 的曲目与声部" }, Modifier.testTag("compare-title-$side"),
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(pane.summary?.categoryName, pane.score?.part?.name).joinToString(" · "),
                Modifier.testTag("compare-part-$side"), minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth().background(ScorePalette.Preview).onSizeChanged(size), contentAlignment = Alignment.Center) {
                val image = pane.image?.takeIf { enabled && it.canDisplay() }
                when {
                    image != null -> AndroidView(factory = { PreviewImageView(it) }, modifier = Modifier.fillMaxSize().testTag("compare-page-$side"),
                        onReset = { it.clearPreview() }, onRelease = { it.clearPreview() }, update = {
                            it.contentDescription = "$label · ${pane.score?.title} · ${pane.score?.part?.name} · 原谱第 ${pane.sourcePage} 页"
                            it.show(image)
                        })
                    pane.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(pane.error, Modifier.testTag("compare-error-$side"))
                        TextButton(onClick = retry, enabled = enabled && pane.target != null, modifier = Modifier.testTag("compare-retry-$side")) { Text("重新核验") }
                    }
                    pane.loading -> CircularProgressIndicator(Modifier.testTag("compare-loading-$side"))
                    pane.parts.isNotEmpty() && pane.target?.part == null -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(pane.parts, key = { it.id }) { part -> TextButton(onClick = { choosePart(part.id) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("compare-part-$side-${part.id}")) { Text(part.name) } }
                    }
                    pane.target != null && pane.target.part == null -> Text("当前账号没有可读声部")
                    else -> Text("请选择曲目与声部", Modifier.testTag("compare-empty-$side"))
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { move(-1) }, enabled = enabled && pane.score != null && pane.index > 0,
                    modifier = Modifier.testTag("compare-previous-$side")) { Text("上一页") }
                Text(if (pane.total == 0) "—" else "${pane.index + 1}/${pane.total}", Modifier.weight(1f).testTag("compare-position-$side"),
                    style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { move(1) }, enabled = enabled && pane.score != null && pane.index + 1 < pane.total,
                    modifier = Modifier.testTag("compare-next-$side")) { Text("下一页") }
            }
        }
    }
}

@Composable
private fun ComparisonPiecePicker(pieces: List<PieceSummary>, side: Int, close: () -> Unit, choose: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(pieces, query) { filterPieces(pieces, query, false) }
    AlertDialog(onDismissRequest = close, title = { Text("选择 ${if (side == 0) "A" else "B"} 的曲目") },
        text = { Column {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("曲名、编号、分类或乐器") },
                modifier = Modifier.fillMaxWidth().testTag("compare-search"))
            Text("${matches.size} 首", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                items(matches, key = { it.id }) { piece -> TextButton(onClick = { choose(piece.id) },
                    modifier = Modifier.fillMaxWidth().testTag("compare-pick-$side-${piece.id}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(listOfNotNull(piece.organizerSequence, piece.title).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text((listOfNotNull(piece.categoryName) + piece.partNames).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text("取消") } })
}
