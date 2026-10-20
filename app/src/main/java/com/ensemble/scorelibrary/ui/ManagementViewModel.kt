package com.ensemble.scorelibrary.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class ManagementState(val actor: String? = null, val catalog: ManagementCatalog? = null,
    val token: String = "", val busy: Boolean = false, val message: String? = null, val revision: Long = 0)

@HiltViewModel
class ManagementViewModel @Inject constructor(private val service: LibraryManagement) : ViewModel() {
    private val mutable=MutableStateFlow(ManagementState())
    val state=mutable.asStateFlow()
    private var observation: Job?=null
    private var operationJob: Job?=null
    fun bind(actor: String?) {
        if(mutable.value.actor==actor) return
        observation?.cancel()
        operationJob?.cancel()
        mutable.value=ManagementState(actor=actor,busy=mutable.value.busy,revision=mutable.value.revision)
        if(actor!=null) observation=viewModelScope.launch {
            service.observe(actor).catch { failure ->
                if(failure is CancellationException) throw failure
                if(mutable.value.actor==actor) mutable.value=mutable.value.copy(catalog=null,token="",message="管理目录不可用，请重新选择管理员或刷新")
            }.collect { catalog ->
                if(mutable.value.actor==actor) mutable.value=mutable.value.copy(catalog=catalog,token=UUID.randomUUID().toString())
            }
        }
    }
    fun refresh() { if(mutable.value.busy)return;val actor=mutable.value.actor; bind(null); bind(actor) }
    fun clearMessage() { mutable.value=mutable.value.copy(message=null) }
    fun edit(actor: String, token: String, command: ManagementCommand) = operation(actor,token) { catalog ->
        service.edit(actor,command,catalog.data); "已保存"
    }
    fun importPdf(actor: String, token: String, uri: Uri, title: String, pdf: String?) = operation(actor,token) { catalog ->
        service.importPdf(actor,uri,title,pdf,catalog.data); "版本已导入；批注按版本独立保存"
    }
    fun activate(actor: String, token: String, pdf: String, version: String) = operation(actor,token) { catalog ->
        service.activateVersion(actor,pdf,version,catalog.data); "当前版本已切换；未迁移批注"
    }
    private fun operation(actor: String, token: String, block: suspend (ManagementCatalog) -> String) {
        val before=mutable.value
        if(before.busy) return
        if(before.actor!=actor || before.token!=token || before.catalog==null) {
            if(before.actor==actor) mutable.value=before.copy(message="数据已更新，请重新打开编辑")
            return
        }
        mutable.value=before.copy(busy=true,message=null)
        val job=viewModelScope.launch {
            try {
                val message=block(before.catalog)
                if(mutable.value.actor==actor) mutable.value=mutable.value.copy(message=message,revision=mutable.value.revision+1)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) {
                if(mutable.value.actor==actor) mutable.value=mutable.value.copy(message=if(failure is IllegalStateException || failure is IllegalArgumentException)
                    failure.message ?: "操作失败" else "操作失败，请重试")
            }
        }
        operationJob=job
        // Completion also runs when a queued coroutine is cancelled before entering its body.
        job.invokeOnCompletion {
            viewModelScope.launch {
                if(operationJob===job) mutable.value=mutable.value.copy(busy=false)
            }
        }
    }
}
