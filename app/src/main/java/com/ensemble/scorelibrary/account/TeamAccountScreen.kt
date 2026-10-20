package com.ensemble.scorelibrary.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val LocalTeamAccount = staticCompositionLocalOf<TeamAccountViewModel?> { null }

@Composable
fun TeamAccountEntry(vm: TeamAccountViewModel) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = Modifier.testTag("team-account-entry")) { Text("账号与团队") }
    if (show) {
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.open() }
        Dialog(onDismissRequest = { show = false; vm.hideInvitation() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("账号与团队", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { show = false; vm.hideInvitation() }, modifier = Modifier.testTag("team-account-back")) { Text("返回曲库") }
                    }
                    TeamSwitcherEntry(vm)
                    com.ensemble.scorelibrary.diagnostics.TestOperationStatus()
                    if (state.principal?.joined == true) com.ensemble.scorelibrary.sync.TeamDownloadsEntry(vm)
                    Box(Modifier.weight(1f).fillMaxWidth()) { ConnectedAccountScreen(vm, state) }
                }
            }
        }
    }
}

/** The whole library is replaced while identity is unresolved or membership is pending. */
@Composable
internal fun TeamAccountGate(vm: TeamAccountViewModel, state: TeamAccountState) {
    TeamAccountGateContent(vm::leaveAccount) { ConnectedAccountScreen(vm, state) }
}

/** Signed-in accounts stay signed in even when approval or local activation is unavailable. */
@Composable
internal fun TeamLibraryRecovery(vm:TeamAccountViewModel,state:TeamAccountState) {
    val principal=checkNotNull(state.principal)
    Surface(Modifier.fillMaxSize().testTag("team-membership-gate")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("团队曲库",style=MaterialTheme.typography.headlineSmall)
            Text("${principal.name} · 已登录",Modifier.testTag("team-signed-in"))
            Text(when {
                !principal.joined -> when(principal.membership) { "pending" -> "等待管理员审核，请在账号与乐团中查看申请状态"; "rejected" -> "入团申请未通过，可在账号与乐团中重新申请"; else -> "请在账号与乐团中填写乐团码申请加入" }
                state.busy -> "正在加载团队曲库，请稍候"
                else -> "团队曲库尚未加载成功；账号仍保持登录，可以重试。"
            },Modifier.testTag("team-library-recovery-status"))
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.message.isNotBlank())Text(state.message,Modifier.testTag("team-account-status"))
            Button(onClick=vm::refresh,enabled=!state.busy,modifier=Modifier.testTag("team-library-retry")) {
                Text(if(principal.joined) "重试进入团队曲库" else "刷新审核状态")
            }
            TeamAccountEntry(vm)
            TextButton(onClick=vm::leaveAccount,modifier=Modifier.testTag("team-explicit-logout")) { Text("退出账号") }
        }
    }
}

@Composable
internal fun TeamAccountGateContent(leave: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = leave)
    Surface(Modifier.fillMaxSize().testTag("team-membership-gate")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("团队账号", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = leave, modifier = Modifier.testTag("team-gate-back")) {
                    Text("返回本地曲库")
                }
            }
            com.ensemble.scorelibrary.update.LocalAppUpdates.current?.let { com.ensemble.scorelibrary.update.AppUpdateNotice(it) }
            com.ensemble.scorelibrary.update.TemporaryReleaseNotesButton()
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun ConnectedAccountScreen(vm: TeamAccountViewModel, state: TeamAccountState) {
    TeamAccountScreen(state, vm::login, vm::logout, vm::refresh, vm::changePassword,
        vm::revokeDevice, vm::invite, vm::memberStatus, vm::consent, vm::sync,
        vm::signup, vm::applyToTeam, vm::rotateJoinCode, vm::reviewJoin)
}

@Composable
internal fun TeamAccountScreen(state: TeamAccountState,
    onLogin: (String, String, String?, String?, Boolean) -> Unit,
    onLogout: () -> Unit, onRefresh: () -> Unit,
    onPassword: (String, String) -> Unit, onRevoke: (String) -> Unit,
    onInvite: () -> Unit, onMemberStatus: (String, Boolean) -> Unit, onConsent: () -> Unit,
    onSync: () -> Unit = {}, onSignup: (String, String, String, Boolean) -> Unit = { _,_,_,_-> },
    onApply: (String) -> Unit = {}, onRotateCode: (Long) -> Unit = {},
    onReview: (TeamJoinRequest, Boolean) -> Unit = { _,_-> }) {
    var registration by remember { mutableStateOf("login") }
    val register = registration != "login"
    val invited = registration == "invite"
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    var passwordForm by remember(state.principal?.accountId) { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newRepeated by remember { mutableStateOf("") }
    var revoke by remember { mutableStateOf<TeamDevice?>(null) }
    var disable by remember { mutableStateOf<TeamMember?>(null) }
    var joinCode by remember(state.principal?.accountId) { mutableStateOf("") }
    var rotate by remember(state.principal?.accountId) { mutableStateOf<Long?>(null) }
    var review by remember(state.principal?.accountId) { mutableStateOf<Pair<TeamJoinRequest, Boolean>?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val formIssue = accountFormIssue(username,password,repeated,name,register,invited,invitation,accepted)
    LaunchedEffect(state.principal?.accountId, state.principal?.membership) {
        if (state.principal?.membership !in setOf("unjoined", "rejected")) joinCode = ""
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("个人批注按账号独立保存，同账号设备间同步；管理员可只读查看已上传的个人批注。管理员发布的共享批注对获授权成员可见。", style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotEmpty() && state.principal != null) Text(state.message, Modifier.testTag("team-account-status"))
        val principal = state.principal
        if (principal == null) {
            Row {
                TextButton(onClick = { registration = "login"; password = ""; repeated = "" }, enabled = !state.busy) { Text("登录") }
                TextButton(onClick = { registration = "public"; password = ""; repeated = ""; accepted = false }, enabled = !state.busy) { Text("注册账号") }
                TextButton(onClick = { registration = "invite"; password = ""; repeated = ""; accepted = false }, enabled = !state.busy) { Text("邀请注册") }
            }
            OutlinedTextField(username, { username = it }, label = { Text("账号名") }, singleLine = true,
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("team-username"))
            Text("账号名为3–64位小写字母、数字、点、下划线或短横线，以字母或数字开头；显示姓名可以使用中文。",style=MaterialTheme.typography.bodySmall)
            PasswordField(password, { password = it }, "密码（12–128 个字符）", "team-password", !state.busy)
            if (register) {
                if (!invited) Text("注册后即可管理自己的个人空间，也可创建团队成为管理员，或加入其他团队按授权阅读乐谱。")
                PasswordField(repeated, { repeated = it }, "再次输入密码", "team-password-repeat", !state.busy)
                OutlinedTextField(name, { name = it }, label = { Text("显示姓名") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("team-name"))
                if (invited) OutlinedTextField(invitation, { invitation = it }, label = { Text("管理员提供的邀请码") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("team-invitation"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accepted, { accepted = it }, enabled = !state.busy, modifier = Modifier.testTag("team-consent"))
                    Text((if (invited) "我已知晓：" else "可选，加入后同意开启：") + "个人批注自动备份到团队 NAS，同团队管理员可以只读查看；其他普通成员不能读取我的个人批注。", Modifier.weight(1f))
                }
            }
            Box(Modifier.fillMaxWidth().testTag("team-auth-feedback")) {
                Text(when {
                    state.busy -> if(register) "正在注册并关联当前设备，请稍候；请勿重复提交。" else "正在登录，请稍候。"
                    state.message.isNotBlank() -> state.message
                    formIssue != null -> formIssue
                    else -> if(register) "资料已填写完整，可以注册。" else "可以登录。"
                },Modifier.testTag("team-account-status").semantics { liveRegion=LiveRegionMode.Polite },
                    color=if(!state.busy && state.message.isNotBlank())MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = {
                focus.clearFocus();keyboard?.hide()
                if (registration == "public") onSignup(username, password, name, accepted)
                else onLogin(username, password, if (register) name else null, if (register) invitation else null, accepted)
                password = ""; repeated = ""; invitation = ""
            }, enabled = !state.busy && formIssue == null, modifier = Modifier.testTag("team-submit")) {
                Text(if(state.busy) { if(register) "正在注册…" else "正在登录…" } else if(register) "注册账号并登录" else "登录账号")
            }
        } else {
            if(principal.multiTeamProtocol==1) {
                Text("${principal.name} · ${principal.username}",style=MaterialTheme.typography.titleLarge)
                Text("个人空间管理员",Modifier.testTag("account-personal-manager"))
                Text(if(principal.joined) "当前团队：${principal.teamName} · ${if(principal.role=="admin")"管理员" else "成员"}"
                    else "可以创建自己的团队，或申请加入其他团队。团队角色与乐谱授权分别管理。")
            } else {
                Text(principal.teamName, style = MaterialTheme.typography.titleLarge)
                Text("${principal.name} · ${principal.username} · ${if (!principal.joined) "待入团账号" else if (principal.role == "admin") "管理员" else "成员"}")
            }
            if (state.offline) Text(if (principal.joined) "当前离线：仅阅读已核验的本机缓存，最长 7 天；联网后先核验账号与乐谱权限。"
                else "当前离线，联网后才能申请或核验审核结果。", Modifier.testTag("team-offline-status"))
            if (state.syncMessage.isNotBlank()) Text(state.syncMessage, Modifier.testTag("team-sync-status"))
            if (!principal.joined) {
                Text(when (principal.membership) { "pending" -> "等待管理员审核"; "rejected" -> "申请未通过"; else -> "尚未加入乐团" },
                    Modifier.testTag("team-membership-status"), style = MaterialTheme.typography.titleMedium)
                if (principal.membership in setOf("unjoined", "rejected") && principal.multiTeamProtocol==0) {
                    OutlinedTextField(joinCode, { joinCode = it.take(128) }, label = { Text("管理员提供的乐团码") }, singleLine = true,
                        enabled = !state.busy && !state.offline, modifier = Modifier.fillMaxWidth().testTag("team-join-code"))
                    Button(onClick = { onApply(joinCode) }, enabled = !state.busy && !state.offline && joinCode.isNotBlank() &&
                        state.membership?.state == principal.membership, modifier = Modifier.testTag("team-join-apply")) { Text("申请加入乐团") }
                }
                Text(if(principal.multiTeamProtocol==1) "在「我的团队」创建或加入团队；个人空间现在即可使用。加入其他团队后仍需管理员为你分配乐谱。"
                    else "审核前不会显示团队曲库或旧账号缓存；审核通过后请刷新，管理员仍需为你分配声部。", style = MaterialTheme.typography.bodySmall)
            } else TextButton(onClick = onSync, enabled = !state.busy, modifier = Modifier.testTag("team-sync")) { Text("同步乐谱／重试") }
            Row {
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新账号状态") }
                TextButton(onClick = onLogout, modifier = Modifier.testTag("team-logout")) { Text("退出／切换账号") }
            }
            TextButton(onClick = { passwordForm = !passwordForm; currentPassword = ""; newPassword = ""; newRepeated = "" }, enabled = !state.busy) { Text("修改密码") }
            if (passwordForm) {
                PasswordField(currentPassword, { currentPassword = it }, "当前密码", "team-old-password", !state.busy)
                PasswordField(newPassword, { newPassword = it }, "新密码（12–128 个字符）", "team-new-password", !state.busy)
                PasswordField(newRepeated, { newRepeated = it }, "再次输入新密码", "team-new-repeat", !state.busy)
                Button(onClick = { onPassword(currentPassword, newPassword); currentPassword = ""; newPassword = ""; newRepeated = "" },
                    enabled = !state.busy && currentPassword.isNotBlank() && newPassword.length in 12..128 && newPassword == newRepeated) { Text("修改并退出所有设备") }
            }
            HorizontalDivider()
            Text("已绑定设备", style = MaterialTheme.typography.titleMedium)
            state.devices.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name + if (device.current) "（当前设备）" else "")
                        Text(if (device.revoked) "已撤销" else "已绑定", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!device.revoked && !device.current) TextButton(onClick = { revoke = device }, enabled = !state.busy) { Text("撤销设备") }
                }
            }
            HorizontalDivider()
            Text(if (principal.backupConsent) "个人批注按账号自动备份；同团队管理员可以只读查看已上传批注，其他成员不可见。" else "尚未确认个人批注 NAS 备份说明，笔迹先保存在本机。", style = MaterialTheme.typography.bodySmall)
            if (!principal.backupConsent) TextButton(onClick = onConsent, enabled = !state.busy) { Text("确认个人批注自动备份说明") }
            if (principal.joined) com.ensemble.scorelibrary.sync.LocalInkTools.current?.let { com.ensemble.scorelibrary.sync.TeamInkPanel(it, state.members) }
            if (principal.joined && principal.role == "admin") {
                HorizontalDivider()
                Text("乐团码与入团审核", style = MaterialTheme.typography.titleMedium)
                state.joinCodeRevision?.let { revision ->
                    Button(onClick = { if (state.joinCodeConfigured) rotate = revision else onRotateCode(revision) },
                        enabled = !state.busy && !state.offline, modifier = Modifier.testTag("team-code-rotate")) {
                        Text(if (state.joinCodeConfigured) "更换乐团码" else "生成乐团码")
                    }
                } ?: Text(if (principal.membershipSupported) "请刷新以加载乐团码与申请" else "NAS 尚未更新自助注册审核服务，可继续使用单次邀请码。")
                state.joinCode?.let { SelectionContainer { Text("请复制保存，关闭后不再显示：\n$it", Modifier.testTag("team-code-created")) } }
                Text("待审核 ${state.joinRequestTotal} 人。批准只允许加入乐团，乐谱需另行分配。")
                if (state.joinRequestTotal > state.joinRequests.size) Text("当前显示最早的 ${state.joinRequests.size} 人，处理后刷新查看其余申请。")
                state.joinRequests.forEach { request ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${request.name} · ${request.username}", Modifier.weight(1f))
                        TextButton(onClick = { review = request to true }, enabled = !state.busy && !state.offline,
                            modifier = Modifier.testTag("team-join-approve-${request.id}")) { Text("批准") }
                        TextButton(onClick = { review = request to false }, enabled = !state.busy && !state.offline,
                            modifier = Modifier.testTag("team-join-reject-${request.id}")) { Text("拒绝") }
                    }
                }
                LocalTeamAccount.current?.let { com.ensemble.scorelibrary.sync.TeamPublishEntry(it) }
                Text("团队成员", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onInvite, enabled = !state.busy, modifier = Modifier.testTag("team-create-invite")) { Text("生成成员邀请码") }
                state.invitation?.let { SelectionContainer { Text("单次邀请码（48 小时有效）：\n$it", Modifier.testTag("team-created-invite")) } }
                state.members.forEach { member ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${member.name} · ${member.username}" + if (!member.active) "（已停用）" else "", Modifier.weight(1f))
                        if (!member.admin) TextButton(onClick = { if (member.active) disable = member else onMemberStatus(member.id, true) }, enabled = !state.busy) {
                            Text(if (member.active) "停用" else "启用")
                        }
                    }
                }
            }
        }
    }
    revoke?.let { device -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text("撤销设备") },
        text = { Text("撤销 ${device.name} 的登录。该设备需要管理员恢复后才能再次登录。") },
        confirmButton = { TextButton(onClick = { revoke = null; onRevoke(device.id) }) { Text("撤销") } },
        dismissButton = { TextButton(onClick = { revoke = null }) { Text("取消") } }) }
    disable?.let { member -> AlertDialog(onDismissRequest = { disable = null }, title = { Text("停用成员") },
        text = { Text("停用 ${member.name}，并使其所有设备的团队登录失效。") },
        confirmButton = { TextButton(onClick = { disable = null; onMemberStatus(member.id, false) }) { Text("停用") } },
        dismissButton = { TextButton(onClick = { disable = null }) { Text("取消") } }) }
    rotate?.let { revision -> AlertDialog(onDismissRequest = { rotate = null }, title = { Text("更换乐团码") },
        text = { Text("旧乐团码将失效，已有申请仍需逐一审核。更换后请复制新码。") },
        confirmButton = { TextButton(onClick = { rotate = null; onRotateCode(revision) }, enabled = !state.busy && !state.offline,
            modifier = Modifier.testTag("team-code-confirm")) { Text("确认更换") } },
        dismissButton = { TextButton(onClick = { rotate = null }) { Text("取消") } }) }
    review?.let { (request, approved) -> AlertDialog(onDismissRequest = { review = null }, title = { Text(if (approved) "批准入团" else "拒绝入团申请") },
        text = { Text("${request.name} · ${request.username}\n" + if (approved) "批准后成为普通成员，仍需单独分配乐谱，不增加管理员权限。" else "拒绝后仍不可访问团队资料，可以重新申请。") },
        confirmButton = { TextButton(onClick = { review = null; onReview(request, approved) }, enabled = !state.busy && !state.offline,
            modifier = Modifier.testTag("team-review-confirm")) { Text(if (approved) "确认批准" else "确认拒绝") } },
        dismissButton = { TextButton(onClick = { review = null }) { Text("取消") } }) }
}

internal fun accountFormIssue(username:String,password:String,repeated:String,name:String,register:Boolean,
    invited:Boolean,invitation:String,consent:Boolean):String? = when {
    username.isBlank() -> "请填写账号名。"
    !username.trim().matches(Regex("[a-z0-9][a-z0-9._-]{2,63}")) -> "账号名须为3–64位小写字母、数字、点、下划线或短横线，并以字母或数字开头。"
    password.length !in 12..128 -> "密码须为12–128个字符；当前已输入${password.length}个。"
    register && repeated != password -> "两次输入的密码不一致，请核对。"
    register && (name.trim().length !in 1..100 || name.trim().any(Char::isISOControl)) -> "请填写1–100字的显示姓名。"
    invited && invitation.isBlank() -> "请填写管理员提供的邀请码。"
    invited && !consent -> "请先阅读并勾选邀请注册的备份说明。"
    else -> null
}

@Composable
private fun PasswordField(value: String, change: (String) -> Unit, label: String, tag: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag))
}
