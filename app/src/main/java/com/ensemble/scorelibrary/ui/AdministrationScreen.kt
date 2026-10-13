package com.ensemble.scorelibrary.ui

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
    // Capture an immutable request before launching SAF, rather than reading a changed member on return.
    var pendingActor by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTitle by rememberSaveable { mutableStateOf("") }
    var pendingPart by rememberSaveable { mutableStateOf("") }
    var pendingMember by rememberSaveable { mutableStateOf("") }
    var pendingStart by rememberSaveable { mutableStateOf(1) }
    var pendingEnd by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingConcert by rememberSaveable { mutableStateOf("") }
    var pendingGrantAdministrator by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingActor
        if (uri != null && pending != null && pending == actor && canManage) {
            vm.importScore(pending, uri, ImportScoreRequest(pendingTitle, pendingPart, pendingMember,
                pendingStart, pendingEnd, pendingConcert, pendingGrantAdministrator))
        }
        pendingActor = null
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
            Button(onClick = {
                pendingActor = actor; pendingTitle = title; pendingPart = part
                pendingMember = requireNotNull(target).id; pendingStart = requireNotNull(start.toIntOrNull())
                pendingEnd = end.toIntOrNull(); pendingConcert = concert
                pendingGrantAdministrator = grantAdministrator
                picker.launch(arrayOf("application/pdf"))
            }, enabled = !state.busy && pendingActor == null && title.isNotBlank() && part.isNotBlank() && target != null &&
                (start.toIntOrNull() ?: 0) > 0 && (end.isBlank() || (end.toIntOrNull() ?: 0) >= (start.toIntOrNull() ?: 1))) {
                Icon(Icons.Outlined.FileOpen, null); Text("选择 PDF 并导入")
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    }
}
