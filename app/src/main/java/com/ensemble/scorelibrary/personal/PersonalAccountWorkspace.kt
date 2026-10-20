package com.ensemble.scorelibrary.personal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.account.TeamAccountEntry
import com.ensemble.scorelibrary.account.TeamAccountViewModel
import com.ensemble.scorelibrary.account.TeamSwitcherEntry
import com.ensemble.scorelibrary.ui.RootPage
import com.ensemble.scorelibrary.ui.WorkspaceNavigation

/** Account navigation remains available before joining a team, without opening the local/team Domain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalAccountWorkspace(account:TeamAccountViewModel,choice:MutableState<Boolean>) {
    val state by account.state.collectAsStateWithLifecycle()
    val principal=state.principal ?: return
    var selected by rememberSaveable(principal.accountId) { mutableStateOf(RootPage.Library.route) }
    var detail by rememberSaveable(principal.accountId) { mutableStateOf<String?>(null) }
    val page=RootPage.entries.firstOrNull { it.route==selected && it!=RootPage.Admin } ?: RootPage.Library
    val updates=com.ensemble.scorelibrary.update.LocalAppUpdates.current
    val pedal=com.ensemble.scorelibrary.core.pedal.LocalPedal.current
    val navigate:(RootPage)->Unit={selected=it.route;detail=null}
    BackHandler(page!=RootPage.Library || detail!=null) {
        if(detail!=null)detail=null else navigate(RootPage.Library)
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().testTag("personal-account-workspace")) {
        val side=maxWidth>=600.dp
        Row(Modifier.fillMaxSize()) {
            if(side)WorkspaceNavigation(page,navigate)
            Scaffold(Modifier.weight(1f),containerColor=MaterialTheme.colorScheme.background,topBar={
                Column {
                    TopAppBar(title={Text("重奏团 · ${page.label}")},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),
                        windowInsets=WindowInsets(0,0,0,0))
                    updates?.let {com.ensemble.scorelibrary.update.AppUpdateNotice(it)}
                }
            },bottomBar={
                if(!side)NavigationBar(Modifier.testTag("bottom-navigation"),windowInsets=WindowInsets(0,0,0,0)) {
                    RootPage.entries.filterNot {it==RootPage.Admin}.forEach { destination ->
                        NavigationBarItem(selected=destination==page,onClick={navigate(destination)},
                            icon={Icon(destination.icon,destination.label)},label={Text(destination.label)},
                            modifier=Modifier.testTag("nav-${destination.route}"))
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when(page) {
                        RootPage.Library->AccountLibraryContent(account,choice) {
                            error("A personal-only account cannot compose a team library")
                        }
                        RootPage.Concerts->Column(Modifier.fillMaxSize().padding(20.dp).testTag("personal-concerts-empty"),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("节目单",style=MaterialTheme.typography.headlineSmall)
                            Text("个人文件可直接在曲库导入和阅读。团队节目单会在进入已加入的团队后显示。")
                            TeamSwitcherEntry(account)
                        }
                        RootPage.Profile->Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                            .testTag("personal-account-profile"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(detail!=null)TextButton(onClick={detail=null},modifier=Modifier.testTag("personal-profile-back")) {Text("返回我的")}
                            when(detail) {
                                "updates"->updates?.let {com.ensemble.scorelibrary.update.AppUpdatePanel(it)}
                                "pedal"->pedal?.let {com.ensemble.scorelibrary.core.pedal.PedalSettingsScreen(it)}
                                else->{
                                    Text("我的",style=MaterialTheme.typography.headlineSmall)
                                    Text(principal.name,style=MaterialTheme.typography.titleLarge)
                                    Text("个人空间管理员")
                                    Text("个人空间现在即可使用。可创建自己的团队，也可申请加入其他团队；各团队角色与乐谱授权分别管理。")
                                    TeamAccountEntry(account)
                                    TeamSwitcherEntry(account)
                                    updates?.let {TextButton(onClick={detail="updates"},modifier=Modifier.testTag("personal-profile-updates")) {Text("应用更新")}}
                                    pedal?.let {TextButton(onClick={detail="pedal"},modifier=Modifier.testTag("personal-profile-pedal")) {Text("脚踏与外设")}}
                                    TextButton(onClick={navigate(RootPage.Library)},modifier=Modifier.testTag("personal-profile-library")) {Text("进入个人曲库")}
                                }
                            }
                        }
                        RootPage.Admin->error("Team administration requires an authorized team")
                    }
                }
            }
        }
    }
}
