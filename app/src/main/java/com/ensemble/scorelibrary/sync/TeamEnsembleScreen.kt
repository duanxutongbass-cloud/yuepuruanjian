package com.ensemble.scorelibrary.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.account.*

val LocalTeamEnsemble = staticCompositionLocalOf<TeamEnsembleViewModel?> { null }

@Composable
fun TeamReadingPart() {
    val vm = LocalTeamEnsemble.current ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.readingParts.isNotEmpty()) com.ensemble.scorelibrary.ui.AdminChoice("我的阅读声部", state.readingPart,
        state.readingParts.map { it.first to it.second }, !state.busy, "team-reading-part") { it?.let(vm::readingPart) }
}

@Composable
fun TeamEnsembleEntry(account: TeamAccountViewModel, programs: Boolean = false, compact: Boolean = false) {
    val accountState by account.state.collectAsStateWithLifecycle()
    val p = accountState.principal ?: return
    if (p.role != "admin") return
    val vm = LocalTeamEnsemble.current ?: return
    TextButton(onClick = { vm.openManagement(programs) }, modifier = Modifier.testTag(if (programs) "team-manage-programs" else "team-manage-library")) {
        Text(if (compact) if (programs) "节目单管理" else "曲库管理" else if (programs) "管理节目单／整曲选曲" else "管理乐团曲库／删除与恢复")
    }
}

/** Lives above the volatile library Loading/Ready/Error branches. */
@Composable
fun TeamEnsembleDialogHost(account:TeamAccountViewModel) {
    val vm=LocalTeamEnsemble.current ?: return
    val management by vm.management.collectAsStateWithLifecycle()
    val accountState by account.state.collectAsStateWithLifecycle()
    val p=accountState.principal
    val expected=p?.takeIf { it.role=="admin" && it.joined }?.let { teamId(accountState.server+"/"+it.teamId+"/"+it.accountId) }
    LaunchedEffect(expected,management?.key) { if(management!=null && management?.key!=expected)vm.closeManagement() }
    management?.takeIf { it.key==expected }?.let { management ->
        val state by vm.state.collectAsStateWithLifecycle()
        if (state.key != expected) return
        LaunchedEffect(state.changes) { if (state.changes > 0) account.sync() }
        Dialog(onDismissRequest = vm::closeManagement, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (management.programs) "乐团节目单" else "乐团曲库", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = vm::reload, enabled = !state.busy) { Text("刷新") }
                    TextButton(onClick = { vm.closeManagement(); account.sync() }, modifier = Modifier.testTag("team-management-back")) { Text("返回") }
                }
                if (state.busy) Text("可以返回；已提交的操作继续处理，再次打开可查看结果。", style = MaterialTheme.typography.bodySmall)
                key(management.key,management.programs) { TeamEnsembleManager(state, accountState.members, management.programs, vm::save, vm::remove, vm::receive, vm::freeze, vm::checkNumbers,
                    vm::previewPermanent,vm::confirmPermanent,vm::dismissPermanent,vm::previewPermanent,
                    vm::previewNumberRelease,vm::confirmNumberRelease,vm::dismissNumberRelease,
                    vm::previewBatch,vm::confirmBatch,vm::dismissBatch) }
            } }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TeamEnsembleManager(state: EnsembleState, members: List<TeamMember>, programs: Boolean,
    save: (EnsembleProgram) -> Unit, remove: (EnsemblePiece) -> Unit, receive: (EnsemblePiece) -> Unit = {},
    freeze: (EnsembleProgram) -> Unit = {}, checkNumbers: () -> Unit = {},
    previewPermanent:(EnsemblePiece)->Unit={},confirmPermanent:(PermanentPreview)->Unit={},dismissPermanent:()->Unit={},
    previewSelected:(Set<String>)->Unit={},previewNumberRelease:(Set<String>)->Unit={},
    confirmNumberRelease:(NumberReleasePreview)->Unit={},dismissNumberRelease:()->Unit={},
    previewBatch:(Set<String>,Boolean)->Unit={_,_->},confirmBatch:(TeamBatchPlan)->Unit={},dismissBatch:()->Unit={}) {
    var query by remember(state.key) { mutableStateOf("") }
    var recycle by remember(state.key) { mutableStateOf(false) }
    var selected by remember(state.key) { mutableStateOf(emptySet<String>()) }
    val eligible=state.pieces.filter { it.removed==recycle && !it.conflict && it.parts.isNotEmpty() }.map { it.key }.toSet()
    LaunchedEffect(eligible) { selected=selected.intersect(eligible) }
    var edit by remember(state.key) { mutableStateOf<EnsembleProgram?>(null) }
    var confirmPiece by remember(state.key) { mutableStateOf<EnsemblePiece?>(null) }
    var confirmProgram by remember(state.key) { mutableStateOf<EnsembleProgram?>(null) }
    var confirmReceive by remember(state.key) { mutableStateOf<EnsemblePiece?>(null) }
    var confirmFreeze by remember(state.key) { mutableStateOf<EnsembleProgram?>(null) }
    var showNumbers by remember(state.key) { mutableStateOf(false) }
    LaunchedEffect(state.changes) { edit = null }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.message.isNotBlank()) Text(state.message, Modifier.testTag("team-management-status"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(recycle, { recycle = !recycle; selected=emptySet() }, label = { Text(if (recycle) "回收站" else "查看回收站") })
        if (!programs) TextButton(onClick = { showNumbers = true; checkNumbers() }, enabled = !state.busy && state.revision >= 0,
            modifier = Modifier.testTag("team-check-numbers")) { Text("检查编号／补空号") }
        if (programs && !recycle) TextButton(onClick = { edit = EnsembleProgram() }, enabled = !state.busy && state.revision >= 0,
            modifier = Modifier.testTag("team-new-program")) { Text("新建节目单") }
    }
    if(showNumbers)NumberInventoryDialog(state,{showNumbers=false},previewNumberRelease,confirmNumberRelease,dismissNumberRelease)
    OutlinedTextField(query, { query = it }, label = { Text(if (programs) "搜索节目单" else "搜索曲名、编号、分类或乐器") }, modifier = Modifier.fillMaxWidth())
    if (programs) {
        val rows = state.programs.filter { it.archived == recycle && it.title.contains(query.trim(), true) }
        LazyColumn(Modifier.fillMaxWidth().testTag("team-program-list")) {
            items(rows, key = { it.id }) { program ->
                ListItem(headlineContent = { Text(program.title) }, supportingContent = { Text("${program.pieces.size} 首乐曲 · " +
                    if(program.freezeId != null) "已冻结 · 原场次内容保留" else "每首包含全部声部 · 尚未冻结") },
                    trailingContent = { Row {
                        if (!recycle) {
                            if(program.freezeId == null) TextButton(onClick = { confirmFreeze = program }, enabled = !state.busy,
                                modifier = Modifier.testTag("team-freeze-program-${program.id}")) { Text("冻结本场") }
                            TextButton(onClick = { edit = if(program.freezeId == null) program else EnsembleProgram(
                                title = program.title.take(94) + "（新场次）", pieces = program.pieces, accounts = program.accounts) },
                                enabled = !state.busy, modifier = Modifier.testTag("team-edit-program-${program.id}")) {
                                Text(if(program.freezeId == null) "编辑选曲" else "复制为新场次")
                            }
                        }
                        TextButton(onClick = { confirmProgram = program }, enabled = !state.busy, modifier = Modifier.testTag("team-remove-program-${program.id}")) { Text(if (recycle) "恢复" else "删除") }
                    } })
                HorizontalDivider()
            }
            if (rows.isEmpty()) item { Text(if (recycle) "暂无已删除节目单" else "暂无节目单") }
        }
    } else {
        val rows = state.pieces.filter { it.removed == recycle && (it.heading + it.description).contains(query.trim(), true) }
        val picked=state.pieces.filter { it.key in selected }
        val partCount=picked.sumOf { it.parts.size }
        FlowRow(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(onClick={selected=rows.map { it.key }.filter { it in eligible }.toSet()},enabled=!state.busy,
                modifier=Modifier.testTag("team-select-visible")) { Text("全选搜索结果") }
            TextButton(onClick={selected=emptySet()},enabled=!state.busy) { Text("取消选择") }
            TextButton(onClick={previewBatch(selected,!recycle)},enabled=!state.busy && selected.isNotEmpty() && partCount in 1..200,
                modifier=Modifier.testTag("team-batch-selected")) { Text(if(recycle)"恢复所选（${selected.size}首）" else "移入回收站（${selected.size}首）") }
            if(recycle)TextButton(onClick={previewSelected(selected)},enabled=!state.busy && selected.isNotEmpty() && partCount in 1..200,
                modifier=Modifier.testTag("team-purge-selected")) { Text("彻底删除所选（${selected.size}首／${partCount}份）") }
        }
        if(partCount>200)Text("一次最多200份分谱，请减少选择后合并预览。")
        LazyColumn(Modifier.fillMaxWidth().testTag("team-score-management")) {
            items(rows, key = { it.key }) { piece ->
                ListItem(headlineContent = { Text(piece.heading) }, supportingContent = { Text(piece.description) },
                    leadingContent = { Checkbox(piece.key in selected,{ checked -> selected=if(checked)selected+piece.key else selected-piece.key },
                        enabled=!state.busy && piece.key in eligible,modifier=Modifier.testTag("team-select-${piece.key}")) },
                    trailingContent = { Row {
                        if (!recycle) TextButton(onClick = { confirmReceive = piece }, enabled = !state.busy) { Text("接收阅读") }
                        TextButton(onClick = { confirmPiece = piece }, enabled = !state.busy,
                            modifier = Modifier.testTag("team-remove-piece-${piece.key}")) { Text(if (recycle) "恢复" else "删除") }
                        if(recycle)TextButton(onClick={previewPermanent(piece)},enabled=!state.busy && piece.key in eligible && piece.parts.size<=200,
                            modifier=Modifier.testTag("team-purge-${piece.key}")) { Text("彻底删除") }
                    } })
                HorizontalDivider()
            }
            if (rows.isEmpty()) item { Text(if (recycle) "暂无已删除乐谱" else "暂无乐谱，请先上传") }
        }
    }
    state.batchPreview?.let { plan ->
        var checked by remember(plan.request) { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!state.busy)dismissBatch()},title={Text(if(plan.removed)"确认批量移入回收站" else "确认批量恢复")},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("所选${plan.pieces.size}首曲目，共${plan.artifacts.size}份分谱")
                plan.pieces.forEach { Text(it.heading+" · "+it.parts.size+"份") }
                Text(if(plan.removed)"仅下架所选整曲，成员同步后不能打开；原PDF、编号和批注保留，可从回收站恢复。"
                    else "恢复所选整曲及原阅读授权，原编号和批注保持。")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked,{checked=it},enabled=!state.busy,modifier=Modifier.testTag("team-batch-consent"));Text("我已核对所选曲目")
                }
            }},confirmButton={TextButton({confirmBatch(plan)},enabled=checked && !state.busy,
                modifier=Modifier.testTag("team-batch-confirm")){Text("确认")}},
            dismissButton={TextButton(dismissBatch,enabled=!state.busy){Text("取消")}})
    }
    edit?.let { original -> TeamProgramEditor(original, state.pieces.filter { !it.removed }, members, state.busy, state.message,
        close = { edit = null }, save = save) }
    state.permanentPreview?.let { preview ->
        var checked by remember(preview.request) { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!state.busy)dismissPermanent()},title={Text("确认彻底删除")},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("将从NAS删除以下分谱和对应批注，操作无法从回收站恢复。")
                preview.titles.forEach { Text(it) }
                Text("${preview.artifacts.size}份PDF（${preview.bytes}字节）；个人批注${preview.personal}页，共享批注${preview.shared}页，历史${preview.history}条。")
                Text("影响${preview.programs.size}个节目单、${preview.frozen}份冻结场次、${preview.folders}个账号的文件夹引用。")
                preview.programs.forEach { Text(it) }
                Text("受影响的在演阅读会结束。各端联网后清理软件内已识别副本；未联网设备下次联网处理。原始来源和历史备份保留，编号继续占用。")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked,{checked=it},enabled=!state.busy,modifier=Modifier.testTag("team-purge-checked"))
                    Text("我已核对范围及影响，确认彻底删除")
                }
            }},confirmButton={TextButton(onClick={confirmPermanent(preview)},enabled=checked && !state.busy,
                modifier=Modifier.testTag("confirm-team-purge")) { Text("彻底删除") }},
            dismissButton={TextButton(onClick=dismissPermanent,enabled=!state.busy) { Text("取消") }})
    }
    confirmFreeze?.let { program -> AlertDialog(onDismissRequest = { confirmFreeze = null }, title = { Text("冻结本场演出") },
        text = { Text("${program.title}\n固定本场曲目顺序、分谱和已发布的共享批注。个人批注与共享草稿不包含在内。成员仍须有乐谱授权并完成同步、演出准备。\n冻结后如需改动，请复制为新场次；原场次和原 PDF 保留。") },
        confirmButton = { TextButton(onClick = { confirmFreeze = null; freeze(program) }, enabled = !state.busy,
            modifier = Modifier.testTag("confirm-team-freeze")) { Text("确认冻结") } },
        dismissButton = { TextButton(onClick = { confirmFreeze = null }) { Text("取消") } }) }
    confirmPiece?.let { piece -> AlertDialog(onDismissRequest = { confirmPiece = null }, title = { Text(if (piece.removed) "恢复乐曲" else "删除整首乐曲") },
        text = { Text("${piece.heading}\n${piece.description}\n" + if (piece.removed) "恢复原分谱、编号和已有阅读授权。" else "此操作将下架这首乐曲的全部 ${piece.parts.size} 份分谱；成员同步后不可再打开。原 PDF、编号及批注保留，可在回收站恢复。") },
        confirmButton = { TextButton(onClick = { confirmPiece = null; remove(piece) }, modifier = Modifier.testTag("confirm-team-score-remove")) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmPiece = null }) { Text("取消") } }) }
    confirmProgram?.let { program -> AlertDialog(onDismissRequest = { confirmProgram = null }, title = { Text(if (program.archived) "恢复节目单" else "删除节目单") },
        text = { Text("${program.title}\n节目顺序保留，乐谱和批注不删除，可从回收站恢复。") },
        confirmButton = { TextButton(onClick = { confirmProgram = null; save(program.copy(archived = !program.archived)) }, modifier = Modifier.testTag("confirm-team-program-remove")) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmProgram = null }) { Text("取消") } }) }
    confirmReceive?.let { piece -> AlertDialog(onDismissRequest = { confirmReceive = null }, title = { Text("为当前管理员接收乐谱") },
        text = { Text("${piece.heading}\n接收这首乐曲全部 ${piece.parts.size} 份分谱的阅读权限。已有成员授权保持不变。") },
        confirmButton = { TextButton(onClick = { confirmReceive = null; receive(piece) }) { Text("确认接收") } },
        dismissButton = { TextButton(onClick = { confirmReceive = null }) { Text("取消") } }) }
}

@Composable
internal fun TeamProgramEditor(original: EnsembleProgram, pieces: List<EnsemblePiece>, members: List<TeamMember>, busy: Boolean,
    message: String, close: () -> Unit, save: (EnsembleProgram) -> Unit) {
    var title by remember(original.id) { mutableStateOf(original.title) }
    var selected by remember(original.id) { mutableStateOf(original.pieces) }
    var query by remember { mutableStateOf("") }
    var restricted by remember(original.id) { mutableStateOf(original.accounts != null) }
    var accounts by remember(original.id) { mutableStateOf(original.accounts.orEmpty()) }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("节目单整曲选曲", style = MaterialTheme.typography.titleLarge)
            Text("按唯一曲目序号选择一首乐曲，其全部声部一起纳入节目单；不会按相同曲名合并。")
            OutlinedTextField(title, { title = it }, label = { Text("节目单名称") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("team-program-title"))
            OutlinedTextField(query, { query = it }, label = { Text("搜索曲名、编号、分类、声部") }, modifier = Modifier.fillMaxWidth().testTag("team-program-search"))
            LazyColumn(Modifier.weight(1f).testTag("team-program-piece-picker")) {
                items(pieces.filter { (it.heading + it.description).contains(query.trim(), true) }, key = { it.key }) { piece ->
                    ListItem(headlineContent = { Text(piece.heading) }, supportingContent = { Column {
                        Text(piece.category)
                        Text("整首包含 ${piece.parts.size} 个声部" + if (piece.conflict) " · 编号冲突，请先核对" else "")
                        piece.parts.forEach { part -> Text("${part.number} · ${part.name}", Modifier.testTag("program-voice-${part.id}")) }
                    } }, leadingContent = { Checkbox(piece.key in selected, { checked -> selected = if (checked) selected + piece.key else selected - piece.key },
                        enabled = !busy && !piece.conflict, modifier = Modifier.testTag("team-program-select-${piece.key}")) })
                }
                item {
                    Text("已选 ${selected.size} 首（使用下方按钮调整演出顺序）")
                    selected.forEachIndexed { index, key -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${pieces.firstOrNull { it.key == key }?.heading ?: "乐曲已下架：$key"}", Modifier.weight(1f))
                        TextButton(onClick = { selected = selected.toMutableList().apply { val value = removeAt(index); add(index - 1, value) } }, enabled = !busy && index > 0) { Text("上移") }
                        TextButton(onClick = { selected = selected - key }, enabled = !busy) { Text("移除") }
                    } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(restricted, { restricted = it }, enabled = !busy); Text("指定可见成员") }
                    if (restricted) members.filter { it.active }.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(member.id in accounts, { checked -> accounts = if (checked) accounts + member.id else accounts - member.id }, enabled = !busy)
                        Text("${member.name} · ${member.username}")
                    } }
                    Text("成员仍只会收到已获授权的声部分谱；选择节目单不会额外授予乐谱权限。")
                }
            }
            if (message.isNotBlank()) Text(message)
            Row {
                TextButton(onClick = close, enabled = !busy) { Text("取消") }
                Button(onClick = { save(original.copy(title = title, pieces = selected, accounts = if (restricted) accounts else null)) },
                    enabled = !busy && title.isNotBlank() && title.length <= 100 && selected.isNotEmpty() && selected.all { key -> pieces.any { it.key == key && !it.conflict } },
                    modifier = Modifier.testTag("team-program-save")) { Text("保存节目单（${selected.size} 首）") }
            }
        } }
    }
}
