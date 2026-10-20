package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.domain.LibrarySnapshot

@Composable
fun ConcertPiecePicker(data: LibrarySnapshot, selected: String?, enabled: Boolean, change: (String?) -> Unit) {
    val rows = remember(data, selected) { managedPieceRows(data, true).filter { !it.piece.isArchived || it.piece.id == selected } }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val chosen = rows.firstOrNull { it.piece.id == selected }
    Column(Modifier.fillMaxWidth()) {
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("choice-曲目")) {
        Column(Modifier.fillMaxWidth()) {
            Text(chosen?.heading ?: "搜索并选择曲目")
            chosen?.let { Text(it.description, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (open) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().height(440.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("节目单选曲", style = MaterialTheme.typography.titleLarge)
                Text("选择整首曲目一次，即包含其全部声部；成员按授权和演奏分配阅读。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("搜索曲名、编号、分类、乐器、作曲／编曲") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("concert-piece-search"))
                AdminChoice("分类", category, listOf(null to "全部分类") + rows.map { it.piece.categoryName ?: "未分类" }.distinct().sorted().map { it to it },
                    tag = "concert-category", change = { category = it })
                val filtered = filterManagedPieces(rows, query, category)
                Text("找到 ${filtered.size} 首曲目", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).testTag("concert-piece-results")) {
                    if (filtered.isEmpty()) item { Text("没有匹配的曲目，请更换关键词或分类", Modifier.padding(16.dp)) }
                    items(filtered, key = { it.piece.id }) { row ->
                        ListItem(headlineContent = { Text(row.heading) }, supportingContent = { Text(row.description) },
                            modifier = Modifier.fillMaxWidth().clickable { change(row.piece.id); open = false }.testTag("concert-pick-${row.piece.id}"))
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = { open = false }) { Text("取消") }
            }
        }
    }
    }
}
