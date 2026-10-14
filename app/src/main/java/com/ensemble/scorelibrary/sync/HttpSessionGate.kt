package com.ensemble.scorelibrary.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.ui.LibraryViewModel

val LocalHttpSession = staticCompositionLocalOf<HttpSyncViewModel?> { null }

@Composable
fun HttpSessionGate(vm: HttpSyncViewModel, library: LibraryViewModel, content: @Composable () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by library.state.collectAsStateWithLifecycle()
    val boundMember by library.selectedMember.collectAsStateWithLifecycle()
    val reading = screen.memberId == state.member && screen.request is com.ensemble.scorelibrary.domain.ScreenRequest.Viewer
    var account by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var panel by remember(state.member) { mutableStateOf(true) }
    LaunchedEffect(state.member, state.revision) { library.selectMember(state.member); library.retry() }
    if (state.member == null) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.widthIn(max = 580.dp).padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                Text("临时联网测试", style = MaterialTheme.typography.headlineMedium)
                Text("登录 → 本人授权分谱 → 下载 → 离线阅读", Modifier.padding(vertical = 16.dp))
                OutlinedTextField(account, { account = it }, label = { Text("测试账号") }, singleLine = true, modifier = Modifier.testTag("http-account"))
                OutlinedTextField(code, { code = it }, label = { Text("本次服务登录码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.testTag("http-code"))
                Button(onClick = { vm.login(account.trim(), code); code = "" }, enabled = !state.busy && account.isNotBlank() && code.isNotBlank(), modifier = Modifier.testTag("http-login")) { Text("登录测试服务") }
                Text(state.message, Modifier.testTag("http-status"))
                Text("独立测试包与合成资料，不修改已有曲库。进程退出后需要重新在线登录。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    } else CompositionLocalProvider(LocalHttpSession provides vm) {
        Column(Modifier.fillMaxSize()) {
            if (!reading) Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.name + " · 临时会话", Modifier.weight(1f))
                        TextButton(onClick = { panel = !panel }, modifier = Modifier.testTag("http-panel")) { Text(if (panel) "收起下载状态" else "下载状态") }
                        // Switching cannot abandon a dirty annotation gesture: the existing profile flow handles reader exit.
                        if (panel) TextButton(onClick = vm::logout, enabled = !state.busy, modifier = Modifier.testTag("http-logout")) { Text("切换账号") }
                    }
                    if (panel) {
                        Text(state.message, Modifier.testTag("http-status"), style = MaterialTheme.typography.bodySmall)
                        state.manifest?.scores?.forEach { Text(it.title + " · " + it.partName, Modifier.testTag("http-authorized-score")) }
                        Row {
                            Button(onClick = vm::download, enabled = !state.busy, modifier = Modifier.testTag("http-download")) { Text("下载本人分谱 / 重试") }
                            TextButton(onClick = vm::refresh, enabled = !state.busy, modifier = Modifier.testTag("http-refresh")) { Text("刷新授权") }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                // Never render one old-account frame while the library ViewModel is rebinding.
                if (boundMember == state.member && screen.memberId == state.member) key(state.member) { content() }
            }
        }
    }
}
