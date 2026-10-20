package com.ensemble.scorelibrary.annotation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Sliders live outside horizontally scrolling tool rows, with tap alternatives. */
@Composable
internal fun PenWidthSettings(state: AnnotationState, model: AnnotationViewModel, close: () -> Unit) {
    val enabled = state.editable && !state.gestureActive
    val color = Color(state.color)
    AlertDialog(onDismissRequest = close, title = { Text("笔画粗细") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            Text("调整后用于新笔画；已画内容请先套索选中，再应用。")
            Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("pen-width-preview")) {
                drawLine(color, Offset(16.dp.toPx(), size.height/2), Offset(size.width-16.dp.toPx(),size.height/2),
                    state.width * size.width * 2, StrokeCap.Round)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.width(state.width - .0005f) }, enabled = enabled,
                    modifier = Modifier.testTag("pen-width-minus")) { Text("−") }
                Text("粗细 ${"%.1f".format(java.util.Locale.ROOT,state.width * 1000)}", Modifier.weight(1f))
                TextButton(onClick = { model.width(state.width + .0005f) }, enabled = enabled,
                    modifier = Modifier.testTag("pen-width-plus")) { Text("＋") }
            }
            Slider(state.width * 1000, { model.width(it / 1000) }, valueRange = 1f..12f, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("pen-width").semantics { contentDescription = "笔画粗细" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("细"); Text("粗")
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("细" to .0015f, "标准" to .003f, "粗" to .006f, "加粗" to .01f).forEach { (label,width) ->
                    TextButton(onClick = { model.width(width) }, enabled = enabled, modifier = Modifier.weight(1f)
                        .testTag("pen-width-$label")) { Text(label) }
                }
            }
            TextButton(onClick = model::resizeSelectedStrokes,
                enabled = enabled && !state.transitionBlocked && state.marks.any { it.id in state.selection && it.kind != MarkKind.SYMBOL },
                modifier = Modifier.testTag("resize-selected-strokes")) { Text("应用到选中笔画") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
internal fun StampSettings(state: AnnotationState, model: AnnotationViewModel, close: () -> Unit) {
    val hairpin = HairpinGeometry.isHairpin(state.symbol)
    val enabled = state.editable && !state.gestureActive
    val color = Color(state.color)
    AlertDialog(onDismissRequest = close, title = { Text(if (hairpin) "${musicSymbolLabel(state.symbol)} · 长度与开口" else "印章大小") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Canvas(Modifier.fillMaxWidth().height(64.dp).testTag("stamp-settings-preview")) {
                if (hairpin) drawHairpin(state.symbol, Offset(12.dp.toPx(),size.height/2),
                    Offset(12.dp.toPx() + (size.width-24.dp.toPx()) * (state.hairpinLength/.65f),size.height/2),
                    12.dp.toPx() * state.symbolScale,color,1.5.dp.toPx())
                else drawMusicStamp(state.symbol,Offset(12.dp.toPx(),48.dp.toPx()),24.dp.toPx()*state.symbolScale,color)
            }
            if (hairpin) {
                Text("长度：点击放置使用此长度，也可在谱面横向拖画。")
                Slider(state.hairpinLength * 100, { model.hairpinLength(it/100) }, valueRange = 4f..65f, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("hairpin-length").semantics { contentDescription = "渐变符号长度" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("短"); Text("长") }
            }
            Text("${if(hairpin) "开口" else "印章大小"} ${(state.symbolScale * 100).toInt()}%")
            Slider(state.symbolScale, model::symbolScale, valueRange = .5f..2f, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("stamp-size").semantics { contentDescription = if(hairpin) "渐变符号开口" else "印章大小" })
            TextButton(onClick = model::resizeSelectedSymbols,
                enabled = enabled && !state.transitionBlocked && state.marks.any { it.id in state.selection && it.kind == MarkKind.SYMBOL },
                modifier = Modifier.testTag("settings-resize-selected-stamps")) { Text("应用到选中印章") }
            if (hairpin) Text("已有符号请先用套索选中，再应用新的长度与开口。")
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}
