package com.ensemble.scorelibrary.sync

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.account.OfflineUnavailable
import com.ensemble.scorelibrary.ui.LibraryFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class FolderToolsState(val member:String?=null,val busy:Boolean=false,val preview:FolderComparison?=null,val message:String="")

@HiltViewModel
class TeamFolderViewModel @Inject constructor(val cloud:TeamFolderSync):ViewModel() {
    private val mutable=MutableStateFlow(FolderToolsState())
    val state=mutable.asStateFlow()
    init { viewModelScope.launch { cloud.active.collect { c -> mutable.value=FolderToolsState(c?.member) } } }
    fun sync(member:String)=operation(member) { c -> cloud.sync(c);if(cloud.current()===c)mutable.value=mutable.value.copy(preview=null) }
    fun preview(member:String)=operation(member) { c ->
        cloud.sync(c);val preview=cloud.preview(c)
        if(cloud.current()===c)mutable.value=mutable.value.copy(preview=preview)
    }
    fun resolve(member:String,useLocal:Boolean) {
        val preview=mutable.value.preview?.takeIf { it.member==member } ?: return
        operation(member) { c -> cloud.resolve(c,preview,useLocal);if(cloud.current()===c)mutable.value=mutable.value.copy(preview=null) }
    }
    fun dismiss(member:String) { if(mutable.value.member==member && !mutable.value.busy)mutable.value=mutable.value.copy(preview=null,message="") }
    private fun operation(member:String,block:suspend (TeamCollection)->Unit) {
        val c=cloud.current()?.takeIf { it.member==member } ?: return
        if(mutable.value.member!=member || mutable.value.busy)return
        mutable.value=mutable.value.copy(busy=true,message="")
        viewModelScope.launch {
            try { block(c) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                if(cloud.current()===c)mutable.value=mutable.value.copy(message=if(error is OfflineUnavailable)error.message.orEmpty()
                    else "操作未完成，文件夹记录可能已变化，请重新查看；两边内容保留")
            } finally { if(cloud.current()===c)mutable.value=mutable.value.copy(busy=false) }
        }
    }
}

val LocalFolderSync=staticCompositionLocalOf<TeamFolderViewModel?> { null }

@Composable
fun FolderSyncEntry(member:String) {
    val model=LocalFolderSync.current ?: return
    val active by model.cloud.active.collectAsStateWithLifecycle()
    if(active?.member!=member)return
    key(member) {
        val raw by model.cloud.state.collectAsStateWithLifecycle()
        val status=raw.takeIf { it.member==member } ?: FolderSyncState(member)
        val tools by model.state.collectAsStateWithLifecycle()
        val own=tools.takeIf { it.member==member } ?: FolderToolsState(member)
        var show by remember { mutableStateOf(false) }
        TextButton(onClick={show=true},modifier=Modifier.testTag("folder-sync-entry")) {
            Text(if(status.conflict)"文件夹同步 · 有冲突" else if(status.pending)"文件夹同步 · 待同步" else "文件夹同步")
        }
        if(show) {
            val preview=own.preview
            if(preview!=null)FolderConflictDialog(preview,!own.busy && !status.busy,own.message,
                {model.resolve(member,true)},{model.resolve(member,false)},{model.dismiss(member)})
            else AlertDialog(onDismissRequest={if(!own.busy && !status.busy) { model.dismiss(member);show=false }},
                title={Text("我的文件夹同步")},text={FolderSyncActions(status,own.busy,own.message,{model.sync(member)},{model.preview(member)})},
                confirmButton={TextButton(onClick={model.dismiss(member);show=false},enabled=!own.busy && !status.busy) { Text("关闭") }})
        }
    }
}

@Composable
internal fun FolderSyncActions(status:FolderSyncState,busy:Boolean,message:String,sync:()->Unit,preview:()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("文件夹和封面同步到本乐团 NAS，只有本人账号能在其他设备获取。PDF 和批注使用各自的同步功能。")
        Text("离线修改先留在本机；两边有不同修改时需要你选择。文件夹中的引用不会增加乐谱权限。",style=MaterialTheme.typography.bodySmall)
        if(status.busy || busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.message.isNotEmpty())Text(status.message,Modifier.testTag("folder-sync-status"))
        if(message.isNotEmpty())Text(message,Modifier.testTag("folder-sync-error"))
        OutlinedButton(onClick=sync,enabled=!busy && !status.busy,modifier=Modifier.testTag("folder-sync-now")) { Text("立即同步我的文件夹") }
        if(status.conflict)OutlinedButton(onClick=preview,enabled=!busy && !status.busy,modifier=Modifier.testTag("folder-sync-preview")) { Text("查看两边并处理冲突") }
    }
}

@Composable
internal fun FolderConflictDialog(preview:FolderComparison,enabled:Boolean,message:String,local:()->Unit,remote:()->Unit,dismiss:()->Unit) {
    AlertDialog(onDismissRequest={if(enabled)dismiss()},title={Text("选择保留哪一套文件夹")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("本次选择整套文件夹，包括名称、曲目引用、封面和删除结果。确认前可以继续保留两边。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp).testTag("folder-conflict-list"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                item { Text("本机：${preview.local.size} 个文件夹",style=MaterialTheme.typography.titleSmall) }
                items(preview.local,key={"local:${it.id}"}) { FolderConflictRow(it) }
                item { Text("云端：${preview.remote.size} 个文件夹",style=MaterialTheme.typography.titleSmall) }
                items(preview.remote,key={"remote:${it.id}"}) { FolderConflictRow(it) }
            }
            Text("保留本机：用本机整套内容更新云端。使用云端：替换本机整套文件夹。PDF、编号、授权和批注不改动。",style=MaterialTheme.typography.bodySmall)
            if(message.isNotEmpty())Text(message,Modifier.testTag("folder-conflict-error"))
        }},confirmButton={Column {
            TextButton(onClick=local,enabled=enabled,modifier=Modifier.testTag("folder-conflict-local")) { Text("确认保留本机整套") }
            TextButton(onClick=remote,enabled=enabled,modifier=Modifier.testTag("folder-conflict-remote")) { Text("确认使用云端整套") }
        }},dismissButton={TextButton(onClick=dismiss,enabled=enabled) { Text("暂不处理") }})
}

@Composable
private fun FolderConflictRow(folder:LibraryFolder) {
    val image=remember(folder.image) { folder.image?.let { encoded ->
        val bytes=Base64.decode(encoded,Base64.NO_WRAP);BitmapFactory.decodeByteArray(bytes,0,bytes.size)
    } }
    DisposableEffect(image) { onDispose { image?.recycle() } }
    OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        image?.let { Image(it.asImageBitmap(),"图片封面",Modifier.size(42.dp),contentScale=ContentScale.Crop) }
        Column { Text(folder.name);Text("${folder.pieces.size} 个曲目引用 · "+when {
            folder.image!=null->"图片封面";folder.coverPiece!=null->"乐谱封面";else->"默认封面"
        },style=MaterialTheme.typography.bodySmall) }
    } }
}
