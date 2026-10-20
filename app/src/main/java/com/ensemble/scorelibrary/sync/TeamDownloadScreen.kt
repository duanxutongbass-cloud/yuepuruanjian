package com.ensemble.scorelibrary.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.account.TeamAccountViewModel
import java.util.Locale

@Composable
fun TeamDownloadsEntry(vm:TeamAccountViewModel) {
    val account by vm.state.collectAsStateWithLifecycle()
    val principal=account.principal ?: return
    val expected=teamId("${account.server}/${principal.teamId}/${principal.accountId}")
    var show by remember(expected) { mutableStateOf(false) }
    // Only mounted in library/account pages. Byte progress never changes account/reader state.
    val received by vm.downloads.collectAsStateWithLifecycle()
    val state=received.takeIf { it.key==expected } ?: TeamDownloadState(expected)
    TextButton(onClick={show=true},modifier=Modifier.testTag("team-downloads-entry")) {
        Text("下载管理" + if(state.rows.isEmpty()) "" else "（${state.rows.count { it.phase==DownloadPhase.READY }} / ${state.rows.size}）")
    }
    if(show)Dialog(onDismissRequest={show=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("乐谱下载",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={show=false}) { Text("返回曲库") }
            }
            TeamDownloadPanel(state,account.offline,account.busy,{vm.pauseDownloads(expected)},{vm.resumeDownloads(expected)},Modifier.weight(1f))
        } }
    }
}
internal fun downloadSize(bytes:Long):String = when {
    bytes<1024 -> "$bytes B"
    bytes<1024*1024 -> "%.1f KB".format(Locale.ROOT,bytes/1024.0)
    else -> "%.1f MB".format(Locale.ROOT,bytes/1024.0/1024.0)
}
@Composable
internal fun TeamDownloadPanel(state:TeamDownloadState,offline:Boolean,busy:Boolean,pause:()->Unit,resume:()->Unit,modifier:Modifier=Modifier) {
    var query by remember(state.key) { mutableStateOf("") }
    var unfinished by remember(state.key) { mutableStateOf(false) }
    Column(modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("已入库 ${state.rows.count { it.phase==DownloadPhase.READY }} / ${state.rows.size} 份",Modifier.testTag("download-summary"))
        Text("接收 ${downloadSize(state.rows.sumOf { it.received })} / ${downloadSize(state.rows.sumOf { it.total })}")
        Text("接收完成后还会校验；显示“已入库”才可离线打开。",style=MaterialTheme.typography.bodySmall)
        if(offline)Text("当前离线；联网核验权限后可继续下载。",Modifier.testTag("download-offline"))
        if(state.message.isNotBlank())Text(state.message,Modifier.testTag("download-message"))
        Row(verticalAlignment=Alignment.CenterVertically) {
            if(!state.paused)TextButton(onClick=pause,enabled=!state.committing && (state.active || state.rows.any { it.phase!=DownloadPhase.READY }),
                modifier=Modifier.testTag("download-pause")) { Text("暂停下载") }
            if(state.paused || state.rows.any { it.phase!=DownloadPhase.READY })Button(onClick=resume,
                enabled=!state.active && !busy && !offline,modifier=Modifier.testTag("download-resume")) {
                Text(if(state.paused && state.active) "正在暂停…" else if(state.paused) "继续下载" else "重试未完成")
            }
            FilterChip(unfinished,{unfinished=!unfinished},label={Text("仅未完成")})
        }
        OutlinedTextField(query,{query=it},label={Text("搜索曲名、编号、分类或声部")},singleLine=true,
            modifier=Modifier.fillMaxWidth().testTag("download-search"))
        val rows=state.rows.filter { (!unfinished || it.phase!=DownloadPhase.READY) &&
            (it.title+it.number+it.category+it.part).contains(query.trim(),true) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("download-list")) {
            items(rows,key={it.id}) { row -> Column(Modifier.fillMaxWidth().padding(vertical=10.dp).testTag("download-row-${row.id}")) {
                Text(row.title,style=MaterialTheme.typography.titleMedium)
                Text(listOf(row.number.ifBlank { "无编号" },row.category,row.part).filter { it.isNotBlank() }.joinToString(" · "),
                    style=MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress={if(row.total==0L)0f else row.received.toFloat()/row.total},modifier=Modifier.fillMaxWidth())
                Text("${downloadSize(row.received)} / ${downloadSize(row.total)} · " + when(row.phase) {
                    DownloadPhase.READY -> "已入库"
                    DownloadPhase.STAGED -> "已接收，等待入库"
                    DownloadPhase.VERIFYING -> "正在校验 PDF"
                    DownloadPhase.FAILED -> "失败，可重试"
                    DownloadPhase.DOWNLOADING -> if(state.paused)"正在暂停…" else "下载中"
                    DownloadPhase.WAITING -> if(state.paused)"已暂停，可续传" else "等待下载"
                },style=MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(top=10.dp))
            } }
            if(rows.isEmpty())item { Text(if(offline)"联网后同步权限，刷新下载列表" else if(state.rows.isEmpty())"同步乐谱后显示已授权的下载任务" else "没有符合条件的任务") }
        }
    }
}
