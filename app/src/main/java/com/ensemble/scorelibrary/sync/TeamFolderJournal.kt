package com.ensemble.scorelibrary.sync

import android.content.Context
import com.ensemble.scorelibrary.account.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

internal data class FolderApply(val expectedLocal:String,val head:FolderHead)
internal data class FolderCheckpoint(val baseRevision:Long=0,val baseSha:String?=null,
    val pending:FolderPending?=null,val conflict:FolderHead?=null,val applying:FolderApply?=null)

/** Survives token renewal and login, bound to this service/account/member, never OS backup. */
@Singleton
class TeamFolderJournal internal constructor(private val root:File,private val key:()->SecretKey) {
    @Inject constructor(@ApplicationContext context:Context):this(context.noBackupFilesDir,{teamSecretKey("team-folders-v1")})
    private fun vault(s:TeamSession):AccountVault {
        val id=teamId(s.server+"/"+s.principal.teamId+"/"+s.principal.accountId)
        val directory=File(root,"team-libraries/$id").apply { check(isDirectory || mkdirs()) }
        return AccountVault(File(directory,"folders-sync.enc"),40*1024*1024,key)
    }
    private fun head(value:FolderHead)=JSONObject().put("revision",value.revision).put("payload",value.payload).put("sha256",value.sha256)
    private fun readHead(row:JSONObject):FolderHead {
        FolderPayload.keys(row,setOf("revision","payload","sha256"))
        val revision=FolderPayload.number(row,"revision");val payload=FolderPayload.text(row,"payload");val sha=FolderPayload.text(row,"sha256")
        FolderPayload.digest(sha);require(FolderPayload.hash(payload)==sha)
        require(FolderPayload.encode(FolderPayload.decode(payload))==payload)
        require(revision>0 || payload=="[]")
        return FolderHead(revision,payload,sha)
    }
    @Synchronized internal fun read(s:TeamSession):FolderCheckpoint {
        val raw=vault(s).read() ?: return FolderCheckpoint()
        val row=JSONObject(raw)
        FolderPayload.keys(row,setOf("format","server","account","team","member","baseRevision","baseSha","pending","conflict","applying"))
        require(row.get("format") is Int && row.getInt("format")==1 && FolderPayload.text(row,"server")==s.server &&
            FolderPayload.text(row,"account")==s.principal.accountId && FolderPayload.text(row,"team")==s.principal.teamId &&
            FolderPayload.text(row,"member")==s.principal.memberId) { "本机文件夹同步记录的账号不匹配，原文件保留" }
        val revision=FolderPayload.number(row,"baseRevision")
        val sha=if(row.isNull("baseSha"))null else FolderPayload.text(row,"baseSha").also(FolderPayload::digest)
        require(sha!=null || revision==0L)
        val pending=if(row.isNull("pending"))null else row.getJSONObject("pending").let { p ->
            FolderPayload.keys(p,setOf("requestId","baseRevision","payload"))
            val id=FolderPayload.text(p,"requestId");require(UUID.fromString(id).toString()==id)
            val payload=FolderPayload.text(p,"payload");require(FolderPayload.encode(FolderPayload.decode(payload))==payload)
            FolderPending(id,FolderPayload.number(p,"baseRevision"),payload)
        }
        val conflict=if(row.isNull("conflict"))null else readHead(row.getJSONObject("conflict"))
        val applying=if(row.isNull("applying"))null else row.getJSONObject("applying").let { a ->
            FolderPayload.keys(a,setOf("expectedLocal","head"))
            FolderApply(FolderPayload.text(a,"expectedLocal").also(FolderPayload::digest),readHead(a.getJSONObject("head")))
        }
        require(applying==null || pending==null && conflict==null)
        return FolderCheckpoint(revision,sha,pending,conflict,applying)
    }
    @Synchronized internal fun write(s:TeamSession,value:FolderCheckpoint) {
        val row=JSONObject().put("format",1).put("server",s.server).put("account",s.principal.accountId)
            .put("team",s.principal.teamId).put("member",s.principal.memberId).put("baseRevision",value.baseRevision)
            .put("baseSha",value.baseSha ?: JSONObject.NULL)
            .put("pending",value.pending?.let { JSONObject().put("requestId",it.requestId).put("baseRevision",it.baseRevision).put("payload",it.payload) } ?: JSONObject.NULL)
            .put("conflict",value.conflict?.let(::head) ?: JSONObject.NULL)
            .put("applying",value.applying?.let { JSONObject().put("expectedLocal",it.expectedLocal).put("head",head(it.head)) } ?: JSONObject.NULL)
        vault(s).write(row.toString())
    }
}
