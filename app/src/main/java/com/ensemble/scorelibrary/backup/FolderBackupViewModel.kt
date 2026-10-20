package com.ensemble.scorelibrary.backup

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class FolderBackupAction { EXPORT, RESTORE }
data class FolderBackupState(val member:String?=null,val busy:Boolean=false,val pending:Boolean=false,
    val preview:FolderArchivePreview?=null,val message:String?=null,val revision:Int=0)

@HiltViewModel
class FolderBackupViewModel @Inject constructor(private val service:FolderBackupStore,private val saved:SavedStateHandle) : ViewModel() {
    private var member:String?=saved["folderBackupMember"]
    private var source:Uri?=null
    private var epoch=0
    private var job:Job?=null
    private fun action()=saved.get<String>("folderBackupAction")?.let { runCatching { FolderBackupAction.valueOf(it) }.getOrNull() }
    private val interrupted=saved.get<Boolean>("folderBackupDelivered")==true
    private val mutable=MutableStateFlow(FolderBackupState(member,pending=!interrupted && action()!=null,
        message=if(interrupted)"上次文件夹备份操作未确认完成，请重新选择文件" else null))
    val state=mutable.asStateFlow()
    init { if(interrupted)clearRequest() }
    private fun clearRequest() { source=null;saved.remove<String>("folderBackupAction");saved.remove<Boolean>("folderBackupDelivered") }
    fun bind(owner:String?) {
        if(member==owner)return
        epoch++;job?.cancel();clearRequest();member=owner;saved["folderBackupMember"]=owner
        mutable.value=FolderBackupState(owner,revision=mutable.value.revision)
    }
    fun request(owner:String,action:FolderBackupAction):Boolean {
        if(owner!=member || mutable.value.busy || mutable.value.pending || mutable.value.preview!=null)return false
        saved["folderBackupAction"]=action.name;saved["folderBackupDelivered"]=false
        mutable.update { it.copy(pending=true,message=null) };return true
    }
    fun picked(owner:String,uri:Uri?) {
        if(owner!=member)return
        val requested=action() ?: return
        if(uri==null) { dismiss(owner);return }
        saved["folderBackupDelivered"]=true
        work(owner) { token ->
            if(requested==FolderBackupAction.EXPORT) {
                service.export(owner,uri)
                if(member==owner && epoch==token) { clearRequest();mutable.update { it.copy(pending=false,message="文件夹备份已导出并回读校验") } }
            } else {
                val preview=service.preview(owner,uri)
                if(member==owner && epoch==token) { source=uri;mutable.update { it.copy(preview=preview,pending=false) } }
            }
        }
    }
    fun restore(owner:String) {
        if(member!=owner || mutable.value.busy)return
        val preview=mutable.value.preview ?: return;val uri=source ?: return
        if(preview.conflicts.isNotEmpty())return
        mutable.update { it.copy(preview=null) }
        work(owner) { token ->
            service.restore(owner,uri,preview)
            if(member==owner && epoch==token) { clearRequest();mutable.update { it.copy(message="文件夹恢复完成；原乐谱与批注保留") } }
        }
    }
    fun dismiss(owner:String) {
        if(owner!=member || mutable.value.busy)return
        clearRequest();mutable.update { it.copy(pending=false,preview=null) }
    }
    fun cancel(owner:String) {
        if(owner!=member)return
        val restoring=action()==FolderBackupAction.RESTORE
        epoch++;job?.cancel();clearRequest()
        mutable.update { it.copy(busy=false,pending=false,preview=null,revision=it.revision+1,message=if(restoring)
            "已请求取消；正在保存的恢复可能完成，请核对文件夹" else "已请求取消；未完成的导出不能作为有效备份") }
    }
    private fun work(owner:String,block:suspend (Int)->Unit) {
        if(mutable.value.busy)return
        val token=epoch;mutable.update { it.copy(busy=true,message=null) }
        job=viewModelScope.launch {
            try { block(token) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                if(member==owner && epoch==token) {
                    clearRequest()
                    mutable.update { it.copy(pending=false,preview=null,message=error.message?.takeIf { s -> s.any { it in '\u4e00'..'\u9fff' } }
                        ?: "文件夹备份无效或操作失败，未确认完成；原记录未覆盖") }
                }
            } finally {
                if(member==owner)mutable.update { it.copy(busy=if(epoch==token)false else it.busy,revision=it.revision+1) }
            }
        }
    }
}
