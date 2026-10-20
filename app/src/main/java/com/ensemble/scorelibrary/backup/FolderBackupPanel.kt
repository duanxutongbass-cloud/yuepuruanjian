package com.ensemble.scorelibrary.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.ui.LibraryFolderViewModel

val LocalFolderBackups=staticCompositionLocalOf<FolderBackupViewModel?> { null }

@Composable
fun FolderBackupEntry(member:String,folders:LibraryFolderViewModel,enabled:Boolean) {
    val model=LocalFolderBackups.current ?: return
    key(member) {
        val raw by model.state.collectAsStateWithLifecycle()
        val state=raw.takeIf { it.member==member } ?: FolderBackupState()
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(member) { model.bind(member) }
        LaunchedEffect(member,state.revision) { if(state.revision>0)folders.reload(member) }
        // A picker belonging to a previous member is unregistered when this key leaves composition.
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { model.picked(member,it) }
        val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.picked(member,it) }
        TextButton(onClick={show=true},enabled=enabled,modifier=Modifier.testTag("folder-backup-entry")) { Text("文件夹备份") }
        if(show)AlertDialog(onDismissRequest={if(!state.busy && !state.pending) { model.dismiss(member);show=false } },
            title={Text("我的文件夹备份")},text={FolderBackupActions(state,
                {if(model.request(member,FolderBackupAction.EXPORT))export.launch("我的曲谱文件夹.zip")},
                {if(model.request(member,FolderBackupAction.RESTORE))restore.launch(arrayOf("application/zip","application/octet-stream"))},
                {model.cancel(member)})},
            confirmButton={TextButton(onClick={model.dismiss(member);show=false},enabled=!state.busy && !state.pending) { Text("关闭") }})
        state.preview?.let { preview -> FolderRestoreConfirmation(preview,!state.busy,{model.restore(member)},{model.dismiss(member)}) }
    }
}
@Composable
internal fun FolderBackupActions(state:FolderBackupState,export:()->Unit,restore:()->Unit,cancel:()->Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("包含本人的文件夹、图片封面和乐谱封面引用。PDF、批注和账号密码不在这个备份中。")
        Text("ZIP 未加密，请自行保管。先预览再确认恢复；同一记录有冲突时保留本机内容。",style=MaterialTheme.typography.bodySmall)
        val enabled=state.member!=null && !state.busy && !state.pending && state.preview==null
        OutlinedButton(onClick=export,enabled=enabled,modifier=Modifier.testTag("folder-backup-export")) { Text("导出我的文件夹") }
        OutlinedButton(onClick=restore,enabled=enabled,modifier=Modifier.testTag("folder-backup-restore")) { Text("从备份恢复文件夹") }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.busy || state.pending)TextButton(onClick=cancel,modifier=Modifier.testTag("folder-backup-cancel")) { Text("取消操作") }
        state.message?.let { Text(it,Modifier.testTag("folder-backup-message")) }
    }
}
@Composable
internal fun FolderRestoreConfirmation(preview:FolderArchivePreview,enabled:Boolean,restore:()->Unit,cancel:()->Unit) {
    AlertDialog(onDismissRequest=cancel,title={Text("确认恢复文件夹")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("备份共 ${preview.folders} 个文件夹；新增 ${preview.added} 个，相同记录跳过 ${preview.identical} 个。")
        if(preview.missingReferences>0)Text("${preview.missingReferences} 个曲目引用目前缺失或无授权。保留引用，仍需恢复原曲库或取得授权后才能查看；不会按同名曲目替换。")
        if(preview.conflicts.isNotEmpty())Text("存在冲突：${preview.conflicts.joinToString("、")}。本次不能恢复，已保留本机内容。")
        Text("原有文件夹保留；原 PDF、编号和批注不改动。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=restore,enabled=enabled && preview.conflicts.isEmpty(),modifier=Modifier.testTag("folder-backup-confirm")) { Text("确认恢复") }},
        dismissButton={TextButton(onClick=cancel,enabled=enabled) { Text("取消") }})
}
