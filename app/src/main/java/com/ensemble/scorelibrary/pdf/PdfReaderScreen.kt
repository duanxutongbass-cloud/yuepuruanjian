package com.ensemble.scorelibrary.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import com.ensemble.scorelibrary.domain.ResolvedConcert
import com.ensemble.scorelibrary.core.model.PedalAction

val LocalPdfReader = staticCompositionLocalOf<PdfReaderViewModel?> { null }
val LocalReaderBack = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun PdfReaderScreen(member: String, score: ResolvedConcertItem, model: PdfReaderViewModel, offline: Boolean = false,
    canManage: Boolean? = null, concert: ResolvedConcert? = null) {
    LaunchedEffect(member, score, concert) { model.bind(member, score.itemId.ifEmpty { "library" }, score, concert) }
    DisposableEffect(model) { onDispose { model.detach() } }
    val rawState by model.state.collectAsStateWithLifecycle()
    val binding by model.binding.collectAsStateWithLifecycle()
    val anchor by model.anchor.collectAsStateWithLifecycle()
    val playMode by model.playMode.collectAsStateWithLifecycle()
    val performance by model.performance.collectAsStateWithLifecycle()
    val preparing = performance is PerformanceState.Checking
    val fullscreen by model.fullscreen.collectAsStateWithLifecycle()
    BackHandler(fullscreen) { model.setFullscreen(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, playMode, fullscreen) {
        val previous = view.keepScreenOn
        view.keepScreenOn = playMode || fullscreen
        onDispose { view.keepScreenOn = previous }
    }
    val notice by model.notice.collectAsStateWithLifecycle()
    val activeScore = if (anchor == member to score) binding?.takeIf { it.first == member }?.second ?: score else score
    val state = if (anchor == member to score) rawState else PdfState.Loading
    val annotations = com.ensemble.scorelibrary.annotation.LocalAnnotations.current
    val teamMode = com.ensemble.scorelibrary.sync.LocalTeamLibraryMode.current
    val teamAccount = com.ensemble.scorelibrary.account.LocalTeamAccount.current
    val teamIdentity = teamAccount?.state?.collectAsStateWithLifecycle()?.value
    // NAS administrators still have a MEMBER projection for per-PDF authorization. That
    // mother-library capability is independent of their authenticated shared-ink role.
    val inkAdmin = if (teamMode) teamIdentity?.principal?.role == "admin" else canManage
    val back = LocalReaderBack.current
    val pedal = com.ensemble.scorelibrary.core.pedal.LocalPedal.current
    val pedalState = pedal?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(pedalState?.message) {
        pedalState?.message?.takeIf { it.startsWith("输入设备已断开") || it.startsWith("批注操作") ||
            it.startsWith("脚踏配置不可用") || it == "按键未映射" }?.let(model::feedback)
    }
    val pageScope = (state as? PdfState.Ready)?.let { page ->
        com.ensemble.scorelibrary.annotation.AnnotationScope(member, activeScore.pieceId, activeScore.part.id,
            page.reference.segment.versionId, page.reference.pageIndex, activeScore.itemId.takeIf { it.isNotEmpty() })
    }
    LaunchedEffect(annotations, pageScope, playMode) {
        if ((!playMode || concert == null) && annotations != null && pageScope != null) {
            val old = annotations.state.value.scope
            // A changed authority/projection is not an ordinary page turn: quarantine its draft.
            if (old != null && (old.member != pageScope.member || old.piece != pageScope.piece ||
                old.part != pageScope.part || old.item != pageScope.item ||
                activeScore.segments.none { it.versionId == old.version })) annotations.suspendSession()
            annotations.bind(pageScope)
        }
    }
    LaunchedEffect(playMode, annotations, pageScope) {
        if (playMode && annotations != null) {
            if (concert != null) annotations.suspendSession()
            else if (pageScope != null) {
                annotations.bind(pageScope)
                annotations.tool(com.ensemble.scorelibrary.annotation.AnnotationTool.READ)
                annotations.refreshPerformance()
            }
        }
    }
    val annotationRaw = annotations?.state?.collectAsStateWithLifecycle()?.value
    val frozenInk = pageScope?.takeIf { playMode && concert != null }?.let(model::performanceInk)
    val scopedAnnotation = if (playMode && concert != null) frozenInk?.let { data ->
        com.ensemble.scorelibrary.annotation.AnnotationState(scope = pageScope, data = data, editLocked = true)
    } else annotationRaw?.takeIf { value -> value.scope?.let { scope ->
        scope.member == member && scope.piece == activeScore.pieceId && scope.part == activeScore.part.id &&
            scope.item == activeScore.itemId.takeIf { it.isNotEmpty() } && activeScore.segments.any { it.versionId == scope.version }
    } == true }?.let { current ->
        if (inkAdmin == false && current.data?.admin == true) current.copy(
            data = current.data.copy(admin = false, draft = emptyList()), draftMode = false)
        else current
    }
    val annotationState = scopedAnnotation?.takeIf { it.scope == pageScope }
    val editing = annotationRaw?.tool?.let { it != com.ensemble.scorelibrary.annotation.AnnotationTool.READ } == true
    LaunchedEffect(editing, fullscreen) { if (editing && !fullscreen) model.fitForAnnotation() }
    LaunchedEffect(fullscreen, state is PdfState.Failure, scopedAnnotation?.error, performance) {
        if (fullscreen && (state is PdfState.Failure || scopedAnnotation?.error != null || performance is PerformanceState.Failure))
            model.setFullscreen(false)
    }
    LaunchedEffect(playMode, annotationState?.error) {
        if (playMode && annotationState?.error != null) model.invalidatePerformance(annotationState.error)
    }
    // Recheck on an authority/scope transition, never on the result of the recheck itself:
    // null -> admin -> null could otherwise repeatedly suspend/load ink and flash controls.
    LaunchedEffect(inkAdmin, pageScope, playMode) {
        if ((!playMode || concert == null) && annotations != null && annotationRaw != null && annotationRaw.scope == pageScope && inkAdmin != null &&
            annotationRaw.data?.admin != null && annotationRaw.data.admin != inkAdmin) annotations.refreshAuthorization()
    }
    LaunchedEffect(playMode, frozenInk, pageScope) {
        if (playMode && concert != null && pageScope != null && frozenInk == null)
            model.invalidatePerformance("本场已准备页面失效，请退出后重新核验")
    }
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var symbolsOpen by remember(member, score.itemId, score.pieceId, score.part.id) { mutableStateOf(false) }
    LaunchedEffect(fullscreen, playMode) { if (fullscreen || playMode) symbolsOpen = false }
    BackHandler(symbolsOpen && !fullscreen) { symbolsOpen = false }
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
        Surface(Modifier.fillMaxWidth().testTag("reader-controls"), tonalElevation = 4.dp) {
        Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = back, modifier = Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            val ready = state as? PdfState.Ready
            IconButton(onClick = model::previousPage, enabled = !preparing && scopedAnnotation?.transitionBlocked != true && ready != null && (ready.index > 0 || (model.itemNumber()?.first ?: 1) > 1),
                modifier = Modifier.testTag("previous-page")) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "上一页") }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                if (concert != null) Text(activeScore.title, maxLines = 1, style = MaterialTheme.typography.titleSmall,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("reader-piece-title"))
                Text(activeScore.part.name, maxLines = 2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("reader-part-name"))
                val ordinal = model.itemNumber()?.let { "曲目 ${it.first} / ${it.second} · " }.orEmpty()
                Text(ordinal + if (notice != null) notice!! else if (ready != null) "${ready.index + 1} / ${ready.total} · PDF 第 ${ready.sourcePage} 页"
                    else if (state is PdfState.Failure) "曲谱不可用" else "正在打开曲谱",
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reader-page-number"))
                if (concert != null && annotations != null) Text(if (scopedAnnotation?.data?.locked == true) "本场共享版本已冻结"
                    else if (scopedAnnotation?.data != null) "本场共享版本未冻结" else "批注正在读取", maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = model::nextPage, enabled = !preparing && scopedAnnotation?.transitionBlocked != true && ready != null && (ready.index < ready.total - 1 ||
                model.itemNumber()?.let { it.first < it.second } == true),
                modifier = Modifier.testTag("next-page")) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "下一页") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                FitMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(selected = (state as? PdfState.Ready)?.mode == mode,
                        onClick = { model.fit(mode) }, enabled = !preparing && scopedAnnotation?.transitionBlocked != true, modifier = Modifier.widthIn(min = 128.dp),
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                        Text(if (mode == FitMode.WIDTH) "适合宽度" else "适合整页", maxLines = 1)
                    }
                }
            }
            IconButton(onClick = model::retry, enabled = !preparing && !playMode && scopedAnnotation?.transitionBlocked != true) { Icon(Icons.Outlined.Refresh, "重新打开") }
            IconButton(onClick = { model.setFullscreen(true) }, enabled = state is PdfState.Ready && !preparing && scopedAnnotation?.transitionBlocked != true,
                modifier = Modifier.testTag("reader-fullscreen")) { Icon(Icons.Outlined.Fullscreen, "全屏看谱") }
            IconButton(onClick = { model.transform(1.25f, 0f, 0f) }, enabled = state is PdfState.Ready && !preparing && scopedAnnotation?.transitionBlocked != true,
                modifier = Modifier.testTag("reader-zoom-in")) { Icon(Icons.Outlined.ZoomIn, "放大") }
            IconButton(onClick = model::resetView, enabled = state is PdfState.Ready && !preparing && scopedAnnotation?.transitionBlocked != true,
                modifier = Modifier.testTag("reader-zoom-reset")) { Icon(Icons.Outlined.ZoomOut, "恢复页面大小") }
            }
            TextButton(onClick = { model.action(PedalAction.TOGGLE_PLAY_MODE) },
                enabled = !preparing && state is PdfState.Ready && scopedAnnotation?.transitionBlocked != true,
                modifier = Modifier.testTag("play-mode")) {
                Icon(if (playMode) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
                Text(if (playMode) "退出演出" else if (concert == null) "单曲专注" else "准备演出")
            }
        }
        Text(if (offline) "离线阅读" else "本地阅读", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        if (concert != null) Text("演出中使用本场已锁定版本；退出或切到后台后需重新准备。",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        when (val readiness = performance) {
            is PerformanceState.Checking -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("performance-checking"),
                verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(Modifier.width(80.dp))
                Text("正在检查整场曲谱 ${readiness.checked} / ${readiness.total}", Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = model::cancelPreparation) { Icon(Icons.Outlined.Close, "取消准备") }
            }
            is PerformanceState.Failure -> Text(readiness.message, Modifier.padding(16.dp).testTag("performance-error"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
        if (annotations != null && !playMode && !preparing) com.ensemble.scorelibrary.annotation.AnnotationToolbar(
            (annotationState ?: scopedAnnotation?.copy(busy = true)
                ?: com.ensemble.scorelibrary.annotation.AnnotationState(busy = true)).let {
                if ((state as? PdfState.Ready)?.rendering == true) it.copy(busy = true) else it
            }, annotations, onSymbols = { symbolsOpen = true })
        }
        }
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("reader-viewport").background(com.ensemble.scorelibrary.ui.ScorePalette.Workspace).onSizeChanged { viewportSize = it; model.viewport(it.width, it.height) }, contentAlignment = Alignment.Center) {
            when (val page = state) {
                PdfState.Loading -> CircularProgressIndicator(Modifier.testTag("pdf-loading"))
                is PdfState.Failure -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(readerErrorText(page.error), Modifier.testTag("pdf-error"))
                    Button(onClick = model::retry) { Text("重试") }
                    if (fullscreen) IconButton(onClick = { model.setFullscreen(false) }) { Icon(Icons.Outlined.FullscreenExit, "退出全屏") }
                }
                is PdfState.Ready -> {
                    if (playMode && annotations != null && (annotationState?.busy != false || annotationState.data == null ||
                        concert != null && !annotationState.data.locked)) {
                        CircularProgressIndicator()
                        return@Box
                    }
                    val fitted = page.frame.transform.viewportWidth == viewportSize.width && page.frame.transform.viewportHeight == viewportSize.height
                    Image(page.frame.bitmap.asImageBitmap(), "${activeScore.title}，PDF 第 ${page.sourcePage} 页",
                        modifier = Modifier.fillMaxSize().testTag("pdf-page").pointerInput(page.mode, page.reference,
                            page.frame.transform.viewportWidth, page.frame.transform.viewportHeight, annotationState?.tool, playMode,
                            fullscreen, page.rendering, fitted, annotationState?.editLocked, annotationState?.transitionBlocked) {
                            val inkReady = annotations == null || annotationState?.let { !it.transitionBlocked && it.data != null && it.error == null } == true
                            val reading = fullscreen || playMode || annotationState == null || annotationState.editLocked ||
                                annotationState.tool == com.ensemble.scorelibrary.annotation.AnnotationTool.READ
                            if (inkReady && !preparing && !page.rendering && fitted && reading) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var dx = 0f
                                    var dy = 0f
                                    var multi = false
                                    var moved = false
                                    val wasZoomed = model.zoom() > 1.01f
                                    do {
                                        val event = awaitPointerEvent()
                                        val pan = event.calculatePan()
                                        dx += pan.x
                                        dy += pan.y
                                        multi = multi || event.changes.count { it.pressed } > 1
                                        moved = moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > viewConfiguration.touchSlop
                                        if (multi || wasZoomed) {
                                            val focus = event.calculateCentroid(useCurrent = false)
                                            model.transform(event.calculateZoom(), pan.x, pan.y, focus.x, focus.y)
                                        } else if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                                            model.transform(1f, 0f, pan.y)
                                        }
                                        if (moved || multi) event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    if (!multi && !wasZoomed && moved) readerSwipe(dx, dy, 64.dp.toPx())?.let(model::action)
                                    if (!moved && !multi) model.setFullscreen(!fullscreen)
                                }
                            }
                        }, contentScale = if (fitted) androidx.compose.ui.layout.ContentScale.FillBounds else androidx.compose.ui.layout.ContentScale.Fit)
                    if (fitted && annotations != null && annotationState?.data != null) {
                        com.ensemble.scorelibrary.annotation.AnnotationOverlay(page.frame.transform,
                            if (page.rendering) annotationState.copy(tool = com.ensemble.scorelibrary.annotation.AnnotationTool.READ, editLocked = true, selection = emptySet())
                            else if (playMode) annotationState.copy(tool = com.ensemble.scorelibrary.annotation.AnnotationTool.READ,
                                draftMode = false, showShared = true, showPersonal = true, selection = emptySet())
                            else if (fullscreen) annotationState.copy(tool = com.ensemble.scorelibrary.annotation.AnnotationTool.READ, selection = emptySet())
                            else annotationState, annotations)
                    }
                }
            }
            if (symbolsOpen && !fullscreen && !playMode && annotations != null) {
                com.ensemble.scorelibrary.annotation.MusicSymbolPalette(
                    annotationState ?: com.ensemble.scorelibrary.annotation.AnnotationState(busy = true),
                    choose = { annotations.symbol(it); symbolsOpen = false }, close = { symbolsOpen = false },
                    modifier = Modifier.align(Alignment.TopEnd).zIndex(2f))
            }
        }
    }
}
fun readerErrorText(error: ReaderError) = when (error) {
    ReaderError.FILE_MISSING -> "本地曲谱文件缺失"
    ReaderError.CORRUPTED -> "曲谱文件损坏或内容与当前版本不一致"
    ReaderError.ACTIVE_VERSION_MISSING -> "当前曲谱版本缺失"
    ReaderError.INVALID_PAGE_RANGE -> "曲谱页码范围无效"
    ReaderError.LOAD_FAILED -> "曲谱加载失败"
    ReaderError.PROGRAM_CHANGED -> "节目单或曲谱已更新，请重新打开并确认"
    ReaderError.RENDER_TIMEOUT -> "本机页面渲染等待过久，请返回或重试；已接收的曲谱无需重复下载。"
}
