package com.ensemble.scorelibrary.reading

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.domain.PieceSummary

internal enum class ShelfFilter(val label: String) { ALL("全部"), RECENT("最近阅读"), FAVORITES("收藏") }
internal fun shelfPieces(pieces: List<PieceSummary>, data: ReadingActivityData, filter: ShelfFilter, descending: Boolean): List<PieceSummary> {
    if (filter == ShelfFilter.ALL) return pieces
    if (filter == ShelfFilter.FAVORITES) return pieces.filter { it.id in data.favorites }
    val order = data.recent.map { it.piece }.distinct().withIndex().associate { it.value to it.index }
    val recent = pieces.filter { it.id in order }.sortedBy { order.getValue(it.id) }
    return if (descending) recent.reversed() else recent
}

@Composable
internal fun ReadingShelfControls(filter: ShelfFilter, onFilter: (ShelfFilter) -> Unit, state: ReadingShelfState?,
    onClear: () -> Unit, onClearFavorites: () -> Unit, onRetry: () -> Unit) {
    var explain by remember { mutableStateOf(false) }
    var confirmClear by remember(state?.owner) { mutableStateOf<ShelfFilter?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfFilter.entries.forEach { mode -> FilterChip(selected = filter == mode, onClick = { onFilter(mode) },
            label = { Text(mode.label) }, modifier = Modifier.testTag("shelf-${mode.name.lowercase()}")) }
        if (filter == ShelfFilter.RECENT && state?.data?.recent?.isNotEmpty() == true)
            TextButton(onClick = { confirmClear = ShelfFilter.RECENT }, enabled = !state.busy, modifier = Modifier.testTag("clear-recent")) { Text("清空最近") }
        if (filter == ShelfFilter.FAVORITES && state?.data?.favorites?.isNotEmpty() == true)
            TextButton(onClick = { confirmClear = ShelfFilter.FAVORITES }, enabled = !state.busy, modifier = Modifier.testTag("clear-favorites")) { Text("清空收藏") }
        IconButton(onClick = { explain = true }) { Icon(Icons.Outlined.Info, "最近与收藏说明") }
    }
    if (state?.error != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(state.error, Modifier.weight(1f).testTag("shelf-error"), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry, enabled = !state.busy) { Text("重试记录") }
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("最近阅读与收藏") },
        text = { Text("记录仅保存在当前设备，按账号分别保存；换设备或卸载后不会自动带过去。最近保留 100 份分谱，收藏最多 2000 首。曲目重新打开时仍须当前授权，换版后从第一页开始；清空最近会清除持久页码，收藏保留。") },
        confirmButton = { TextButton(onClick = { explain = false }) { Text("知道了") } })
    if (confirmClear != null) {
        val recent = confirmClear == ShelfFilter.RECENT
        AlertDialog(onDismissRequest = { confirmClear = null }, title = { Text(if (recent) "清空本机最近阅读？" else "清空本机收藏？") },
            text = { Text(if (recent) "清除当前账号在本机的全部最近阅读及持久页码，包括其他分类；收藏将保留。" else
                "清除当前账号在本机的全部收藏，包括暂不可见的曲目；最近阅读和页码保留。") },
            confirmButton = { TextButton(onClick = { confirmClear = null; if (recent) onClear() else onClearFavorites() },
                modifier = Modifier.testTag(if (recent) "confirm-clear-recent" else "confirm-clear-favorites")) { Text(if (recent) "清空最近记录" else "清空全部收藏") } },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("保留") } })
    }
}
