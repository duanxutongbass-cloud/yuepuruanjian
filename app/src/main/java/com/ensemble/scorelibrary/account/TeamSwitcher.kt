package com.ensemble.scorelibrary.account

import androidx.compose.foundation.layout.*
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

@Composable
internal fun TeamSwitcherEntry(account:TeamAccountViewModel) {
    val state by account.state.collectAsStateWithLifecycle()
    val principal=state.principal ?: return
    if(principal.multiTeamProtocol!=1)return
    var show by remember(principal.accountId) { mutableStateOf(false) }
    LaunchedEffect(principal.teamId) { show=false }
    TextButton(onClick={show=true},modifier=Modifier.testTag("account-teams-entry")) { Text("我的团队") }
    if(show)Dialog(onDismissRequest={show=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            TeamPickerContent(state,account::switchTeam,account::createTeam,account::applyOtherTeam,account::refresh,{show=false})
        }
    }
}

@Composable
internal fun TeamPickerContent(state:TeamAccountState,switch:(String)->Unit,create:(String)->Unit,
    apply:(String)->Unit,refresh:()->Unit,close:()->Unit) {
    val p=state.principal ?: return
    var code by remember(p.accountId) { mutableStateOf("") }
    var name by remember(p.accountId) { mutableStateOf("") }
    var selected by remember(p.accountId,p.teamId) { mutableStateOf<AccountTeam?>(null) }
    var createName by remember(p.accountId) { mutableStateOf<String?>(null) }
    val enabled=!state.busy && !state.offline
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).testTag("account-teams-panel")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("我的团队",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            TextButton(close,modifier=Modifier.testTag("account-teams-close")){Text("返回曲库")}
        }
        if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("可以返回；已提交操作继续处理，再次打开可查看结果。") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("你是个人空间管理员。可以创建自己的团队，也可以加入多个团队；每个团队的角色和乐谱授权分别管理。")
            if(state.message.isNotBlank())Text(state.message,Modifier.testTag("account-teams-status"))
            if(state.offline)Text("切换和管理团队需要联网；当前已核验的曲库仍可按原离线期限阅读。")
            TextButton(refresh,enabled=enabled,modifier=Modifier.testTag("account-teams-refresh")){Text("刷新团队及审核状态")}
            val visibleTeams=state.teams.filter { it.membership!="unjoined" }
            if(visibleTeams.isEmpty())Text("尚未创建或加入团队。可直接创建自己的团队，也可填写乐团码申请加入。")
            visibleTeams.forEach { team ->
                ListItem(headlineContent={Text(team.name)},supportingContent={Text(when {
                    !team.active->"本团队已停用"
                    team.membership=="pending"->"等待本团队管理员审核"
                    team.membership=="rejected"->"申请未通过"
                    !team.joined->"尚未加入"
                    team.role=="admin"->"管理员"
                    else->"成员"
                })},trailingContent={
                    if(team.id==p.teamId)Text("当前团队",Modifier.testTag("account-team-current-${team.id}"))
                    else TextButton({selected=team},enabled=enabled && team.active,
                        modifier=Modifier.testTag("account-team-switch-${team.id}")){Text("进入")}
                })
            }
            HorizontalDivider()
            Text("加入另一个团队",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(code,{if(it.length<=128)code=it},enabled=enabled,label={Text("乐团码")},
                modifier=Modifier.fillMaxWidth().testTag("account-team-code"),singleLine=true)
            Button({apply(code.trim())},enabled=enabled && code.isNotBlank(),modifier=Modifier.testTag("account-team-apply")){Text("申请加入")}
            Text("申请通过后，仍需该团队管理员分配乐谱。已有团队保持。",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("创建团队",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(name,{if(it.length<=100)name=it},enabled=enabled,label={Text("团队名称")},
                modifier=Modifier.fillMaxWidth().testTag("account-team-name"),singleLine=true)
            Button({createName=name.trim()},enabled=enabled && name.isNotBlank() && name.none { it.isISOControl() },
                modifier=Modifier.testTag("account-team-create")){Text("创建前确认")}
        }
    }
    selected?.let { chosen ->
        val valid=state.teams.any { it==chosen && it.active }
        AlertDialog(onDismissRequest={if(!state.busy)selected=null},title={Text("切换团队")},
            text={Text("进入「${chosen.name}」。当前阅读和演出将结束，个人文件与各团队已保存的内容保留。")},
            confirmButton={TextButton({selected=null;switch(chosen.id)},enabled=enabled && valid,
                modifier=Modifier.testTag("account-team-switch-confirm")){Text("确认切换")}},
            dismissButton={TextButton({selected=null},enabled=!state.busy){Text("取消")}})
    }
    createName?.let { title ->
        var confirmed by remember(title) { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!state.busy)createName=null},title={Text("创建「$title」")},
            text={Column { Text("你将成为新团队的管理员，可生成乐团码并审核成员。已有团队和个人文件保持。")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(confirmed,{confirmed=it},enabled=enabled,modifier=Modifier.testTag("account-team-create-consent"))
                    Text("我已核对团队名称")
                }
            }},confirmButton={TextButton({createName=null;create(title)},enabled=enabled && confirmed,
                modifier=Modifier.testTag("account-team-create-confirm")){Text("确认创建")}},
            dismissButton={TextButton({createName=null},enabled=!state.busy){Text("取消")}})
    }
}
