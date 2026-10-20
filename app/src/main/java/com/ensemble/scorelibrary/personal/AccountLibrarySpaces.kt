package com.ensemble.scorelibrary.personal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.account.*

/** Selection is owned by the account gate, so a team refresh cannot discard it. */
internal val LocalPersonalLibraryChoice=staticCompositionLocalOf<MutableState<Boolean>?> { null }

@Composable
internal fun AccountLibrarySpaces(state:TeamAccountState,choice:MutableState<Boolean>,
    accountActions:@Composable ()->Unit={},personal:@Composable ()->Unit,team:@Composable ()->Unit,spacesActions:@Composable ()->Unit={}) {
    val p=state.principal ?: return
    val showPersonal=!p.joined || choice.value
    Column(Modifier.fillMaxSize().testTag("account-library-spaces")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("曲库",style=MaterialTheme.typography.titleMedium)
            FilterChip(showPersonal,{choice.value=true},label={Text("个人")},modifier=Modifier.testTag("library-space-personal"))
            if(p.joined)FilterChip(!showPersonal,{choice.value=false},label={Text(p.teamName)},modifier=Modifier.testTag("library-space-team"))
            spacesActions()
        }
        if(showPersonal) {
            accountActions()
            Box(Modifier.weight(1f).fillMaxWidth()) { personal() }
        } else Box(Modifier.weight(1f).fillMaxWidth()) { team() }
    }
}

@Composable
internal fun AccountLibraryContent(account:TeamAccountViewModel,choice:MutableState<Boolean>,team:@Composable ()->Unit) {
    val state by account.state.collectAsStateWithLifecycle()
    val ownerNow=remember(account) { { personalOwner(account.state.value) } }
    AccountLibrarySpaces(state,choice,spacesActions={TeamSwitcherEntry(account)},accountActions={
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${state.principal?.name.orEmpty()} · 个人空间管理员 · 已登录",Modifier.weight(1f).testTag("team-signed-in"))
            TeamAccountEntry(account)
        }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.message.isNotBlank())Text(state.message,Modifier.padding(horizontal=12.dp).testTag("personal-account-feedback"))
        if(state.principal?.joined==false)TextButton(onClick=account::refresh,enabled=!state.busy,
            modifier=Modifier.testTag("team-library-retry")) { Text("刷新账号与团队状态") }
    },personal={PersonalDocumentsPanel(personalOwner(state),ownerNow)},team=team)
}
