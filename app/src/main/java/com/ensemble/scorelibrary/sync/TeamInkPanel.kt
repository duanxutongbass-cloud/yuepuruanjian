package com.ensemble.scorelibrary.sync

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.account.TeamMember
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.awaitClose
import javax.inject.Inject

data class InkConflictRow(val version: String, val page: Int, val title: String, val conflict: InkConflict)
data class InkBackupRead(val account: String, val artifact: String, val page: Int, val marks: List<InkMark>,
    val frame: com.ensemble.scorelibrary.pdf.PdfFrame? = null)
data class InkToolsState(val key: String = "", val busy: Boolean = false, val message: String = "", val conflicts: List<InkConflictRow> = emptyList(),
    val scores: List<InkBackupScore> = emptyList(), val backup: InkBackupRead? = null)

@HiltViewModel
class TeamInkViewModel @Inject constructor(val cloud: TeamInkSync, @param:ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(InkToolsState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch { cloud.active.collectLatest { c ->
            mutable.value = InkToolsState(key = c?.key.orEmpty())
            if (c != null) merge(cloud.status.filter { it.key == c.key }.map { Unit }, databaseChanges(c)).collect {
                try { refresh(c) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (cloud.current() === c) mutable.value = mutable.value.copy(backup = null, scores = emptyList()) }
            }
        } }
    }
    private fun databaseChanges(c: TeamCollection): Flow<Unit> = callbackFlow {
        val tracker = c.database.invalidationTracker
        val observer = object : androidx.room.InvalidationTracker.Observer("member_piece_part_permissions", "score_pdf_versions", "part_score_bindings", "piece_parts", "score_pdfs") {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        tracker.addObserver(observer); trySend(Unit)
        awaitClose { tracker.removeObserver(observer) }
    }.conflate()
    private suspend fun refresh(c: TeamCollection) {
        val rows = withContext(Dispatchers.IO) {
            val d = c.database.resolutionDao().snapshot(); val out = mutableListOf<InkConflictRow>()
            c.database.annotationDao().cloudPages().filter { it.conflictPayload != null }.forEach { p ->
                val conflict = cloud.conflict(c, p.versionId, p.pageIndex, p.kind) ?: return@forEach
                val pdf = d.versions.find { it.id == p.versionId }?.scorePdfId
                val binding = d.bindings.find { it.scorePdfId == pdf }
                val part = d.parts.find { it.id == binding?.piecePartId }
                val title = d.pieces.find { it.id == part?.pieceId }?.title ?: "历史曲谱"
                out += InkConflictRow(p.versionId, p.pageIndex, "$title · ${part?.displayName.orEmpty()}", conflict)
            }
            out
        }
        val scores = cloud.readableScores(c)
        if (cloud.current() === c) mutable.value = mutable.value.copy(conflicts = rows, scores = scores,
            backup = mutable.value.backup?.takeIf { backup -> scores.any { it.artifact == backup.artifact } })
    }
    fun sync() = operation { c -> cloud.sync(c); refresh(c) }
    fun readMember(account: String, score: InkBackupScore, page: Int) = operation { c ->
        val marks = cloud.readMemberBackup(c, account, score, page)
        val frame = try {
            com.ensemble.scorelibrary.pdf.NativePdfEngine(context).render(
                com.ensemble.scorelibrary.pdf.PdfPageRef(cloud.backupSegment(c, score), page), 1000, 1400, com.ensemble.scorelibrary.pdf.FitMode.PAGE, 0f)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        cloud.backupSegment(c, score)
        if (cloud.current() === c) mutable.value = mutable.value.copy(backup = InkBackupRead(account, score.artifact, page, marks, frame),
            message = if (frame == null) "此设备暂时无法显示谱面，已显示只读笔迹；请先同步对应乐谱" else "已读取上传的个人批注（只读），访问已记录")
    }
    fun resolve(row: InkConflictRow, keepLocal: Boolean) = operation { c ->
        cloud.resolve(c, row.version, row.page, row.conflict, keepLocal); cloud.sync(c); refresh(c)
    }
    fun export(uri: Uri, key: String) = operation { c ->
        check(c.key == key) { "账号已切换，请重新选择导出位置" }
        val json = cloud.personalBackup(c)
        withContext(Dispatchers.IO) {
            check(cloud.current() === c)
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                check(cloud.current() === c); output.write(json.toByteArray(Charsets.UTF_8))
            }
        }
        if (cloud.current() === c) mutable.value = mutable.value.copy(message = "已导出本账号云端及本机已保存个人笔迹，不包含 PDF 或其他账号批注")
    }
    private fun operation(block: suspend (TeamCollection) -> Unit) {
        val c = cloud.current() ?: return
        if (mutable.value.busy || mutable.value.key != c.key) return
        mutable.value = mutable.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try { block(c) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (cloud.current() === c) mutable.value = mutable.value.copy(message =
                if (error is IllegalStateException || error is IllegalArgumentException) error.message ?: "操作未完成"
                else "暂时无法同步，请检查网络后重试；本机笔迹仍保留") }
            finally { if (cloud.current() === c) mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

val LocalInkTools = staticCompositionLocalOf<TeamInkViewModel?> { null }

@Composable
fun TeamInkPanel(vm: TeamInkViewModel, members: List<TeamMember> = emptyList()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.cloud.status.collectAsStateWithLifecycle()
    val collection by vm.cloud.active.collectAsStateWithLifecycle()
    if (collection?.key != state.key || collection == null) return
    var exportKey by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri, exportKey) }
    Text("批注同步", style = MaterialTheme.typography.titleMedium)
    if (status.key == state.key) {
        if (status.busy || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status.message, Modifier.testTag("ink-cloud-status"))
        if (status.pending > 0) Text("待同步 ${status.pending} 页；冲突 ${status.conflicts} 页", style = MaterialTheme.typography.bodySmall)
    }
    if (state.message.isNotBlank()) Text(state.message)
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = vm::sync, enabled = !state.busy && !status.busy, modifier = Modifier.testTag("ink-cloud-retry")) { Text("同步批注／重试") }
        TextButton(onClick = { exportKey = state.key; save.launch("个人批注备份.json") }, enabled = !state.busy && !status.busy) { Text("取回我的批注备份") }
    }
    state.conflicts.forEach { row ->
        Text("${row.title} · 第 ${row.page + 1} 页", style = MaterialTheme.typography.titleSmall)
        InkConflictPanel(row.conflict, enabled = !state.busy && !status.busy) { vm.resolve(row, it) }
    }
    if (collection?.session?.principal?.role == "admin") AdminInkBackupPanel(state, members, vm::readMember)
}

@Composable
internal fun AdminInkBackupPanel(state: InkToolsState, members: List<TeamMember>, read: (String, InkBackupScore, Int) -> Unit) {
    var memberId by remember(state.key) { mutableStateOf("") }
    var artifact by remember(state.key) { mutableStateOf("") }
    var page by remember(state.key) { mutableStateOf("1") }
    var peopleOpen by remember { mutableStateOf(false) }; var scoresOpen by remember { mutableStateOf(false) }
    val member = members.find { it.id == memberId }; val score = state.scores.find { it.artifact == artifact }
    val pageIndex = page.toIntOrNull()?.minus(1)
    HorizontalDivider()
    Text("查看已上传个人批注（管理员只读）", style = MaterialTheme.typography.titleMedium)
    Text("只读取已确认备份说明的本团队账号；访问会记录。需要同时拥有对应乐谱权限，不会修改成员笔迹。", style = MaterialTheme.typography.bodySmall)
    Box {
        TextButton(onClick = { peopleOpen = true }, enabled = !state.busy) { Text(member?.name ?: "选择成员") }
        DropdownMenu(peopleOpen, { peopleOpen = false }) {
            DropdownMenuItem(text = { Text("关闭菜单") }, onClick = { peopleOpen = false })
            members.forEach { m -> DropdownMenuItem(text = { Text("${m.name} · ${m.username}") },
            onClick = { memberId = m.id; peopleOpen = false }) } }
    }
    Box {
        TextButton(onClick = { scoresOpen = true }, enabled = !state.busy) { Text(score?.title ?: "选择已授权分谱") }
        DropdownMenu(scoresOpen, { scoresOpen = false }) {
            DropdownMenuItem(text = { Text("关闭菜单") }, onClick = { scoresOpen = false })
            state.scores.forEach { s -> DropdownMenuItem(text = { Text(s.title) },
            onClick = { artifact = s.artifact; page = "1"; scoresOpen = false }) } }
    }
    OutlinedTextField(page, { page = it.filter(Char::isDigit).take(5) }, label = { Text("页码${score?.let { "（共 ${it.pages} 页）" }.orEmpty()}") }, singleLine = true, enabled = !state.busy)
    TextButton(onClick = { if (member != null && score != null && pageIndex != null) read(member.id, score, pageIndex) },
        enabled = !state.busy && member != null && score != null && pageIndex != null && pageIndex in 0 until score.pages,
        modifier = Modifier.testTag("admin-read-personal-ink")) { Text("读取此页已上传个人批注") }
    state.backup?.takeIf { score != null && it.account == memberId && it.artifact == artifact && it.page == pageIndex }?.let {
        Text("${member?.name.orEmpty()} · 第 ${it.page + 1} 页 · ${it.marks.size} 笔（只读）")
        InkPreview(it.marks, it.frame)
    }
}

@Composable
fun InkConflictPanel(conflict: InkConflict, enabled: Boolean, resolve: (Boolean) -> Unit) {
    var choice by remember(conflict) { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("ink-conflict-panel")) {
        Text(if (conflict.kind == "personal") "个人批注发生冲突，双方笔迹已保留" else "管理员共享批注发生冲突，双方已保留")
        Text("以下为笔迹位置预览，不含谱面。请比较后选择；不会自动合并。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Text("本机 · ${conflict.local.size} 笔"); InkPreview(conflict.local) }
            Column(Modifier.weight(1f)) { Text("云端 · ${conflict.remote.size} 笔"); InkPreview(conflict.remote) }
        }
        Row {
            TextButton(onClick = { choice = true }, enabled = enabled, modifier = Modifier.testTag("ink-choose-local")) { Text("使用本机这份") }
            TextButton(onClick = { choice = false }, enabled = enabled, modifier = Modifier.testTag("ink-choose-cloud")) { Text("使用云端这份") }
        }
    }
    choice?.let { local -> AlertDialog(onDismissRequest = { choice = null }, title = { Text(if (local) "使用本机笔迹？" else "使用云端笔迹？") },
        text = { Text(if (local) "将以当前云端修订为基线提交本机这份；若再次发生并发修改，会继续保留双方并提示。历史候选保留。" else "本机改用你看到的云端这份；现有本机笔迹先保留在历史记录。如果云端再次变化，需要重新比较。") },
        confirmButton = { TextButton(onClick = { choice = null; resolve(local) }) { Text("确认选择") } },
        dismissButton = { TextButton(onClick = { choice = null }) { Text("继续保留双方") } }) }
}

@Composable
internal fun InkPreview(marks: List<InkMark>, frame: com.ensemble.scorelibrary.pdf.PdfFrame? = null) {
    val dimensions = if (frame == null) Modifier.height(180.dp) else Modifier.aspectRatio(frame.bitmap.width.toFloat() / frame.bitmap.height)
    Canvas(Modifier.fillMaxWidth().then(dimensions).background(Color.White)) {
        if (frame != null) drawImage(frame.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        marks.forEach { mark ->
            val color = Color(mark.color).let { if (mark.kind == MarkKind.HIGHLIGHT) it.copy(alpha = .28f) else it }
            fun point(p: InkPoint): Offset {
                if (frame == null) return Offset(p.x * size.width, p.y * size.height)
                val screen = frame.transform.normalizedToScreen(p.x, p.y)
                return Offset(screen.x * size.width / frame.bitmap.width, screen.y * size.height / frame.bitmap.height)
            }
            val pageWidth = if (frame == null) size.width else frame.transform.page.width * frame.transform.scale * size.width / frame.bitmap.width
            val width = mark.width * pageWidth
            if (mark.kind == MarkKind.SYMBOL) drawMusicStamp(mark.symbol, point(mark.points.first()), (pageWidth * .032f).coerceAtLeast(12f)*mark.symbolScale, color)
            else if(mark.pressures!=null)drawPressureInk(mark,pageWidth,color,::point)
            else if (mark.points.size == 1) drawCircle(color, width.coerceAtLeast(1f) / 2, point(mark.points.first()))
            else drawPath(Path().apply { val p = point(mark.points.first()); moveTo(p.x, p.y)
                mark.points.drop(1).forEach { val next = point(it); lineTo(next.x, next.y) } }, color,
                style = Stroke(width.coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
