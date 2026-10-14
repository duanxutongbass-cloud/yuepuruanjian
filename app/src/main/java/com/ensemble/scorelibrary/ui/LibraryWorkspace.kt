package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.domain.PieceSummary

@Composable
internal fun WorkspaceNavigation(selected: RootPage, expanded: Boolean, onSelect: (RootPage) -> Unit) {
    Surface(color = ScorePalette.Navigation, contentColor = Color.White,
        modifier = Modifier.width(if (expanded) 184.dp else 88.dp).fillMaxHeight().testTag("side-navigation")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.LibraryMusic, null, Modifier.align(Alignment.CenterHorizontally).size(28.dp))
            if (expanded) Text("重奏团曲谱库", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
            RootPage.entries.filterNot { it == RootPage.Admin }.forEach { page ->
                val active = selected == page || selected == RootPage.Admin && page == RootPage.Profile
                Surface(onClick = { onSelect(page) }, color = if (active) ScorePalette.NavigationSelected else Color.Transparent,
                    contentColor = if (active) Color.White else Color(0xFFC4CEC6),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("nav-${page.route}"),
                    selected = active) {
                    if (expanded) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(page.icon, null); Text(page.label)
                    } else Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(page.icon, null); Text(page.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Searches the existing Domain catalog; opening parts/PDFs still requires Domain authorization. */
internal fun filterPieces(pieces: List<PieceSummary>, query: String, alphabetical: Boolean): List<PieceSummary> {
    val needle = query.trim()
    val matches = pieces.filter { it.title.contains(needle, ignoreCase = true) || it.composer.orEmpty().contains(needle, ignoreCase = true) }
    return if (alphabetical) matches.sortedWith(compareBy<PieceSummary> { it.title }.thenBy { it.id }) else matches
}

@Composable
internal fun PieceWorkspace(pieces: List<PieceSummary>, memberId: String, onPiece: (String) -> Unit) {
    var query by rememberSaveable(memberId) { mutableStateOf("") }
    var grid by rememberSaveable(memberId) { mutableStateOf(true) }
    var alphabetical by rememberSaveable(memberId) { mutableStateOf(false) }
    val matches = remember(pieces, query, alphabetical) { filterPieces(pieces, query, alphabetical) }
    Column(Modifier.fillMaxSize()) {
    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("搜索曲名或作曲") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } },
        modifier = Modifier.fillMaxWidth().testTag("library-search"), shape = MaterialTheme.shapes.medium)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${matches.size} 首曲目", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { alphabetical = !alphabetical }, modifier = Modifier.testTag("library-sort")) {
            Text(if (alphabetical) "曲名排序" else "默认顺序")
        }
        IconToggleButton(checked = grid, onCheckedChange = { grid = it }, modifier = Modifier.testTag("library-layout")) {
            Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, if (grid) "切换列表" else "切换网格")
        }
    }
    if (matches.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (pieces.isEmpty()) "曲库为空" else "没有匹配的曲目", style = MaterialTheme.typography.titleMedium)
            Text(if (pieces.isEmpty()) "尚无曲目记录" else "试试曲名或作曲中的其他文字")
            if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除搜索") }
        }
    } else if (grid) {
        LazyVerticalGrid(columns = GridCells.Adaptive(232.dp), modifier = Modifier.testTag("library-grid"),
            contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(matches, key = { it.id }) { PieceCard(it, true, onPiece) }
        }
    } else LazyColumn(modifier = Modifier.testTag("library-list"), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(matches, key = { it.id }) { PieceCard(it, false, onPiece) }
    }
    }
}

@Composable
private fun PieceCard(piece: PieceSummary, grid: Boolean, onPiece: (String) -> Unit) {
    OutlinedCard(onClick = { onPiece(piece.id) }, modifier = Modifier.fillMaxWidth().testTag("piece-${piece.id}"),
        colors = CardDefaults.outlinedCardColors(containerColor = ScorePalette.Paper),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        if (grid) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Description, null, Modifier.size(32.dp), tint = ScorePalette.Accent)
            Text(piece.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(piece.composer?.takeIf { it.isNotBlank() } ?: "未填写作曲", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            HorizontalDivider()
            Text("查看声部", color = ScorePalette.Accent, style = MaterialTheme.typography.labelLarge)
        } else ListItem(headlineContent = { Text(piece.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(piece.composer.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(Icons.Outlined.Description, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
    }
}

@Composable
internal fun ConcertWorkspace(concerts: List<com.ensemble.scorelibrary.domain.ConcertSummary>, memberId: String, onConcert: (String) -> Unit) {
    var query by rememberSaveable(memberId) { mutableStateOf("") }
    val matches = concerts.filter { it.title.contains(query.trim(), ignoreCase = true) }
    OutlinedTextField(query, { query = it }, label = { Text("搜索节目单") }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } },
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("concert-search"))
    if (matches.isEmpty()) Text(if (concerts.isEmpty()) "暂无节目单" else "没有匹配的节目单", Modifier.padding(24.dp))
    else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(matches, key = { it.id }) { concert ->
            OutlinedCard(onClick = { onConcert(concert.id) }, modifier = Modifier.fillMaxWidth().testTag("concert-${concert.id}"),
                colors = CardDefaults.outlinedCardColors(containerColor = ScorePalette.Paper)) {
                ListItem(headlineContent = { Text(concert.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("查看个人演出谱与曲目顺序") }, leadingContent = { Icon(Icons.Outlined.Event, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
            }
        }
    }
}
