package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
internal fun WorkspaceNavigation(selected: RootPage, onSelect: (RootPage) -> Unit) {
    Surface(color = ScorePalette.Navigation, contentColor = Color.White,
        modifier = Modifier.width(88.dp).fillMaxHeight().testTag("side-navigation")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.LibraryMusic, null, Modifier.align(Alignment.CenterHorizontally).size(28.dp))
            RootPage.entries.filterNot { it == RootPage.Admin }.forEach { page ->
                val active = selected == page || selected == RootPage.Admin && page == RootPage.Profile
                Surface(onClick = { onSelect(page) }, color = if (active) ScorePalette.NavigationSelected else Color.Transparent,
                    contentColor = if (active) Color.White else Color(0xFFC4CEC6),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("nav-${page.route}"),
                    selected = active) {
                    Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(page.icon, null); Text(page.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Metadata-only selection lives above transient loading and navigation destinations. */
internal class LibrarySelection(query: String = "", selected: String? = null, descending: Boolean = false) {
    val query = mutableStateOf(query)
    val selected = mutableStateOf(selected)
    val descending = mutableStateOf(descending)
    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<LibrarySelection, String>(
            save = { listOf(it.query.value, it.selected.value.orEmpty(), it.descending.value.toString()) },
            restore = { LibrarySelection(it[0], it[1].ifEmpty { null }, it[2].toBoolean()) })
    }
}
internal val LocalLibrarySelection = staticCompositionLocalOf<LibrarySelection?> { null }

/** Searches the existing Domain catalog; opening parts/PDFs still requires Domain authorization. */
internal fun filterPieces(pieces: List<PieceSummary>, query: String, descending: Boolean): List<PieceSummary> {
    val needle = query.trim()
    val matches = pieces.filter { it.title.contains(needle, ignoreCase = true) || it.composer.orEmpty().contains(needle, ignoreCase = true) }
    val sorted = matches.sortedWith(compareBy<PieceSummary> { it.title }.thenBy { it.id })
    return if (descending) sorted.reversed() else sorted
}

@Composable
internal fun PieceWorkspace(pieces: List<PieceSummary>, memberId: String,
    onPart: (String, String) -> Unit = { _, _ -> }, onPiece: (String) -> Unit) {
    val selection = LocalLibrarySelection.current ?: rememberSaveable(memberId, saver = LibrarySelection.Saver) { LibrarySelection() }
    var query by selection.query
    var selectedId by selection.selected
    var descending by selection.descending
    val matches = remember(pieces, query, descending) { filterPieces(pieces, query, descending) }
    val selected = selectedId?.takeIf { id -> matches.any { it.id == id } }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val preview = LocalScorePreview.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("搜索曲名或作曲") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } },
            modifier = Modifier.fillMaxWidth().testTag("library-search"), shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ScorePalette.Search,
                unfocusedContainerColor = ScorePalette.Search))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${matches.size} 首曲目", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { descending = !descending }, modifier = Modifier.testTag("library-sort")) {
                Text(if (descending) "曲名 ↓" else "曲名 ↑")
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val split = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { modifier ->
                if (matches.isEmpty()) Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (pieces.isEmpty()) "曲库为空" else "没有匹配的曲目")
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除搜索") }
                } else LazyColumn(modifier.testTag("library-list"), verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(matches, key = { it.id }) { piece ->
                        Surface(selected = selected == piece.id, onClick = { keyboard?.hide(); focus.clearFocus(force = true); selectedId = piece.id },
                            modifier = Modifier.fillMaxWidth().testTag("piece-${piece.id}"),
                            color = if (selected == piece.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(piece.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!piece.composer.isNullOrBlank()) Text(piece.composer, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            val details: () -> Unit = { selected?.let(onPiece) }
            if (split) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                list(Modifier.weight(.34f).fillMaxHeight())
                ScorePreviewPane(memberId, selected, preview, onPart, details, Modifier.weight(.66f), pieceTitle = pieces.firstOrNull { it.id == selected }?.title)
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                list(Modifier.fillMaxWidth().heightIn(max = 168.dp).weight(.32f))
                ScorePreviewPane(memberId, selected, preview, onPart, details, Modifier.weight(.68f), pieceTitle = pieces.firstOrNull { it.id == selected }?.title)
            }
        }
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
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ListItem(headlineContent = { Text(concert.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("查看个人演出谱与曲目顺序") }, leadingContent = { Icon(Icons.Outlined.Event, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
            }
        }
    }
}
