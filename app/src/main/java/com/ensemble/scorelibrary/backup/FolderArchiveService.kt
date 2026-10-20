package com.ensemble.scorelibrary.backup

import com.ensemble.scorelibrary.domain.ScoreNumber

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.sync.TeamLibraryScope
import com.ensemble.scorelibrary.ui.FolderCodec
import com.ensemble.scorelibrary.ui.LibraryFolder
import com.ensemble.scorelibrary.ui.LibraryFolderStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.io.File
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

internal data class FolderOwner(val member:String,val space:String,val pieces:Map<String,PieceEntity>,val guard:()->Unit)

class FolderAccess @Inject constructor(private val database:ScoreLibraryDatabase,private val team:TeamLibraryScope) {
    internal suspend fun owner(member:String):FolderOwner {
        val collection=team.active.value
        val db=collection?.database ?: database
        return db.withTransaction {
            val stamp=collection?.let { team.preparationStamp(it) }
            require(collection==null || collection.member==member) { "不能备份其他账号的文件夹" }
            val raw=db.resolutionDao().snapshot()
            check(raw.members.any { it.id==member && !it.isArchived }) { "请先选择有效的本人身份" }
            val snapshot=if(collection==null)raw else team.project(collection,raw)
            FolderOwner(member,collection?.let { "team:${it.key}" } ?: "local",
                snapshot.pieces.filterNot { it.isArchived }.associateBy { it.id }) {
                if(collection==null)check(team.active.value==null) { "账号已切换，请重新操作" }
                else check(team.preparationStamp(collection)==stamp) { "乐谱权限已变化，请重新预览" }
            }
        }
    }
}

data class FolderArchivePreview(val folders:Int,val added:Int,val identical:Int,val conflicts:List<String>,
    val missingReferences:Int,val hash:String,val localHash:String)

interface FolderBackupStore {
    suspend fun preview(member:String,source:Uri):FolderArchivePreview
    suspend fun restore(member:String,source:Uri,preview:FolderArchivePreview)
    suspend fun export(member:String,destination:Uri)
}

/** An independent open ZIP; it contains no PDFs, grants, login tokens or annotation layers. */
@Singleton
class FolderArchiveService @Inject constructor(@param:ApplicationContext private val context:Context,
    private val store:LibraryFolderStore,private val access:FolderAccess) : FolderBackupStore {
    companion object {
        internal const val FORMAT="ensemble-score-folders"
        internal const val JSON_LIMIT=10*1024*1024
        internal const val ZIP_LIMIT=12*1024*1024
    }
    private fun keys(row:JSONObject,expected:Set<String>) { require(ArchiveFormat.keys(row)==expected) { "文件夹备份字段不匹配" } }
    private fun text(row:JSONObject,key:String):String {
        val value=row.get(key);require(value is String);return value
    }
    private fun identity(value:String) { require(value.isNotBlank() && value.length<=200 && value.none { it.isISOControl() }) { "文件夹身份无效" } }
    private fun referenceIds(folders:List<LibraryFolder>)=folders.flatMap { it.pieces+listOfNotNull(it.coverPiece) }.toSet()
    private fun validateFolders(folders:List<LibraryFolder>) {
        FolderCodec.encode(folders)
        require(folders.map { it.id }.toSet().size==folders.size)
        for(folder in folders) {
            identity(folder.id);folder.pieces.forEach(::identity);folder.coverPiece?.let(::identity)
            require(folder.coverPiece==null || folder.image==null) { "同一文件夹不能同时使用两种封面" }
            folder.image?.let { encoded ->
                require(encoded.length<=700_000) { "封面图片过大" }
                val bytes=Base64.decode(encoded,Base64.NO_WRAP)
                require(bytes.size in 8..512*1024 && Base64.encodeToString(bytes,Base64.NO_WRAP)==encoded)
                require(bytes.take(8)==listOf(137,80,78,71,13,10,26,10).map { it.toByte() }) { "封面必须是有效的 PNG 图片" }
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                require(options.outWidth in 1..512 && options.outHeight in 1..512) { "封面尺寸超过备份上限" }
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)) { "封面图片损坏" }
                try { require(bitmap.width==options.outWidth && bitmap.height==options.outHeight) } finally { bitmap.recycle() }
            }
        }
    }
    private fun manifest(owner:FolderOwner,folders:List<LibraryFolder>):JSONObject {
        validateFolders(folders)
        return JSONObject().put("format",FORMAT).put("formatVersion",1).put("owner",owner.member).put("space",owner.space)
            .put("createdAtEpochMillis",System.currentTimeMillis()).put("folders",JSONArray(FolderCodec.encode(folders)))
            .put("pieceReferences",JSONArray().apply { referenceIds(folders).sorted().forEach { id ->
                val piece=owner.pieces[id]
                put(JSONObject().put("id",id).put("sequence",piece?.organizerSequence ?: JSONObject.NULL)
                    .put("categoryCode",piece?.categoryCode ?: JSONObject.NULL))
            } })
    }
    internal suspend fun exportBytes(member:String):ByteArray=withContext(Dispatchers.IO) {
        val owner=access.owner(member);val folders=store.read(member)
        val root=manifest(owner,folders).toString(2).toByteArray(Charsets.UTF_8)
        require(root.size<=JSON_LIMIT) { "文件夹备份过大" }
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(root);zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"));zip.write((
                "Ensemble score folders v1. UTF-8 manifest.json, PNG covers in Base64.\n"+
                "Personal folders only. No PDFs, grants, passwords or annotations.\n"+
                "Restore into the same owner and library scope. IDs are preserved; no matching by title/number.\n"+
                "Identical records are skipped. Conflicting IDs refuse the whole merge. Missing scores remain unavailable.\n"+
                "The ZIP is unencrypted. CRC and SHA-256 detect corruption, not authorship.\n").toByteArray());zip.closeEntry()
        }
        ensureActive();owner.guard();output.toByteArray().also { require(it.size<=ZIP_LIMIT);decode(it,owner) }
    }
    private fun decode(bytes:ByteArray,owner:FolderOwner):List<LibraryFolder> {
        require(bytes.size<=ZIP_LIMIT)
        val entries=mutableMapOf<String,ByteArray>()
        val temp=File.createTempFile("folder-archive-",".zip",context.cacheDir)
        try {
            temp.writeBytes(bytes)
            ZipFile(temp).use { zip ->
                val iterator=zip.entries()
                while(iterator.hasMoreElements()) {
                val entry=iterator.nextElement()
                require(!entry.isDirectory && entry.name in setOf("manifest.json","README.txt") && entry.name !in entries) { "文件夹备份包含未知或重复条目" }
                val value=zip.getInputStream(entry).use { limited(it,if(entry.name=="manifest.json")JSON_LIMIT else 65536) }
                val crc=CRC32().apply { update(value) }
                require(crc.value==entry.crc && value.size.toLong()==entry.size) { "文件夹备份条目损坏" }
                entries[entry.name]=value
                }
            }
        } finally { temp.delete() }
        require(entries.keys==setOf("manifest.json","README.txt")) { "文件夹备份不完整" }
        val json=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(entries.getValue("manifest.json"))).toString()
        ArchiveFormat.depthCheck(json)
        val root=JSONObject(json)
        keys(root,setOf("format","formatVersion","owner","space","createdAtEpochMillis","folders","pieceReferences"))
        require(text(root,"format")==FORMAT && root.get("formatVersion") is Int && root.getInt("formatVersion")==1)
        val created=root.get("createdAtEpochMillis")
        require((created is Int || created is Long) && (created as Number).toLong()>=0)
        require(text(root,"owner")==owner.member && text(root,"space")==owner.space) { "只能恢复同一账号、同一曲库的文件夹备份" }
        val rows=root.getJSONArray("folders")
        val folders=FolderCodec.decode(rows.toString())
        rows.let { array -> for(i in 0 until array.length()) {
            val row=array.getJSONObject(i)
            keys(row,setOf("id","name","pieces","coverPiece","image"));text(row,"id");text(row,"name")
            for(key in listOf("coverPiece","image"))if(!row.isNull(key))text(row,key)
            val pieces=row.getJSONArray("pieces")
            require((0 until pieces.length()).all { pieces.get(it) is String } && pieces.length()==folders[i].pieces.size)
        } }
        validateFolders(folders)
        val refs=root.getJSONArray("pieceReferences");val known=mutableSetOf<String>()
        require(refs.length()<=1_000_100)
        for(i in 0 until refs.length()) {
            val ref=refs.getJSONObject(i);keys(ref,setOf("id","sequence","categoryCode"))
            val id=text(ref,"id");identity(id);require(known.add(id))
            for(key in listOf("sequence","categoryCode"))if(!ref.isNull(key)) {
                val value=text(ref,key);if(key=="sequence") ScoreNumber.sequence(value) else require(value.matches(Regex("[0-9]{2}")))
                val current=if(key=="sequence")owner.pieces[id]?.organizerSequence else null
                require(current==null || current==value) { "曲目编号引用不一致，未恢复；不会按同名曲目替换" }
            }
        }
        require(known==referenceIds(folders)) { "文件夹曲目引用不完整" }
        return folders
    }
    internal suspend fun previewBytes(member:String,bytes:ByteArray):FolderArchivePreview=withContext(Dispatchers.IO) {
        val owner=access.owner(member);val incoming=decode(bytes,owner)
        val hash=store.fingerprint(member);val current=store.read(member).associateBy { it.id }
        if(incoming.none { current[it.id]!=null && current[it.id]!=it })
            FolderCodec.encode(current.values.toList()+incoming.filter { it.id !in current })
        owner.guard()
        FolderArchivePreview(incoming.size,incoming.count { it.id !in current },incoming.count { current[it.id]==it },
            incoming.filter { current[it.id]!=null && current[it.id]!=it }.map { it.name },
            referenceIds(incoming).count { it !in owner.pieces },ArchiveFormat.digest(bytes),hash)
    }
    internal suspend fun restoreBytes(member:String,bytes:ByteArray,expected:FolderArchivePreview)=withContext(Dispatchers.IO) {
        require(ArchiveFormat.digest(bytes)==expected.hash) { "预览后备份文件已变化，请重新预览" }
        val owner=access.owner(member);val incoming=decode(bytes,owner)
        ensureActive();store.merge(member,incoming,expected.localHash) { ensureActive();owner.guard() }
    }
    private fun limited(input:InputStream,limit:Int,check:()->Unit={}):ByteArray {
        val output=ByteArrayOutputStream();val buffer=ByteArray(16384)
        while(true) { check();val count=input.read(buffer);if(count<0)break
            require(output.size()+count<=limit) { "文件夹备份超过安全大小上限" };output.write(buffer,0,count)
        }
        return output.toByteArray()
    }
    private suspend fun read(source:Uri):ByteArray {
        require(source.scheme=="content") { "请通过系统文件选择器选择文件夹备份" }
        val job=currentCoroutineContext()
        return context.contentResolver.openInputStream(source)?.use { limited(it,ZIP_LIMIT) { job.ensureActive() } }
            ?: error("无法读取文件夹备份")
    }
    override suspend fun preview(member:String,source:Uri)=withContext(Dispatchers.IO) { previewBytes(member,read(source)) }
    override suspend fun restore(member:String,source:Uri,preview:FolderArchivePreview)=withContext(Dispatchers.IO) { restoreBytes(member,read(source),preview) }
    override suspend fun export(member:String,destination:Uri)=withContext(Dispatchers.IO) {
        require(destination.scheme=="content") { "请选择备份保存位置" }
        val owner=access.owner(member);val bytes=exportBytes(member)
        context.contentResolver.openOutputStream(destination,"wt")?.use { output ->
            var offset=0
            while(offset<bytes.size) { ensureActive();owner.guard();val count=minOf(16384,bytes.size-offset);output.write(bytes,offset,count);offset+=count }
            output.flush()
        } ?: error("无法写入文件夹备份")
        val copied=read(destination);require(ArchiveFormat.digest(copied)==ArchiveFormat.digest(bytes)) { "导出后的备份校验失败，请重新导出" }
        owner.guard()
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class FolderBackupModule {
    @dagger.Binds abstract fun backups(service:FolderArchiveService):FolderBackupStore
}
