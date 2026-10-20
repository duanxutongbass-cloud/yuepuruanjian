package com.ensemble.scorelibrary.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.domain.Dashboard

val LocalArchive = staticCompositionLocalOf<ArchiveViewModel?> { null }

@Composable
fun ArchivePanel(dashboard: Dashboard, model: ArchiveViewModel) {
    val actor = dashboard.profile?.id
    LaunchedEffect(actor) { model.bind(actor) }
    val state by model.state.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::picked)
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::picked)
    var confirmMother by rememberSaveable(actor) { mutableStateOf(false) }
    val enabled = !state.busy && !state.pending
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("archive-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
        if (dashboard.profile?.canManage == true) OutlinedButton(onClick = { confirmMother = true }, enabled = enabled,
            modifier = Modifier.testTag("archive-export-library")) { Icon(Icons.Outlined.FileDownload, null); Text("导出母库") }
        if (actor != null) {
            OutlinedButton(onClick = { if (model.request(ArchiveAction.EXPORT_PERSONAL)) export.launch("个人批注.zip") }, enabled = enabled,
                modifier = Modifier.testTag("archive-export-personal")) { Icon(Icons.Outlined.FileDownload, null); Text("导出我的批注") }
            OutlinedButton(onClick = { if (model.request(ArchiveAction.RESTORE_PERSONAL)) restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = enabled,
                modifier = Modifier.testTag("archive-restore-personal")) { Icon(Icons.Outlined.FileUpload, null); Text("恢复我的批注") }
        }
        if (dashboard.members.isEmpty()) OutlinedButton(onClick = {
            if (model.request(ArchiveAction.RESTORE_LIBRARY)) restore.launch(arrayOf("application/zip", "application/octet-stream"))
        }, enabled = enabled, modifier = Modifier.testTag("archive-restore-library")) { Icon(Icons.Outlined.FileUpload, null); Text("从备份恢复母库") }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = model::cancel) { Text("取消") }
        }
        state.message?.let { Text(it, Modifier.testTag("archive-message"), style = MaterialTheme.typography.bodyMedium) }
    }
    if (confirmMother) AlertDialog(onDismissRequest = { confirmMother = false }, title = { Text("导出母库") },
        text = { Text("包含所有原始 PDF、历史版本和共享批注，不含个人批注。备份未加密，请妥善保管。") },
        confirmButton = { TextButton(onClick = {
            confirmMother = false
            if (model.request(ArchiveAction.EXPORT_LIBRARY)) export.launch("曲谱库母库.zip")
        }) { Text("继续") } }, dismissButton = { TextButton(onClick = { confirmMother = false }) { Text("取消") } })
    state.preview?.let { preview -> AlertDialog(onDismissRequest = { if (!state.busy) model.dismiss() },
        title = { Text("确认恢复") }, text = {
            Text(if (preview.kind == ArchiveKind.LIBRARY)
                "${preview.pieces} 首曲目、${preview.files} 个 PDF 版本、${preview.annotations} 页共享批注。只恢复到空库，不覆盖已有资料。个人批注需本人分别恢复。"
                else "${preview.annotations} 页个人批注。仅恢复到原成员和原谱版本，已有冲突会拒绝。")
        }, confirmButton = { TextButton(onClick = model::restore, enabled = !state.busy) { Text("恢复") } },
        dismissButton = { TextButton(onClick = model::dismiss, enabled = !state.busy) { Text("取消") } }) }
}
