package com.ensemble.scorelibrary.annotation

import android.content.Context
import com.ensemble.scorelibrary.account.AccountVault
import com.ensemble.scorelibrary.account.teamSecretKey
import com.ensemble.scorelibrary.backup.ArchiveFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class RecoverySource(val space:String,val sha256:String)
internal data class InkDraftKey(val scope:AnnotationScope,val layer:AnnotationLayer,val space:String)
internal data class InkDraftWrite(val marks:List<InkMark>,val generation:Long,val beforeHash:String)
internal data class InkDraftRecord(val token:String,val source:RecoverySource,val writes:List<InkDraftWrite>)

/** A separate private recovery journal; it contains no grants, passwords or published ink. */
@Singleton
class InkDraftStore internal constructor(private val root:File,private val secret:()->SecretKey,
    private val removeFile:(File)->Boolean={it.delete()}) {
    @Inject constructor(@ApplicationContext context:Context):this(managedRoot(context),{teamSecretKey("ink-recovery-v1")})
    companion object {
        // Context owns this parent. Normalize its legitimate Android /data/user vs
        // /data/data alias, while retaining every child/file symlink check below.
        internal fun managedRoot(context:Context)=File(context.noBackupFilesDir.canonicalFile,"ink-recovery-v1")
        internal const val MAXIMUM=40*1024*1024
        internal const val TOTAL=128L*1024*1024
        internal fun digest(value:String)=ArchiveFormat.digest(value.toByteArray(Charsets.UTF_8))
        // Also serializes separate store instances used by a restored Activity in this process.
        private val diskLock=Any()
    }
    private fun text(value:String) { require(value.isNotBlank() && value.length<=200 && value.none { it.isISOControl() }) }
    private fun hash(value:String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
    private fun fields(row:JSONObject,expected:Set<String>) { require(ArchiveFormat.keys(row)==expected) }
    private fun string(row:JSONObject,key:String):String { val value=row.get(key);require(value is String);return value }
    private fun number(row:JSONObject,key:String):Long {
        val value=row.get(key);require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 0 until Long.MAX_VALUE) }
    }
    private fun scope(s:AnnotationScope)=JSONObject().put("member",s.member).put("piece",s.piece).put("part",s.part)
        .put("version",s.version).put("page",s.page).put("item",s.item ?: JSONObject.NULL)
    private fun validate(key:InkDraftKey) {
        text(key.space);with(key.scope) { listOf(member,piece,part,version).forEach(::text);item?.let(::text);require(page in 0..9999) }
    }
    private fun directory(key:InkDraftKey):File {
        validate(key)
        val directory=File(root,digest(JSONArray().put(key.space).put(key.scope.member).toString()))
        check(root.canonicalFile==root.absoluteFile && directory.canonicalFile==directory.absoluteFile) { "笔迹恢复目录不是受管目录" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建本机笔迹恢复目录" }
        return directory
    }
    private fun file(key:InkDraftKey)=with(key.scope) {
        File(directory(key),digest(JSONArray().put(member).put(piece).put(part).put(version).put(page)
            .put(item ?: JSONObject.NULL).put(key.layer.name).toString())+".enc")
    }
    private fun vault(key:InkDraftKey)=AccountVault(file(key).also {
        check(it.canonicalFile==it.absoluteFile) { "笔迹恢复文件不是受管文件" }
    },MAXIMUM,secret)
    private fun retirementVault(space:String,member:String):AccountVault {
        text(space);text(member)
        val directory=File(root,"retired-v1")
        check(root.canonicalFile==root.absoluteFile && directory.canonicalFile==directory.absoluteFile)
        check(directory.isDirectory || directory.mkdirs())
        val file=File(directory,digest(JSONArray().put(space).put(member).toString())+".enc")
        check(file.canonicalFile==file.absoluteFile)
        return AccountVault(file,32*1024*1024,secret)
    }
    private fun retired(space:String,member:String):Set<String> {
        val raw=retirementVault(space,member).read() ?: return emptySet()
        ArchiveFormat.depthCheck(raw)
        val row=JSONObject(raw);fields(row,setOf("format","space","member","versions"))
        require(row.get("format")==1 && string(row,"space")==space && string(row,"member")==member)
        val rows=row.getJSONArray("versions");require(rows.length()<=500_000)
        return (0 until rows.length()).map { i ->
            (rows.get(i) as String).also { require(UUID.fromString(it).toString()==it) }
        }.also { require(it.distinct().size==it.size) }.toSet()
    }
    private fun requireLive(key:InkDraftKey) {
        validate(key)
        check(key.scope.version !in retired(key.space,key.scope.member)) { "此乐谱已彻底删除，不能恢复或保存旧笔迹" }
    }
    private fun readNow(key:InkDraftKey):InkDraftRecord? {
        val raw=vault(key).read() ?: return null
        return decode(key,raw)
    }
    private fun decode(key:InkDraftKey,raw:String):InkDraftRecord {
        ArchiveFormat.depthCheck(raw)
        val row=JSONObject(raw);fields(row,setOf("format","token","scope","layer","space","sourceHash","writes"))
        require(row.get("format") is Int && row.getInt("format")==1)
        val token=string(row,"token");require(UUID.fromString(token).toString()==token)
        val bound=row.getJSONObject("scope");fields(bound,setOf("member","piece","part","version","page","item"))
        require(listOf("member","piece","part","version").all { string(bound,it)==scope(key.scope).getString(it) } &&
            number(bound,"page")==key.scope.page.toLong() &&
            (if(bound.isNull("item"))null else string(bound,"item"))==key.scope.item &&
            string(row,"layer")==key.layer.name && string(row,"space")==key.space) {
            "未保存笔迹身份不匹配，原文件保留"
        }
        val source=RecoverySource(key.space,string(row,"sourceHash").also(::hash))
        val rows=row.getJSONArray("writes");require(rows.length() in 1..2)
        val writes=(0 until rows.length()).map { i ->
            val write=rows.getJSONObject(i);fields(write,setOf("marks","generation","beforeHash"))
            InkDraftWrite(InkCodec.decode(string(write,"marks")),number(write,"generation"),string(write,"beforeHash").also(::hash))
        }
        if(writes.size==2)require(writes[1].generation==writes[0].generation+1 && writes[1].beforeHash==digest(InkCodec.encode(writes[0].marks)))
        return InkDraftRecord(token,source,writes)
    }
    internal suspend fun read(key:InkDraftKey):InkDraftRecord?=withContext(Dispatchers.IO) { synchronized(diskLock) { requireLive(key);readNow(key) } }
    /** The random token is a CAS boundary, including when another reader claims recovery. */
    internal suspend fun write(key:InkDraftKey,expected:String?,source:RecoverySource,writes:List<InkDraftWrite>):InkDraftRecord=
        withContext(Dispatchers.IO) { synchronized(diskLock) {
            requireLive(key)
            require(source.space==key.space);hash(source.sha256)
            check(readNow(key)?.token==expected) { "另一处已更新本机恢复草稿，未覆盖；请重新打开此页" }
            require(writes.size in 1..2)
            if(writes.size==2)require(writes[1].generation==writes[0].generation+1 && writes[1].beforeHash==digest(InkCodec.encode(writes[0].marks)))
            val token=UUID.randomUUID().toString()
            val row=JSONObject().put("format",1).put("token",token).put("scope",scope(key.scope)).put("layer",key.layer.name)
                .put("space",key.space).put("sourceHash",source.sha256).put("writes",JSONArray().apply { writes.forEach { write ->
                    require(write.generation in 0 until Long.MAX_VALUE);hash(write.beforeHash)
                    val ink=InkCodec.encode(write.marks)
                    require(InkCodec.decode(ink)==write.marks) { "恢复笔迹记录无效，原草稿保留" }
                    put(JSONObject().put("generation",write.generation).put("beforeHash",write.beforeHash).put("marks",ink))
                } })
            val encoded=row.toString();require(encoded.toByteArray(Charsets.UTF_8).size<=MAXIMUM)
            val target=file(key);val siblings=target.parentFile!!.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.enc")) }
            check(target.exists() || siblings.size<16) { "已有 16 页未保存笔迹，请先恢复或处理原草稿" }
            // Include encrypted temporary leftovers after process interruption in the disk quota.
            check(target.parentFile!!.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }-
                (if(target.exists())target.length() else 0)+encoded.toByteArray(Charsets.UTF_8).size+29<=TOTAL) {
                "本机未保存笔迹达到恢复容量上限，原草稿保留，请先处理"
            }
            vault(key).write(encoded)
            InkDraftRecord(token,source,writes)
        } }
    internal suspend fun clear(key:InkDraftKey,expected:String)=withContext(Dispatchers.IO) { synchronized(diskLock) {
        requireLive(key)
        check(readNow(key)?.token==expected) { "恢复草稿已变化，未删除；请重新查看" }
        vault(key).clear()
    } }

    /** Persist the write barrier first; failed erasure can be retried after restart without reopening old drafts.
     * Caller must have verified the server/team/account retirement journal. This is not a user discard action.
     */
    internal suspend fun retireVersions(space:String,member:String,versions:Set<String>,guard:()->Unit) =
        withContext(Dispatchers.IO) { synchronized(diskLock) {
            require(space.startsWith("team:") && versions.isNotEmpty())
            versions.forEach { require(UUID.fromString(it).toString()==it) }
            guard()
            val all=retired(space,member)+versions
            require(all.size<=500_000)
            retirementVault(space,member).write(JSONObject().put("format",1).put("space",space).put("member",member)
                .put("versions",JSONArray(all.sorted())).toString())
            val probe=InkDraftKey(AnnotationScope(member,"probe","probe",versions.first(),0),AnnotationLayer.PERSONAL,space)
            val directory=directory(probe)
            val files=checkNotNull(directory.listFiles()) { "无法检查笔迹恢复文件，尚未完成清理" }
            // Include interrupted encrypted writes; never infer their owner/version from the temporary filename.
            val targets=files.mapNotNull { file ->
                check(file.canonicalFile==file.absoluteFile && file.isFile &&
                    (file.name.matches(Regex("[a-f0-9]{64}\\.enc")) || file.name.matches(Regex("\\.session-[A-Za-z0-9-]+\\.pending")))) {
                    "笔迹恢复目录含未知文件，原文件保留"
                }
                val raw=checkNotNull(AccountVault(file,MAXIMUM,secret).read())
                ArchiveFormat.depthCheck(raw)
                val row=JSONObject(raw)
                val bound=row.getJSONObject("scope")
                fields(bound,setOf("member","piece","part","version","page","item"))
                val page=number(bound,"page");require(page<=9999)
                val key=InkDraftKey(AnnotationScope(string(bound,"member"),string(bound,"piece"),string(bound,"part"),
                    string(bound,"version"),page.toInt(),if(bound.isNull("item"))null else string(bound,"item")),
                    AnnotationLayer.valueOf(string(row,"layer")),string(row,"space"))
                require(key.space==space && key.scope.member==member)
                validate(key)
                // Decode the complete record, including ink/queue and identity, before deleting anything.
                decode(key,raw)
                if(file.name.endsWith(".enc"))require(this@InkDraftStore.file(key).absoluteFile==file.absoluteFile)
                if(key.scope.version in all)file to ArchiveFormat.digest(file.readBytes()) else null
            }
            guard()
            targets.forEach { (file,hash) ->
                guard()
                check(file.canonicalFile==file.absoluteFile && file.isFile && ArchiveFormat.digest(file.readBytes())==hash) {
                    "恢复文件已变化，原文件保留，请重试"
                }
                check(removeFile(file) && !file.exists()) { "恢复笔迹未能清除，删除尚未完成，请重试" }
            }
            targets.size
        } }
}
