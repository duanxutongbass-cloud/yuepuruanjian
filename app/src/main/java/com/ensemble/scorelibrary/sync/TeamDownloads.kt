package com.ensemble.scorelibrary.sync

import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadPhase { WAITING, DOWNLOADING, VERIFYING, STAGED, READY, FAILED }
data class TeamDownload(val id:String,val title:String,val part:String,val number:String,val total:Long,
    val received:Long=0,val phase:DownloadPhase=DownloadPhase.WAITING,val category:String="")
data class TeamDownloadState(val key:String?=null,val rows:List<TeamDownload> = emptyList(),val paused:Boolean=false,
    val active:Boolean=false,val committing:Boolean=false,val message:String="")
class TeamDownloadsPaused : TeamSyncFailure("下载已暂停；已验证的缓存仍可阅读，继续时会重新核验权限")

/** Progress is separate from account/reader state. Only the pause bit is persisted; names and
 * queue membership always come from the freshly authorized catalog. Partial bytes confer no grant. */
@Singleton
class TeamDownloads @Inject constructor(private val scope:TeamLibraryScope) {
    private val mutable=MutableStateFlow(TeamDownloadState())
    val state=mutable.asStateFlow()
    @Volatile private var collection:TeamCollection?=null
    @Volatile private var paused=false
    private var reportedAt=0L
    private val name=Regex("resume-[a-f0-9-]{36}\\.part")
    private fun control(c:TeamCollection)=AtomicFile(File(c.directory.parentFile,"download-control.json"))
    @Synchronized fun bind(c:TeamCollection) {
        scope.requireCurrent(c);collection=c
        paused=try { control(c).let { file ->
            if(!file.baseFile.exists())false else {
                require(file.baseFile.length()<=1024)
                JSONObject(file.readFully().toString(Charsets.UTF_8)).let {
                require(it.getInt("format")==1);it.getBoolean("paused")
            } }
        } } catch(_:Exception) { true }
        mutable.value=TeamDownloadState(c.key,paused=paused)
    }
    @Synchronized fun detach() { collection=null;mutable.value=TeamDownloadState() }
    private fun current(c:TeamCollection) { scope.requireCurrent(c);check(collection===c) { "下载账号已切换" } }
    private fun save(c:TeamCollection) {
        val file=control(c);val output=file.startWrite()
        try { output.write(JSONObject().put("format",1).put("paused",paused).toString().toByteArray(Charsets.UTF_8));file.finishWrite(output) }
        catch(error:Exception) { file.failWrite(output);throw error }
    }
    @Synchronized fun pause(expectedKey:String?=null) {
        val c=collection ?: return;current(c)
        check(expectedKey==null || expectedKey==c.key) { "下载账号已切换" }
        if(mutable.value.committing)return
        paused=true
        mutable.update { it.copy(paused=true,message=if(it.active)"正在暂停，未完成内容将保留" else "下载已暂停") }
        save(c)
    }
    @Synchronized fun resume(expectedKey:String?=null) {
        val c=collection ?: return;current(c);check(!mutable.value.active) { "正在暂停，请稍候再继续" }
        check(expectedKey==null || expectedKey==c.key) { "下载账号已切换" }
        paused=false;save(c);mutable.update { it.copy(paused=false,message="") }
    }
    internal fun partial(c:TeamCollection,a:TeamArtifact):File {
        current(c)
        val file=File(c.directory,"resume-${teamId("partial/${a.id}/${a.hash}/${a.size}/${a.pages}")}.part")
        require(name.matches(file.name) && file.canonicalFile.parentFile==c.directory.canonicalFile) { "下载暂存路径无效" }
        return file
    }
    @Synchronized internal fun plan(c:TeamCollection,catalog:TeamCatalog) {
        current(c)
        mutable.value=TeamDownloadState(c.key,paused=paused,active=true)
        val targets=catalog.artifacts.associate { it.id to partial(c,it) }
        val keep=targets.values.map { it.name }.toSet()
        c.directory.listFiles().orEmpty().filter { name.matches(it.name) && it.name !in keep }.forEach { file ->
            require(file.canonicalFile.parentFile==c.directory.canonicalFile)
            check(file.delete() || !file.exists()) { "无法清理已无授权的下载暂存，请重试" }
        }
        val rows=catalog.artifacts.map { a ->
            val file=targets.getValue(a.id)
            if(file.exists() && file.length()>a.size)check(file.delete())
            TeamDownload(a.id,a.title,a.partName,a.number,a.size,file.takeIf { it.isFile }?.length() ?: 0,category=a.category)
        }
        mutable.value=TeamDownloadState(c.key,rows,paused=paused,active=true)
    }
    internal fun requireRunning(c:TeamCollection) { current(c);if(paused)throw TeamDownloadsPaused() }
    internal fun progress(c:TeamCollection,id:String,bytes:Long,total:Long) {
        requireRunning(c)
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-reportedAt>=100 || bytes==total || bytes==0L || mutable.value.rows.any { it.id==id && it.phase!=DownloadPhase.DOWNLOADING }) {
            reportedAt=now;update(c,id,DownloadPhase.DOWNLOADING,bytes)
        }
    }
    @Synchronized internal fun update(c:TeamCollection,id:String,phase:DownloadPhase,bytes:Long?=null) {
        if(scope.active.value!==c || collection!==c)return
        mutable.update { it.copy(rows=it.rows.map { row -> if(row.id==id)row.copy(phase=phase,
            received=(bytes ?: row.received).coerceIn(0,row.total)) else row }) }
    }
    internal fun discard(c:TeamCollection,a:TeamArtifact) {
        val file=partial(c,a);check(file.delete() || !file.exists()) { "无法清理损坏暂存，请重试" }
        update(c,a.id,DownloadPhase.FAILED,0)
    }
    @Synchronized internal fun committing(c:TeamCollection) { requireRunning(c);mutable.update { it.copy(committing=true,message="正在核验并入库，请稍候") } }
    @Synchronized internal fun complete(c:TeamCollection,catalog:TeamCatalog) {
        current(c)
        catalog.artifacts.forEach { a -> partial(c,a).delete() }
        mutable.update { it.copy(rows=it.rows.map { row -> row.copy(received=row.total,phase=DownloadPhase.READY) },
            message="已同步 ${catalog.artifacts.size} 份授权乐谱") }
    }
    @Synchronized internal fun end(c:TeamCollection,error:Exception?=null) {
        if(scope.active.value!==c || collection!==c)return
        mutable.update { it.copy(active=false,committing=false,message=when {
            paused -> "下载已暂停；继续时重新核验权限"
            error!=null -> "同步未完成，已接收内容保留；可重试未完成文件"
            else -> it.message
        }) }
    }
}
