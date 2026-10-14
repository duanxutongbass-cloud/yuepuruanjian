package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val LocalScorePreview = staticCompositionLocalOf<ScorePreviewViewModel?> { null }

@Composable
internal fun ScorePreviewPane(member: String, piece: String?, model: ScorePreviewViewModel?,
    onPart: (String, String) -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    // A pause/resume pair can occur while composition is suspended; a boolean alone loses that event.
    var resumeGeneration by remember(owner) { mutableLongStateOf(0L) }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; resumeGeneration++ }
            if (event == Lifecycle.Event.ON_PAUSE) { resumed = false; model?.clear() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model?.clear() }
    }
    LaunchedEffect(member, piece, size, model, resumed, resumeGeneration) {
        if (resumed) model?.bind(member, piece, size.width, size.height) else model?.clear()
    }
    val raw = model?.state?.collectAsStateWithLifecycle()?.value
    // Reject old frames in this composition too, before the bind effect has run.
    // Lifecycle collection may retain a pre-pause value; retired images must never reappear.
    val state = if (resumed) when (raw) {
        is ScorePreviewState.Ready -> raw.takeIf { it.target.member == member && it.target.piece == piece && it.image.canDisplay() }
        is ScorePreviewState.Failure -> raw.takeIf { it.target.member == member && it.target.piece == piece }
        else -> null
    } else null
    Column(modifier.fillMaxSize().testTag("score-preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("个人演奏谱预览", style = MaterialTheme.typography.titleMedium)
                Text((state as? ScorePreviewState.Ready)?.score?.part?.name ?: "当前成员的实际声部",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (piece != null) TextButton(onClick = onDetails, modifier = Modifier.testTag("preview-parts")) { Text("声部详情") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
            when {
                piece == null -> Text("选择左侧曲目查看谱页", Modifier.testTag("preview-empty"))
                model == null -> Text("请打开声部详情查看曲谱")
                state is ScorePreviewState.Ready -> AndroidView(
                    factory = { PreviewImageView(it) },
                    modifier = Modifier.fillMaxSize().testTag("preview-page"),
                    onReset = { it.clearPreview() }, onRelease = { it.clearPreview() },
                    update = { it.contentDescription = "${state.score.title} · ${state.score.part.name} · 原谱预览"; it.show(state.image) })
                state is ScorePreviewState.Failure -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, null)
                    Text(state.message, Modifier.testTag("preview-error"))
                    TextButton(onClick = { model.retry() }, modifier = Modifier.testTag("preview-retry")) { Text("重试预览") }
                }
                else -> CircularProgressIndicator(Modifier.testTag("preview-loading"))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("原谱预览 · 批注请在阅读器查看", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            val ready = state as? ScorePreviewState.Ready
            Button(enabled = ready != null, onClick = {
                // An invalidation can arrive after composition: recheck the exact live frame on click.
                if (ready != null && model?.state?.value === ready) onPart(ready.score.pieceId, ready.score.part.id)
            }, modifier = Modifier.testTag("preview-open-reader")) { Text("打开阅读器") }
        }
    }
}
