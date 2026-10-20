package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber

import android.provider.OpenableColumns
import android.content.Context
import com.ensemble.scorelibrary.domain.OrganizerRules
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.diagnostics.*

val LocalTeamPublisher = staticCompositionLocalOf<TeamPublishViewModel?> { null }

@Composable
fun TeamPublishEntry(account: TeamAccountViewModel, compact: Boolean = false) {
    val operations=LocalTestOperations.current
    val accountState by account.state.collectAsStateWithLifecycle()
    val principal = accountState.principal ?: return
    val vm = LocalTeamPublisher.current ?: return
    if (principal.role != "admin") return
    var show by remember(principal.accountId) { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = Modifier.testTag("team-upload-entry")) { Text(if (compact) "上传乐谱" else "上传乐谱到乐团") }
    if (show) {
        val state by vm.state.collectAsStateWithLifecycle()
        val expectedKey = teamId(accountState.server + "/" + principal.teamId + "/" + principal.accountId)
        var comparison by remember(expectedKey) { mutableStateOf<Triple<Int, ExistingTeamScore, List<TeamPublishDetails>>?>(null) }
        val visible = state.takeIf { it.key == expectedKey } ?: TeamPublishState(busy = true)
        val context = LocalContext.current
        var pickerOwner by remember { mutableStateOf<String?>(null) }
        var pickerError by remember { mutableStateOf("") }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if(uris.isEmpty() && pickerOwner==account.state.value.principal?.accountId)
                operations?.record(TestEvent.UPLOAD_SELECT,TestResult.CANCELLED)
            if (uris.isNotEmpty() && pickerOwner == account.state.value.principal?.accountId) {
                val selected = uris.map { uri ->
                val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } }.getOrNull() ?: "未命名.pdf"
                uri to name
                }
                vm.select(selected)
            }
            pickerOwner = null
        }
        Dialog(onDismissRequest = { show = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("上传管理员总库", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { show = false; account.sync() }, modifier = Modifier.testTag("team-upload-back")) { Text("返回曲库") }
                    }
                    if (pickerError.isNotEmpty()) Text(pickerError)
                    if (state.busy) Text("可以返回；已开始的任务继续处理，再次打开可查看结果。", style = MaterialTheme.typography.bodySmall)
                    TeamPublishForm(visible, principal, accountState.members, onChoose = {
                        pickerOwner = principal.accountId
                        try { picker.launch(arrayOf("application/pdf")); pickerError = "" }
                        catch (_: android.content.ActivityNotFoundException) { pickerError = "没有可用的文件选择器，请检查系统文件应用"; pickerOwner = null }
                    }, onPublish = vm::publish, onCancel = vm::cancel, onDiscard = vm::discard, onCompare = { index, match, planned -> comparison = Triple(index, match, planned) })
                }
            }
        }
        comparison?.let { choice -> key(expectedKey, choice) {
            UploadComparisonDialog(load = { vm.comparisonFiles(choice.first, choice.second, choice.third) }, onDismiss = { comparison = null })
        } }
    }
}

@Composable
internal fun TeamPublishForm(state: TeamPublishState, principal: TeamPrincipal, members: List<TeamMember>,
    onChoose: () -> Unit, onPublish: (List<TeamPublishDetails>) -> Unit, onCancel: () -> Unit = {}, onDiscard: (Int, List<TeamPublishDetails>) -> Unit = { _, _ -> }, onCompare: (Int, ExistingTeamScore, List<TeamPublishDetails>) -> Unit = { _, _, _ -> }) {
    if (principal.role != "admin") { Text("仅团队管理员可以上传乐谱"); return }
    val operations=LocalTestOperations.current
    val initial = state.batch.map { it.details }.ifEmpty { listOfNotNull(state.job?.details ?: state.suggestion) }
    var drafts by remember(state.file, state.job, state.batch) { mutableStateOf(initial) }
    var libraryOnly by remember(state.file, state.job?.uploadRequest) {
        mutableStateOf(state.job == null || initial.all { (it.recipients - principal.accountId).isEmpty() })
    }
    var index by remember(state.file, state.batch) { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var preview by remember(state.key, state.existing, state.catalogCheck, state.reservedSequences, state.maximumSequence) { mutableStateOf<List<TeamPublishDetails>?>(null) }
    var cancelPending by remember { mutableStateOf(false) }
    var discardPending by remember(state.batch) { mutableStateOf<Int?>(null) }
    val locked = state.busy || state.job != null
    val context = LocalContext.current
    val rules = remember(context) { runCatching {
        OrganizerRules.read(context.getSharedPreferences("organizer-naming", Context.MODE_PRIVATE).getString("rules", null)
            ?: context.assets.open("score-organizer-rules.json").bufferedReader().use { it.readText() })
    }.getOrNull() }
    fun rowKey(i: Int) = state.batch.getOrNull(i)?.file?.path ?: "${state.file?.path}/$i"
    var manual by remember(state.key) { mutableStateOf(emptySet<String>()) }
    var distinct by remember(state.key) { mutableStateOf(emptySet<String>()) }
    var confirmed by remember(state.file, drafts, manual, distinct, state.existing, state.catalogCheck, state.reservedSequences, state.maximumSequence) { mutableStateOf(false) }
    var validationMessage by remember(state.file, drafts, confirmed, manual, distinct, state.existing, state.catalogCheck, state.reservedSequences, state.maximumSequence) { mutableStateOf("") }
    val plan = if (state.job != null) UploadNumberPlan(drafts, emptyList()) else rules?.let {
        planUploadNumbers(drafts, state.existing, it, drafts.indices.filter { rowKey(it) in manual }.toSet(), drafts.indices.filter { rowKey(it) in distinct }.toSet(), state.reservedSequences, state.maximumSequence)
    } ?: UploadNumberPlan(drafts, drafts.indices.map { UploadNumberIssue(it, UploadIssueKind.METADATA, "整理规则无法读取，请重新加载规则后上传") })
    val details = drafts.getOrNull(index)
    fun change(value: TeamPublishDetails) {
        if (details?.title != value.title || details?.part != value.part || details?.category != value.category) distinct = distinct - rowKey(index)
        drafts = drafts.mapIndexed { i, row -> if (i == index) value else row }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("默认先保存到管理员总库，之后再分发。不限总份数，自动分批上传；原 PDF 和本机曲库保持原样。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(libraryOnly, { checked ->
                libraryOnly = checked
                drafts = drafts.map { it.copy(recipients = setOf(principal.accountId)) }
                preview = null
            }, enabled = !locked, modifier = Modifier.testTag("team-publish-library-only"))
            Text("仅上传管理员总库（之后再分发）")
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotBlank()) Text(state.message, Modifier.testTag("team-publish-status"))
        Button(onClick = { operations?.record(TestEvent.UPLOAD_SELECT,TestResult.START);onChoose() }, enabled = !state.busy && !state.blocked && (state.job == null || state.job.finished), modifier = Modifier.testTag("team-choose-pdf")) {
            Text(if (state.job?.finished == true) "继续多选 PDF" else "选择 PDF（可多选）")
        }
        if (details != null) {
            if (state.batch.size > 1) {
                Text("本批 ${state.batch.size} 份，点击文件逐份核对；同一曲目序号归为一首乐曲")
                val first = (index / 10) * 10
                if (state.batch.size > 10) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { index = maxOf(0, first - 10) }, enabled = !state.busy && first > 0,
                        modifier = Modifier.testTag("team-publish-files-previous")) { Text("上一组") }
                    Text("${first + 1}–${minOf(first + 10, state.batch.size)} / ${state.batch.size}")
                    TextButton(onClick = { index = first + 10 }, enabled = !state.busy && first + 10 < state.batch.size,
                        modifier = Modifier.testTag("team-publish-files-next")) { Text("下一组") }
                }
                (first until minOf(first + 10, state.batch.size)).forEach { i -> val row = state.batch[i]
                    TextButton(onClick = { index = i }, enabled = !state.busy,
                    modifier = Modifier.testTag("team-publish-file-$i")) { Text("${if (i == index) "● " else ""}${i + 1}. ${row.name}") } }
            }
            val shown = state.batch.getOrNull(index)
            Text("${shown?.name ?: state.name} · ${shown?.pages ?: state.pages} 页")
            val related = state.existing.filter { runCatching { ScoreNumber.parse(details.number).sequence == ScoreNumber.parse(it.number).sequence }.getOrDefault(false) }
            if (related.isNotEmpty()) {
                Text("编号 ${runCatching { ScoreNumber.parse(details.number).sequence }.getOrDefault("")} 已有 ${related.size} 个声部：${related.joinToString("、") { it.part }}。可继续补传其他乐器。")
                if (related.any { it.number == details.number }) Text("当前完整编号已存在，请核对乐器编号，原分谱会保留。", color = MaterialTheme.colorScheme.error)
                val existing = related.first()
                if (related.map { it.title to it.category }.distinct().size == 1 &&
                    ScoreNumber.parse(existing.number).category == ScoreNumber.parse(details.number).category &&
                    (existing.title != details.title || existing.category != details.category)) {
                    Text("已有曲名：${existing.title}；分类：${existing.category}")
                    TextButton(onClick = { drafts = drafts.map { row ->
                        if (row.number.length == 7 && row.number.substring(2) == details.number.substring(2))
                            row.copy(title = existing.title, category = existing.category) else row
                    } }, enabled = !locked, modifier = Modifier.testTag("team-publish-use-existing")) { Text("确认同曲，沿用已有曲名与分类") }
                }
            }
            if (state.job == null && state.batch.isNotEmpty()) TextButton(onClick = { discardPending = index }, enabled = !locked,
                modifier = Modifier.testTag("team-publish-discard")) { Text("取消本份上传") }
            val total = state.batch.sumOf { it.size }.takeIf { it > 0 } ?: state.size
            if (state.sent > 0 && state.busy) Text("已上传 ${state.sent / 1024} / ${total / 1024} KB")
            OutlinedTextField(details.title, { change(details.copy(title = it)) }, label = { Text("曲名") }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("team-publish-title"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rowKey(index) in manual, { checked -> manual = if (checked) manual + rowKey(index) else manual - rowKey(index) },
                    enabled = !locked, modifier = Modifier.testTag("team-publish-manual-number"))
                Text("手动安排特殊编号")
            }
            OutlinedTextField(if (rowKey(index) in manual) details.number else plan.rows.getOrNull(index)?.number.orEmpty(),
                { change(details.copy(number = it)) }, label = { Text(if (rowKey(index) in manual) "完整特殊编号" else "自动安排编号") },
                readOnly = rowKey(index) !in manual, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("team-publish-number"))
            Text("默认按曲库全局流水安排；同曲补声部沿用原号，发布前请核对预览。")
            plan.issues.filter { it.index == index }.forEach { issue ->
                Text(issue.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("team-publish-number-issue"))
                if (issue.kind == UploadIssueKind.SIMILAR || issue.kind == UploadIssueKind.AMBIGUOUS) {
                    issue.matches.forEach { match ->
                        TextButton(onClick = {
                            val part = plan.rows.getOrNull(index)?.number?.take(2)?.takeIf { it.length == 2 }
                                ?: details.part.trim().take(2).takeIf { it in rules?.instruments.orEmpty() }
                            if (part != null) {
                                change(details.copy(title = match.title, category = match.category, number = part + match.number.substring(2)))
                                manual = manual + rowKey(index)
                            }
                        }, enabled = !locked && !match.removed, modifier = Modifier.testTag("team-publish-match-${match.number}")) {
                            Text("确认同曲：${ScoreNumber.parse(match.number).sequence} ${match.title} · ${match.part}")
                        }
                    }
                    if (issue.kind == UploadIssueKind.SIMILAR) TextButton(onClick = { distinct = distinct + rowKey(index) }, enabled = !locked,
                        modifier = Modifier.testTag("team-publish-distinct")) { Text("确认为独立新曲，安排新编号") }
                }
                if (issue.kind == UploadIssueKind.DUPLICATE || issue.kind == UploadIssueKind.AMBIGUOUS) {
                    issue.matches.filter { !it.removed }.forEach { match ->
                        TextButton(onClick = { onCompare(index, match, plan.rows) }, enabled = !locked,
                            modifier = Modifier.testTag("team-publish-compare-${match.number}")) { Text("对比PDF：${match.number} ${match.title} · ${match.part}") }
                    }
                    TextButton(onClick = {
                        val key = uploadTitleKey(details.title)
                        val title = nextUploadVersionTitle(details.title, state.existing + drafts.map { ExistingTeamScore(it.number, it.title, it.category, it.part, false) })
                        val affected = drafts.indices.filter { uploadTitleKey(drafts[it].title) == key && drafts[it].category == details.category }.toSet()
                        drafts = drafts.mapIndexed { i, row -> if (i in affected) row.copy(title = title) else row }
                        manual = manual - affected.map(::rowKey).toSet()
                        distinct = distinct + affected.map(::rowKey).toSet()
                    }, enabled = !locked, modifier = Modifier.testTag("team-publish-new-version")) { Text("明确新建下一版，为本批同曲安排新编号") }
                }
            }
            OutlinedTextField(details.part, { change(details.copy(part = it)) }, label = { Text("乐器／声部") }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("team-publish-part"))
            OutlinedTextField(details.category, { change(details.copy(category = it)) }, label = { Text("分类") }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("team-publish-category"))
            if (libraryOnly) Text("仅上传管理员本人可阅读；普通成员不会自动获得乐谱，之后由管理员分发。")
            else {
            Text("本份接收成员（同时为当前管理员 ${principal.name} 保留阅读权限）")
            OutlinedTextField(search, { search = it }, label = { Text("搜索成员姓名或账号") }, modifier = Modifier.fillMaxWidth())
            members.filter { it.active && it.id != principal.accountId && (search.isBlank() || it.name.contains(search, true) || it.username.contains(search, true)) }.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(member.id in details.recipients, { checked -> change(details.copy(recipients = if (checked) details.recipients + member.id else details.recipients - member.id)) },
                        enabled = !locked, modifier = Modifier.testTag("team-publish-recipient-${member.id}"))
                    Text("${member.name} · ${member.username}")
                }
            }
            if (drafts.size > 1) TextButton(onClick = { drafts = drafts.map { it.copy(recipients = details.recipients) } }, enabled = !locked) { Text("将本份接收成员应用到本批全部乐谱") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(confirmed, { confirmed = it }, enabled = !locked, modifier = Modifier.testTag("team-publish-confirm"))
                Text(if (libraryOnly) "已逐份核对编号、分类和声部，全部 PDF 均为独立分谱" else "已逐份核对编号、分类与接收成员，全部 PDF 均为独立分谱")
            }
            Button(onClick = {
                val normalized = plan.rows.map { it.copy(title = it.title.trim(), part = it.part.trim(), category = it.category.trim(), number = it.number.trim(),
                    recipients = if (libraryOnly) setOf(principal.accountId) else it.recipients + principal.accountId) }
                val invalid = normalized.mapIndexedNotNull { i, row ->
                    runCatching { row.validate() }.exceptionOrNull()?.let { "第 ${i+1} 份：${it.message ?: "资料未完整填写"}" }
                }
                validationMessage = when {
                    plan.issues.isNotEmpty() -> plan.issues.joinToString("\n") { "第 ${it.index + 1} 份：${it.message}" }
                    invalid.isNotEmpty() -> invalid.joinToString("\n")
                    !confirmed -> "请先勾选下方的逐份核对确认，再预览发布。"
                    else -> ""
                }
                operations?.record(TestEvent.UPLOAD_PREFLIGHT,if(validationMessage.isEmpty()) TestResult.OK else TestResult.BLOCKED,
                    code=if(validationMessage.isEmpty()) TestCode.NONE else TestCode.METADATA_INVALID,count=normalized.size)
                if (validationMessage.isEmpty()) preview = normalized
            }, enabled = !locked, modifier = Modifier.testTag("team-publish-preview")) { Text("预览上传（${drafts.size} 份）") }
            if (validationMessage.isNotBlank()) Text(validationMessage, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("team-publish-validation"))
            if (state.busy) Text(state.message.ifBlank { "正在处理本批乐谱，请稍候…" })
            if (state.job != null && !state.job.finished) Button(onClick = { onPublish(state.batch.map { it.details }) }, enabled = !state.busy,
                modifier = Modifier.testTag("team-publish-retry")) { Text("重试并核验本次发布") }
            if (state.job != null && !state.job.finished) TextButton(onClick = { cancelPending = true }, enabled = !state.busy) { Text("取消尚未入库部分") }
        }
    }
    preview?.let { batch -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("确认上传 ${batch.size} 份到 ${principal.teamName}") },
        text = { LazyColumn(Modifier.heightIn(max = 450.dp)) { itemsIndexed(batch) { _, d ->
            Text("${d.number} · ${d.title}\n${d.part}\n${d.category}\n接收：" + d.recipients.sorted().joinToString("、") { id ->
                if (id == principal.accountId) principal.name else members.firstOrNull { it.id == id }?.let { "${it.name}（${it.username}）" } ?: "成员不可用"
            }); HorizontalDivider()
        } } }, confirmButton = { TextButton(onClick = { preview = null; onPublish(batch) }, modifier = Modifier.testTag("team-publish-submit")) { Text(if (libraryOnly) "确认上传管理员总库" else "确认上传并分发") } },
        dismissButton = { TextButton(onClick = { preview = null }) { Text("返回修改") } }) }
    discardPending?.let { discardIndex -> AlertDialog(onDismissRequest = { discardPending = null },
        title = { Text("取消本份上传") }, text = { Text("仅清理本次选择的上传缓存，原PDF、库内已有乐谱和本批其余编辑保留。") },
        confirmButton = { TextButton(onClick = { discardPending = null; onDiscard(discardIndex, drafts) },
            modifier = Modifier.testTag("team-publish-discard-confirm")) { Text("确认取消本份") } },
        dismissButton = { TextButton(onClick = { discardPending = null }) { Text("继续保留") } }) }
    if (cancelPending) AlertDialog(onDismissRequest = { cancelPending = false }, title = { Text("取消尚未入库部分") },
        text = { Text("按批次向服务端核验后取消，阻止延迟上传继续入库；已完成的乐谱保留在服务器。回执未确认的部分保留记录供重试核验，原 PDF 不变。") },
        confirmButton = { TextButton(onClick = { cancelPending = false; onCancel() }) { Text("确认取消批次") } },
        dismissButton = { TextButton(onClick = { cancelPending = false }) { Text("继续保留") } })
}
