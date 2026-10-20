package com.ensemble.scorelibrary.personal

import android.net.Uri
import com.ensemble.scorelibrary.account.TeamAccountState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal fun personalOwner(state:TeamAccountState?):PersonalOwner?=state?.takeUnless { it.busy }?.let { s ->
    s.principal?.let { PersonalOwner(s.server,it.accountId) }
}
internal data class PersonalFilesState(val owner:PersonalOwner?=null,val snapshot:PersonalSnapshot?=null,
    val busy:Boolean=false,val message:String?=null,val plan:PersonalDeletePlan?=null,val opened:PersonalPdf?=null)

internal class PersonalDocumentsController(private val store:PersonalDocumentStore,private val scope:CoroutineScope) {
    private val mutable=MutableStateFlow(PersonalFilesState())
    val state=mutable.asStateFlow()
    private var epoch=0L
    private var job:Job?=null
    fun bind(owner:PersonalOwner?) {
        if(mutable.value.owner==owner)return
        epoch++;job?.cancel();mutable.value=PersonalFilesState(owner=owner)
        if(owner!=null)refresh()
    }
    private fun active(key:String,run:Long)=epoch==run && mutable.value.owner?.key==key
    private fun operation(action:suspend (String,Long)->Unit) {
        val owner=mutable.value.owner ?: return
        if(mutable.value.busy)return
        val run=epoch;mutable.update { it.copy(busy=true,message=null) }
        job=scope.launch {
            try { action(owner.key,run) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                if(active(owner.key,run)) {
                    if(error is PersonalOwnerChanged)mutable.value=PersonalFilesState(message=error.message)
                    else {
                        val pending=if(error is PersonalErasePending)runCatching { store.read(owner.key) }.getOrNull() else null
                        val message=when {
                            error is PersonalErasePending -> error.message
                            error.message?.let { text -> listOf("个人","当前","请","目标PDF","无法创建").any(text::startsWith) }==true -> error.message
                            else -> "本机个人文件未能完成校验或读写，原文件保留，请刷新后重试"
                        }
                        mutable.update { it.copy(snapshot=pending ?: it.snapshot,message=message,opened=null,plan=if(pending!=null)null else it.plan) }
                    }
                }
            } finally { if(active(owner.key,run))mutable.update { it.copy(busy=false) } }
        }
    }
    private fun apply(key:String,run:Long,snapshot:PersonalSnapshot,note:String?=null) {
        if(active(key,run))mutable.update { it.copy(snapshot=snapshot,message=snapshot.message ?: note,plan=null,opened=null) }
    }
    fun refresh()=operation { key,run->apply(key,run,store.read(key)) }
    fun importPdf(uri:Uri,title:String)=operation { key,run->apply(key,run,store.importPdf(key,uri,title),"已导入本账号的本机个人文件") }
    fun trash(id:String,removed:Boolean) {
        val revision=mutable.value.snapshot?.revision ?: return
        operation { key,run->apply(key,run,store.trash(key,id,revision,removed),if(removed)"已移入本账号回收站" else "已恢复个人文件") }
    }
    fun preview(id:String)=preview(setOf(id))
    fun preview(ids:Set<String>) {
        val revision=mutable.value.snapshot?.revision ?: return
        val selected=ids.toSet()
        operation { key,run->val plan=store.preview(key,selected,revision);if(active(key,run))mutable.update { it.copy(plan=plan,opened=null) } }
    }
    fun cancelPreview() { if(!mutable.value.busy)mutable.update { it.copy(plan=null) } }
    fun permanent(confirmed:Boolean) {
        val plan=mutable.value.plan ?: return
        operation { key,run->apply(key,run,store.permanent(plan,confirmed),"已彻底删除所选本机个人副本；外部原文件保留") }
    }
    fun open(id:String)=operation { key,run->val pdf=store.open(key,id);if(active(key,run))mutable.update { it.copy(opened=pdf) } }
    fun closePdf() { mutable.update { it.copy(opened=null) } }
    suspend fun verifiedPdf(id:String):PersonalPdf {
        val owner=mutable.value.owner ?: throw PersonalOwnerChanged();val run=epoch
        val pdf=store.open(owner.key,id)
        if(!active(owner.key,run))throw PersonalOwnerChanged()
        return pdf
    }
    fun close() { epoch++;job?.cancel();mutable.value=PersonalFilesState() }
}
