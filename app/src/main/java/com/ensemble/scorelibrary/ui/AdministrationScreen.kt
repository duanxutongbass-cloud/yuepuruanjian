package com.ensemble.scorelibrary.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.data.ImportScoreRequest
import com.ensemble.scorelibrary.domain.Dashboard

val LocalAdministrationViewModel = staticCompositionLocalOf<AdministrationViewModel?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdministrationScreen(dashboard: Dashboard, vm: AdministrationViewModel, initialTab: Int = 0,
    initialId: String? = null, createRecord: Boolean = false, contextOnly: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var part by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("1") }
    var end by rememberSaveable { mutableStateOf("") }
    var concert by rememberSaveable { mutableStateOf("") }
    var recipient by rememberSaveable { mutableStateOf<String?>(null) }
    var grantAdministrator by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val actor = dashboard.profile?.id
    val canManage = dashboard.profile?.canManage == true
    val management=LocalManagement.current
    val managementState=management?.state?.collectAsStateWithLifecycle()?.value
    var tab by rememberSaveable(actor) { mutableIntStateOf(initialTab) }
    var initialEntry by rememberSaveable(actor) { mutableStateOf(true) }
    LaunchedEffect(actor,canManage){management?.bind(actor.takeIf{canManage})}
    val target = dashboard.members.firstOrNull { it.id == recipient }
        ?: dashboard.members.firstOrNull { it.id == actor }
    // Choosing a file does not require completed metadata or publish an asset.
    // Both the request and selection belong to the current administrator.
    var pendingActor by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    var selectedUri by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable(actor, canManage) { mutableStateOf("") }
    var automaticTitle by rememberSaveable(actor, canManage) { mutableStateOf("") }
    var pickerMessage by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingActor
        if (uri != null && pending != null && pending == actor && canManage) {
            selectedUri = uri.toString()
            selectedName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "已选择 PDF" }
            val suggestedTitle = selectedName.replace(Regex("(?i)\\.pdf$"), "").take(200)
            if (title.isBlank() || title == automaticTitle) title = suggestedTitle
            automaticTitle = suggestedTitle
            pickerMessage = null
        }
        pendingActor = null
    }
    val importHint = when {
        selectedUri == null -> "请先选择 PDF，再填写或确认曲目和声部信息。"
        title.isBlank() -> "请填写曲目名称。"
        title.trim().length > 200 -> "曲目名称最多 200 字。"
        part.isBlank() -> "请填写声部名称。"
        part.trim().length > 100 -> "声部名称最多 100 字。"
        target == null -> "请选择授权成员。"
        (start.toIntOrNull() ?: 0) <= 0 -> "起始页须为大于 0 的整数。"
        end.isNotBlank() && (end.toIntOrNull() ?: 0) < (start.toIntOrNull() ?: 1) -> "结束页须为空，或为不小于起始页的整数。"
        concert.trim().length > 200 -> "节目单名称最多 200 字。"
        else -> null
    }
    val bootstrap = dashboard.members.isEmpty()
    Column(Modifier.fillMaxSize()) {
    if(canManage && management!=null) {
        if (!contextOnly) PrimaryScrollableTabRow(tab) { managementTabs.forEachIndexed{index,label->Tab(tab==index,{tab=index;initialEntry=false},
            enabled=!state.busy && managementState?.busy!=true,text={Text(label)},modifier=Modifier.testTag("manage-tab-$index"))} }
        if(tab>0) { ManagementConsole(requireNotNull(actor),tab,management, initialId.takeIf { initialEntry && tab == initialTab }, createRecord && initialEntry && tab == initialTab); return@Column }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!contextOnly || bootstrap) Text(if (bootstrap) "创建本地管理员" else "管理", style = MaterialTheme.typography.headlineSmall)
        if (!bootstrap && !canManage) {
            Text("请在“我的”中选择管理员")
            return@Column
        }
        if (!contextOnly || bootstrap) {
        OutlinedTextField(name, { name = it }, label = { Text("成员姓名") }, singleLine = true,
            enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.createMember(actor, name) }, enabled = !state.busy && name.isNotBlank()) {
            Icon(Icons.Outlined.PersonAdd, null)
            Text(if (bootstrap) "创建管理员" else "添加成员")
        }
        }
        if (canManage) {
            HorizontalDivider()
            Text("导入曲谱", style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                pendingActor = actor
                pickerMessage = null
                try { picker.launch(arrayOf("application/pdf")) }
                catch (_: ActivityNotFoundException) {
                    pendingActor = null
                    pickerMessage = "无法打开系统文件选择器，请确认设备有可用的文件管理应用。"
                }
            }, enabled = !state.busy && pendingActor == null, modifier = Modifier.testTag("import-select-pdf")) {
                Icon(Icons.Outlined.FileOpen, null); Text(if (selectedUri == null) "选择 PDF" else "重新选择 PDF")
            }
            if (selectedUri != null) Text("已选择：$selectedName", modifier = Modifier.testTag("import-selected-file"))
            pickerMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(title, { title = it }, label = { Text("新曲目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            OutlinedTextField(part, { part = it }, label = { Text("声部名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !state.busy) { Text("授权成员：${target?.name ?: "未选择"}") }
                DropdownMenu(menu, { menu = false }) {
                    dashboard.members.forEach { member -> DropdownMenuItem(text = { Text(member.name) },
                        onClick = { recipient = member.id; menu = false }) }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(grantAdministrator, { grantAdministrator = it }, enabled = !state.busy)
                Text("同时授权当前管理员")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("起始页") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
                OutlinedTextField(end, { end = it }, label = { Text("结束页（空为末页）") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
            }
            OutlinedTextField(concert, { concert = it }, label = { Text("节目单名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            importHint?.let { Text(it, modifier = Modifier.testTag("import-required-fields")) }
            Button(onClick = {
                vm.importScore(requireNotNull(actor), Uri.parse(requireNotNull(selectedUri)),
                    ImportScoreRequest(title, part, requireNotNull(target).id, requireNotNull(start.toIntOrNull()),
                        end.toIntOrNull(), concert, grantAdministrator))
            }, enabled = !state.busy && pendingActor == null && importHint == null,
                modifier = Modifier.testTag("import-confirm")) {
                Text("确认导入")
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    }
}
