package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.data.ManagementCommand

@Composable
internal fun LocalRemovalEntry(actor: String, programs: Boolean) {
    val vm = LocalManagement.current ?: return
    var show by remember(actor) { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    TextButton(onClick = { vm.bind(actor); show = true }, modifier = Modifier.testTag(if (programs) "local-program-delete-entry" else "local-piece-delete-entry")) {
        Text(if (programs) "删除／恢复节目单" else "删除／恢复曲目")
    }
    if (show) Dialog(onDismissRequest = { show = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(20.dp)) {
            var recycle by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf<Pair<String,String>?>(null) }
            val catalog = state.catalog?.takeIf { state.actor == actor }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (programs) "节目单删除与恢复" else "曲目删除与恢复", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { show = false }, modifier = Modifier.testTag("local-removal-back")) { Text("返回") }
            }
            Text("删除后可从回收站恢复。PDF、批注和原编号保留。" + if (!programs) "从节目单移除的条目不会随曲目恢复而自动重新加入。锁定演出引用的曲目不能删除。" else "")
            FilterChip(recycle, { recycle = !recycle }, label = { Text(if (recycle) "回收站" else "查看回收站") })
            OutlinedTextField(query, { query = it }, label = { Text("搜索名称或编号") }, modifier = Modifier.fillMaxWidth())
            if (state.busy || catalog == null && state.message == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, Modifier.testTag("local-removal-status")) }
            val rows = if (programs) catalog?.data?.concerts?.filter { it.isArchived == recycle }?.map { it.id to it.title }.orEmpty()
                else catalog?.data?.pieces?.filter { it.isArchived == recycle }?.map { it.id to "[${it.organizerSequence ?: "未编号"}] ${it.title}" }.orEmpty()
            LazyColumn(Modifier.weight(1f)) { items(rows.filter { it.second.contains(query.trim(), true) }, key = { it.first }) { row ->
                ListItem(headlineContent = { Text(row.second) }, trailingContent = {
                    TextButton(onClick = { selected = row }, enabled = !state.busy, modifier = Modifier.testTag("local-remove-${row.first}")) { Text(if (recycle) "恢复" else "删除") }
                })
            } }
            selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(if (recycle) "恢复" else "删除") },
                text = { Text(row.second + if (programs) "\n节目顺序和乐谱保留。" else "\n同时从未锁定的节目单中移除此曲目；保留 PDF 和批注。恢复曲目后，节目单条目需重新添加。") },
                confirmButton = { TextButton(onClick = {
                    selected = null
                    val command = if (programs) catalog?.data?.concerts?.firstOrNull { it.id == row.first }?.let { ManagementCommand.Concert(it.copy(isArchived = !recycle)) }
                        else ManagementCommand.RemovePiece(row.first, !recycle)
                    command?.let { vm.edit(actor, state.token, it) }
                }, modifier = Modifier.testTag("local-confirm-remove")) { Text("确认") } },
                dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }) }
        } }
    }
}
