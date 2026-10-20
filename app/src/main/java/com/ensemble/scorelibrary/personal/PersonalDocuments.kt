package com.ensemble.scorelibrary.personal

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.ensemble.scorelibrary.account.AccountVault
import com.ensemble.scorelibrary.account.accountServer
import com.ensemble.scorelibrary.account.teamSecretKey
import com.ensemble.scorelibrary.backup.ArchiveFormat
import com.ensemble.scorelibrary.data.ManagedPdf
import com.ensemble.scorelibrary.data.ManagedPdfImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey

/** Online account identity, deliberately independent of team role, grants or local test profiles. */
internal data class PersonalOwner(val server:String,val account:String) {
    init { require(accountServer(server)==server);require(UUID.fromString(account).toString()==account) }
    val key:String get()=ArchiveFormat.digest(JSONArray().put(server).put(account).toString().toByteArray())
}
internal data class PersonalDocument(val id:String,val title:String,val hash:String,val size:Long,val pages:Int,
    val originalUri:String,val state:String="active") {
    val trashed:Boolean get()=state!="active"
    val retiring:Boolean get()=state=="retiring"
}
internal data class PersonalSnapshot(val owner:PersonalOwner,val revision:Long,val documents:List<PersonalDocument>,
    val pending:Int=0,val message:String?=null)
internal data class PersonalDeletePlan(val owner:String,val revision:Long,val documents:List<PersonalDocument>)
internal data class PersonalPdf(val document:PersonalDocument,val file:File)
internal class PersonalOwnerChanged:IllegalStateException("当前账号已切换或正在核验，请重新打开个人文件")
internal class PersonalErasePending:IllegalStateException("个人文件清理未完成，可重试；已确认删除的文件保持不可打开，其他个人文件仍可使用")
internal typealias PersonalPdfCopier=suspend (Context,Uri,suspend (ManagedPdf)->Unit)->Unit

/** Device-only personal originals are separate from every team database and managed download.
 * A durable retirement record precedes erasure. No method accepts a NAS artifact or another actor.
 */
internal class PersonalDocumentStore(context:Context,private val ownerNow:()->PersonalOwner?,
    private val secret:()->SecretKey={teamSecretKey("personal-documents-v1")},
    private val removeFile:(File)->Boolean={it.delete()},
    private val copyPdf:PersonalPdfCopier={c,u,commit->ManagedPdfImporter(c).publish(u) { commit(it) }}) {
    private val context=context
    private val privateRoot=context.noBackupFilesDir.canonicalFile
    companion object { private val gate=Mutex();private const val MAX_DOCS=10_000;private const val MAX_RETIRED=100_000 }
    private data class State(val revision:Long=0,val documents:List<PersonalDocument> = emptyList(),val retired:Set<String> = emptySet())
    private fun owner(expected:String):PersonalOwner=ownerNow()?.takeIf { it.key==expected } ?: throw PersonalOwnerChanged()
    private fun directory(file:File):File {
        check(file.absoluteFile==file.canonicalFile) { "个人文件目录不是受管目录，原文件保留" }
        check(file.isDirectory || file.mkdirs()) { "无法创建个人文件目录" };return file
    }
    private fun root(o:PersonalOwner)=directory(File(directory(File(privateRoot,"personal-documents-v1")),o.key))
    private fun scores(o:PersonalOwner)=directory(File(root(o),"scores"))
    private fun file(o:PersonalOwner,id:String):File {
        require(UUID.fromString(id).toString()==id)
        return File(scores(o),"$id.pdf").also { check(it.canonicalFile==it.absoluteFile) { "个人PDF不是受管文件，未读取或删除" } }
    }
    private fun vault(o:PersonalOwner)=AccountVault(File(root(o),"index.enc").also {
        check(it.canonicalFile==it.absoluteFile) { "个人文件记录不是受管文件" }
    },32*1024*1024,secret)
    private fun fields(row:JSONObject,keys:Set<String>) { require(ArchiveFormat.keys(row)==keys) }
    private fun string(row:JSONObject,key:String)=row.get(key).also { require(it is String) } as String
    private fun integer(row:JSONObject,key:String):Long {
        val value=row.get(key);require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it>=0) }
    }
    private fun encode(d:PersonalDocument)=JSONObject().put("id",d.id).put("title",d.title).put("hash",d.hash)
        .put("size",d.size).put("pages",d.pages).put("originalUri",d.originalUri).put("state",d.state)
    private fun load(o:PersonalOwner):State {
        val raw=vault(o).read() ?: return State()
        ArchiveFormat.depthCheck(raw)
        val row=JSONObject(raw);fields(row,setOf("format","server","account","revision","documents","retired"))
        require(row.get("format")==1 && string(row,"server")==o.server && string(row,"account")==o.account) { "个人文件记录身份不符，原文件保留" }
        val revision=integer(row,"revision");require(revision<Long.MAX_VALUE)
        val docs=row.getJSONArray("documents");require(docs.length()<=MAX_DOCS)
        val documents=(0 until docs.length()).map { i ->
            val d=docs.getJSONObject(i);fields(d,setOf("id","title","hash","size","pages","originalUri","state"))
            val id=string(d,"id").also { require(UUID.fromString(it).toString()==it) }
            val title=string(d,"title").also { require(it.isNotBlank() && it.length<=200 && it.none(Char::isISOControl)) }
            val hash=string(d,"hash").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val size=integer(d,"size").also { require(it in 1..256L*1024*1024) }
            val pages=integer(d,"pages").also { require(it in 1..10000) }.toInt()
            val uri=string(d,"originalUri").also { require(it.length<=8192 && Uri.parse(it).scheme=="content") }
            val state=string(d,"state").also { require(it in setOf("active","trash","retiring")) }
            PersonalDocument(id,title,hash,size,pages,uri,state)
        }
        require(documents.map { it.id }.distinct().size==documents.size)
        val retiredRows=row.getJSONArray("retired");require(retiredRows.length()<=MAX_RETIRED)
        val retired=(0 until retiredRows.length()).map { i ->
            (retiredRows.get(i) as String).also { require(UUID.fromString(it).toString()==it) }
        };require(retired.distinct().size==retired.size)
        require(documents.all { (it.id in retired)==it.retiring })
        return State(revision,documents,retired.toSet())
    }
    private fun save(o:PersonalOwner,state:State) {
        owner(o.key);require(state.revision in 1 until Long.MAX_VALUE && state.documents.size<=MAX_DOCS && state.retired.size<=MAX_RETIRED)
        vault(o).write(JSONObject().put("format",1).put("server",o.server).put("account",o.account).put("revision",state.revision)
            .put("documents",JSONArray().apply { state.documents.forEach { put(encode(it)) } }).put("retired",JSONArray(state.retired.sorted())).toString())
    }
    private fun next(s:State)=s.revision.also { check(it<Long.MAX_VALUE-1) { "个人文件记录版本已达上限" } }+1
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun verify(o:PersonalOwner,d:PersonalDocument,allowMissing:Boolean=false):File {
        val f=file(o,d.id)
        if(allowMissing && !f.exists())return f
        check(f.isFile && f.length()==d.size && hash(f)==d.hash && f.canonicalFile==f.absoluteFile) { "个人PDF已变化或缺失，未读取或删除；请保留原文件并重试" }
        return f
    }
    private fun snapshot(o:PersonalOwner,s:State,message:String?=null)=PersonalSnapshot(o,s.revision,s.documents.toList(),s.documents.count { it.retiring },message)
    private fun finish(o:PersonalOwner,original:State):State {
        val targets=original.documents.filter { it.retiring }.map { it to verify(o,it,true) }
        if(targets.isEmpty())return original
        for((d,f) in targets) {
            owner(o.key);verify(o,d,true)
            if(f.exists() && (!removeFile(f) || f.exists()))throw PersonalErasePending()
        }
        val done=original.copy(revision=next(original),documents=original.documents.filterNot { it.retiring })
        save(o,done);return done
    }
    suspend fun read(expected:String):PersonalSnapshot=withContext(Dispatchers.IO) { gate.withLock {
        val o=owner(expected);val state=load(o)
        val result=try { snapshot(o,finish(o,state)) } catch(e:PersonalErasePending) { snapshot(o,state,e.message) }
        owner(expected);result
    } }
    suspend fun importPdf(expected:String,uri:Uri,title:String):PersonalSnapshot=withContext(Dispatchers.IO) { gate.withLock {
        val o=owner(expected);require(uri.scheme=="content") { "请通过文件选择器选择PDF" }
        val heading=title.trim();require(heading.isNotBlank() && heading.length<=200 && heading.none(Char::isISOControl))
        val state=load(o);require(state.documents.size<MAX_DOCS);scores(o)
        val managed=object:ContextWrapper(context) { override fun getFilesDir()=root(o) }
        var updated:State?=null
        copyPdf(managed,uri) { staged ->
            owner(expected)
            val id=staged.file.name.removeSuffix(".pdf");require(UUID.fromString(id).toString()==id)
            check(staged.file.absoluteFile==file(o,id).absoluteFile && staged.file.isFile && staged.file.length()==staged.size && hash(staged.file)==staged.hash)
            require(staged.pages in 1..10000 && staged.size in 1..256L*1024*1024 && staged.uri==uri.toString() && staged.uri.length<=8192)
            require(staged.hash.matches(Regex("[a-f0-9]{64}")) && id !in state.retired && state.documents.none { it.id==id })
            check(load(o)==state) { "个人文件已变化，请重新导入" }
            val d=PersonalDocument(id,heading,staged.hash,staged.size,staged.pages,staged.uri)
            val next=state.copy(revision=next(state),documents=state.documents+d)
            save(o,next);updated=next // Commit must return normally so importer retains the copied PDF.
        }
        owner(expected);snapshot(o,checkNotNull(updated))
    } }
    suspend fun trash(expected:String,id:String,revision:Long,removed:Boolean):PersonalSnapshot=withContext(Dispatchers.IO) { gate.withLock {
        val o=owner(expected);val s=load(o);check(s.revision==revision) { "个人文件列表已变化，请刷新后重试" }
        val d=checkNotNull(s.documents.singleOrNull { it.id==id });check(!d.retiring && id !in s.retired)
        verify(o,d);val changed=d.copy(state=if(removed)"trash" else "active")
        val next=s.copy(revision=next(s),documents=s.documents.map { if(it.id==id)changed else it });save(o,next)
        owner(expected);snapshot(o,next)
    } }
    suspend fun preview(expected:String,ids:Set<String>,revision:Long):PersonalDeletePlan=withContext(Dispatchers.IO) { gate.withLock {
        val o=owner(expected);val s=load(o);check(s.revision==revision) { "个人文件列表已变化，请刷新后重试" }
        require(ids.size in 1..100);val docs=s.documents.filter { it.id in ids };require(docs.size==ids.size && docs.all { it.state=="trash" })
        docs.forEach { verify(o,it) };owner(expected);PersonalDeletePlan(expected,revision,docs.toList())
    } }
    suspend fun permanent(plan:PersonalDeletePlan,confirmed:Boolean):PersonalSnapshot=withContext(Dispatchers.IO) { gate.withLock {
        require(confirmed) { "请先核对影响范围并确认彻底删除" };val o=owner(plan.owner);val s=load(o)
        check(s.revision==plan.revision) { "个人文件列表已变化，请重新预览" }
        val ids=plan.documents.map { it.id }.toSet();require(ids.size in 1..100 && ids.size==plan.documents.size)
        val selected=s.documents.filter { it.id in ids }
        require(selected.sortedBy { it.id }==plan.documents.sortedBy { it.id } && selected.all { it.state=="trash" })
        selected.forEach { verify(o,it) };owner(plan.owner)
        val staged=s.copy(revision=next(s),documents=s.documents.map { if(it.id in ids)it.copy(state="retiring") else it },retired=s.retired+ids)
        save(o,staged)
        val done=finish(o,staged);owner(plan.owner);snapshot(o,done)
    } }
    suspend fun open(expected:String,id:String):PersonalPdf=withContext(Dispatchers.IO) { gate.withLock {
        val o=owner(expected);val s=load(o);val d=checkNotNull(s.documents.singleOrNull { it.id==id && it.state=="active" && it.id !in s.retired }) { "文件已删除或进入回收站" }
        val pdf=verify(o,d);owner(expected);PersonalPdf(d,pdf)
    } }
}
