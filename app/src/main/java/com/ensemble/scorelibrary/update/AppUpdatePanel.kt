package com.ensemble.scorelibrary.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ensemble.scorelibrary.BuildConfig

val LocalAppUpdates = staticCompositionLocalOf<AppUpdateViewModel?> { null }
@Composable
fun AppUpdatePanel(vm: AppUpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用更新 · 当前 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
        TemporaryReleaseNotesButton()
        TextButton(onClick = vm::check, enabled = !state.busy, modifier = Modifier.testTag("app-update-check")) { Text("检查新版本") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotEmpty()) Text(state.message, Modifier.testTag("app-update-status"))
        state.release?.let { release ->
            if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
            Text("大小 %.1f MB".format(release.size / 1024.0 / 1024.0), style = MaterialTheme.typography.bodySmall)
            if (state.file == null) Button(onClick = vm::download, enabled = !state.busy, modifier = Modifier.testTag("app-update-download")) { Text("下载新版 ${release.name}") }
            else Button(onClick = vm::install, enabled = !state.busy, modifier = Modifier.testTag("app-update-install")) { Text("安装新版") }
        }
    }
}

/** Temporary testing aid. Disable in the commercial build; independent of update detection. */
@Composable
fun TemporaryReleaseNotesButton() {
    if (!BuildConfig.TEST_RELEASE_NOTES) return
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = Modifier.testTag("release-notes-button")) { Text("版本更新内容 · ${BuildConfig.VERSION_NAME}") }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("版本更新内容（测试期）") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            Text(androidx.compose.ui.platform.LocalContext.current.assets.open("release-notes.txt").bufferedReader().use { it.readText() },
                Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } })
}

@Composable
fun AppUpdateNotice(vm: AppUpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            vm.checkAutomatically()
            kotlinx.coroutines.awaitCancellation()
        }
    }
    UpdateNotice(state, vm::download, vm::install)
}

@Composable
internal fun UpdateNotice(state: AppUpdateState, download: () -> Unit, install: () -> Unit) {
    val release = state.release ?: return
    var show by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("app-update-banner")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("有新版 ${release.name}，请更新测试", Modifier.weight(1f))
            TextButton(onClick = { show = true }) { Text("查看并更新") }
        }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("发现新版 ${release.name}") },
        text = { Column { Text(release.notes); if (state.message.isNotBlank()) Text(state.message); if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) } },
        confirmButton = { TextButton(onClick = if (state.file == null) download else install, enabled = !state.busy) { Text(if (state.file == null) "下载更新" else "安装新版") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("稍后") } })
}
