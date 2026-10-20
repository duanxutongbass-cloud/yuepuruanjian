package com.ensemble.scorelibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.domain.Dashboard

private data class ProfileShortcut(val key: String, val title: String, val icon: ImageVector, val open: () -> Unit)

@Composable
internal fun CompactProfilePage(dashboard: Dashboard, onMember: (String?) -> Unit,
    onManage: (Int, String?, Boolean) -> Unit) {
    val account = com.ensemble.scorelibrary.account.LocalTeamAccount.current
    val accountState = account?.state?.collectAsStateWithLifecycle()?.value
    val personalOwnerNow:()->com.ensemble.scorelibrary.personal.PersonalOwner? = remember(account) {
        { com.ensemble.scorelibrary.personal.personalOwner(account?.state?.value) }
    }
    val teamMode = com.ensemble.scorelibrary.sync.LocalTeamLibraryMode.current
    val http = com.ensemble.scorelibrary.sync.LocalHttpSession.current
    val personalChoice=com.ensemble.scorelibrary.personal.LocalPersonalLibraryChoice.current
    val personalMode=http==null && accountState?.principal!=null && (personalChoice?.value==true || accountState.principal?.joined==false)
    val updates = com.ensemble.scorelibrary.update.LocalAppUpdates.current
    val archive = com.ensemble.scorelibrary.backup.LocalArchive.current
    val pedal = com.ensemble.scorelibrary.core.pedal.LocalPedal.current
    val administration = LocalAdministrationViewModel.current
    var panel by remember(dashboard.profile?.id, accountState?.principal?.accountId) { mutableStateOf<String?>(null) }
    var accountMenu by remember { mutableStateOf(false) }
    val shortcuts = buildList {
        add(ProfileShortcut("account", "账号与乐团", Icons.Outlined.Person) { panel = "account" })
        if(accountState?.principal!=null && http==null)
            add(ProfileShortcut("personal", "个人文件（仅本机）", Icons.Outlined.Folder) { panel="personal" })
        if (administration != null && (dashboard.profile?.canManage == true || dashboard.members.isEmpty()))
            add(ProfileShortcut("management", if (dashboard.members.isEmpty()) "创建管理员" else "管理中心", Icons.Outlined.Settings) { onManage(0, null, false) })
        if (administration != null && dashboard.profile?.canManage == true)
            add(ProfileShortcut("part-grants", "成员与声部", Icons.Outlined.AdminPanelSettings) { onManage(2, null, false) })
        if (archive != null) add(ProfileShortcut("archive", "备份与恢复", Icons.Outlined.Folder) { panel = "archive" })
        if (pedal != null) add(ProfileShortcut("pedal", "脚踏与外设", Icons.Outlined.Bluetooth) { panel = "pedal" })
        if (updates != null) add(ProfileShortcut("updates", "应用更新", Icons.Outlined.SystemUpdate) { panel = "updates" })
    }
    val selected = shortcuts.firstOrNull { it.key == panel }
    if (selected != null) {
        BackHandler { panel = null }
        Column(Modifier.fillMaxSize().testTag("profile-detail")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { panel = null }, modifier = Modifier.testTag("profile-detail-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回我的")
                }
                Text(selected.title, style = MaterialTheme.typography.titleLarge)
            }
            if(selected.key=="personal") {
                com.ensemble.scorelibrary.personal.PersonalDocumentsPanel(
                    com.ensemble.scorelibrary.personal.personalOwner(accountState),personalOwnerNow,Modifier.weight(1f))
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                when (selected.key) {
                    "updates" -> updates?.let { com.ensemble.scorelibrary.update.AppUpdatePanel(it) }
                    "archive" -> archive?.let { com.ensemble.scorelibrary.backup.ArchivePanel(dashboard, it) }
                    "pedal" -> pedal?.let { com.ensemble.scorelibrary.core.pedal.PedalSettingsScreen(it) }
                    "account" -> {
                        if (http == null) account?.let { com.ensemble.scorelibrary.account.TeamAccountEntry(it) }
                        if (!teamMode && http == null) MemberPicker(dashboard, onMember)
                        if (teamMode && account != null) {
                            if (accountState?.principal?.role == "admin") com.ensemble.scorelibrary.sync.TeamPublishEntry(account)
                            com.ensemble.scorelibrary.sync.TeamReadingPart()
                            if (!accountState?.syncMessage.isNullOrBlank()) Text(accountState?.syncMessage.orEmpty(), Modifier.testTag("team-library-status"))
                            TextButton(onClick = account::sync, enabled = accountState?.busy != true) { Text("同步乐谱／重试") }
                        }
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("profile-overview"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.AccountCircle, null, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(if(personalMode)accountState?.principal?.name.orEmpty() else dashboard.profile?.name ?: "未选择成员", style = MaterialTheme.typography.titleMedium)
                    Text(if(personalMode)"个人空间管理员" else if (teamMode) if (accountState?.principal?.role == "admin") "团队管理员" else "乐团成员"
                        else dashboard.profile?.defaultPartName ?: "本地曲谱库", style = MaterialTheme.typography.bodySmall)
                }
                if (dashboard.profile != null) Box {
                    IconButton(onClick = { accountMenu = true }, modifier = Modifier.testTag("profile-account-menu")) { Icon(Icons.Outlined.MoreVert, "账号菜单") }
                    DropdownMenu(accountMenu, { accountMenu = false }) {
                        DropdownMenuItem(text = { Text("关闭菜单") }, onClick = { accountMenu = false })
                        DropdownMenuItem(text = { Text(if (teamMode) "退出团队账号" else "退出当前成员") },
                            enabled = !teamMode || accountState?.busy != true,
                            onClick = { accountMenu = false; onMember(null) })
                    }
                }
            }
        }
        if(http==null && accountState?.principal?.multiTeamProtocol==1)account?.let { com.ensemble.scorelibrary.account.TeamSwitcherEntry(it) }
        Text("功能与设置", style = MaterialTheme.typography.titleSmall)
        ProfileShortcutGrid(shortcuts.map { Triple(it.key, it.title, it.icon) }) { key -> shortcuts.first { it.key == key }.open() }
    }
}

@Composable
internal fun ProfileShortcutGrid(items: List<Triple<String, String, ImageVector>>, open: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 840.dp) 4 else if (maxWidth >= 600.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (key, title, icon) ->
                        OutlinedCard(onClick = { open(key) }, modifier = Modifier.weight(1f).heightIn(min = 92.dp).testTag("profile-$key")) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

