package com.ensemble.scorelibrary.sync

import android.graphics.BitmapFactory
import android.util.Base64
import com.ensemble.scorelibrary.account.TeamSession
import com.ensemble.scorelibrary.backup.ArchiveFormat
import com.ensemble.scorelibrary.ui.FolderCodec
import com.ensemble.scorelibrary.ui.LibraryFolder
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FolderHead(val revision:Long,val payload:String,val sha256:String) {
    fun folders()=FolderPayload.decode(payload)
}
internal data class FolderPending(val requestId:String,val baseRevision:Long,val payload:String) {
    fun body()=JSONObject().put("protocolVersion",1).put("requestId",requestId).put("baseRevision",baseRevision)
        .put("folders",JSONArray(payload))
}
internal data class FolderReceipt(val revision:Long,val sha256:String)

/** Same field order and JSON escaping as the server's canonical UTF-8 payload. */
internal object FolderPayload {
    const val LIMIT=8*1024*1024
    fun hash(value:String)=ArchiveFormat.digest(value.toByteArray(Charsets.UTF_8))
    fun keys(value:JSONObject,expected:Set<String>) { require(ArchiveFormat.keys(value)==expected) { "文件夹响应字段不匹配" } }
    fun text(value:JSONObject,key:String):String { val result=value.get(key);require(result is String);return result }
    fun number(value:JSONObject,key:String):Long {
        val result=value.get(key);require(result is Int || result is Long)
        return (result as Number).toLong().also { require(it in 0..9_007_199_254_740_991L) }
    }
    fun digest(value:String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
    private fun identity(value:String) { require(value.isNotBlank() && value.length<=200 && value.none { it.isISOControl() }) }
    private fun quote(value:String):String=buildString {
        // Reject unpaired surrogates instead of hashing replacement characters.
        Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value))
        append('"');for(c in value)when(c) {
            '"'->append("\\\"");'\\'->append("\\\\");'\b'->append("\\b");'\u000c'->append("\\f")
            '\n'->append("\\n");'\r'->append("\\r");'\t'->append("\\t")
            else->if(c.code<32)append("\\u"+c.code.toString(16).padStart(4,'0')) else append(c)
        };append('"')
    }
    fun encode(folders:List<LibraryFolder>):String {
        FolderCodec.encode(folders)
        require(folders.map { it.id }.toSet().size==folders.size)
        return folders.joinToString(",","[","]") { f ->
            identity(f.id);f.pieces.forEach(::identity);f.coverPiece?.let(::identity)
            require(f.coverPiece==null || f.image==null)
            f.image?.let(::cover)
            "{\"coverPiece\":"+(f.coverPiece?.let(::quote) ?: "null")+",\"id\":"+quote(f.id)+
                ",\"image\":"+(f.image?.let(::quote) ?: "null")+",\"name\":"+quote(f.name)+
                ",\"pieces\":"+f.pieces.joinToString(",","[","]",transform=::quote)+"}"
        }.also { require(it.toByteArray(Charsets.UTF_8).size<=LIMIT) { "文件夹封面超过同步容量，请减少图片" } }
    }
    private fun cover(encoded:String) {
        require(encoded.length<=700_000)
        val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size in 8..512*1024 && Base64.encodeToString(bytes,Base64.NO_WRAP)==encoded)
        require(bytes.take(8)==listOf(137,80,78,71,13,10,26,10).map { it.toByte() })
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
        val image=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try { require(image.width==bounds.outWidth && image.height==bounds.outHeight) } finally { image.recycle() }
    }
    fun decode(payload:String):List<LibraryFolder> {
        require(payload.toByteArray(Charsets.UTF_8).size<=LIMIT)
        ArchiveFormat.depthCheck(payload)
        val array=JSONArray(payload)
        val folders=FolderCodec.decode(payload)
        for(i in 0 until array.length()) {
            val row=array.getJSONObject(i);keys(row,setOf("id","name","pieces","coverPiece","image"))
            text(row,"id");text(row,"name")
            for(key in listOf("coverPiece","image"))if(!row.isNull(key))text(row,key)
            val pieces=row.getJSONArray("pieces")
            require((0 until pieces.length()).all { pieces.get(it) is String } && pieces.length()==folders[i].pieces.size)
        }
        encode(folders)
        return folders
    }
}

internal object TeamFolderProtocol {
    private val owner=setOf("protocolVersion","accountId","teamId")
    private fun owner(row:JSONObject,session:TeamSession,fields:Set<String>) {
        FolderPayload.keys(row,owner+fields)
        require(row.get("protocolVersion") is Int && row.getInt("protocolVersion")==1)
        require(FolderPayload.text(row,"accountId")==session.principal.accountId &&
            FolderPayload.text(row,"teamId")==session.principal.teamId) { "文件夹账号不匹配，未写入本机" }
    }
    fun head(row:JSONObject,session:TeamSession):FolderHead {
        owner(row,session,setOf("revision","payload","sha256"))
        val payload=FolderPayload.text(row,"payload");val sha=FolderPayload.text(row,"sha256");FolderPayload.digest(sha)
        require(FolderPayload.hash(payload)==sha) { "文件夹响应校验失败" }
        val folders=FolderPayload.decode(payload)
        require(FolderPayload.encode(folders)==payload) { "文件夹数据编码不一致" }
        val revision=FolderPayload.number(row,"revision")
        require(revision!=0L || payload=="[]")
        return FolderHead(revision,payload,sha)
    }
    fun pending(revision:Long,payload:String)=FolderPending(UUID.randomUUID().toString(),revision,payload)
    fun receipt(row:JSONObject,session:TeamSession,pending:FolderPending):FolderReceipt {
        owner(row,session,setOf("requestId","revision","sha256"))
        require(FolderPayload.text(row,"requestId")==pending.requestId)
        val revision=FolderPayload.number(row,"revision");val sha=FolderPayload.text(row,"sha256")
        require(revision==pending.baseRevision+1 && sha==FolderPayload.hash(pending.payload)) { "文件夹保存回执不匹配，待同步记录保留" }
        return FolderReceipt(revision,sha)
    }
    fun conflict(row:JSONObject,session:TeamSession,pending:FolderPending):FolderReceipt {
        owner(row,session,setOf("requestId","error","currentRevision","currentSha256"))
        require(FolderPayload.text(row,"requestId")==pending.requestId && FolderPayload.text(row,"error")=="folder_conflict")
        val revision=FolderPayload.number(row,"currentRevision");val sha=FolderPayload.text(row,"currentSha256");FolderPayload.digest(sha)
        require(revision!=pending.baseRevision)
        return FolderReceipt(revision,sha)
    }
}
