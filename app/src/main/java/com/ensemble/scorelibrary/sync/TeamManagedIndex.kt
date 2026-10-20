package com.ensemble.scorelibrary.sync

import android.content.Context
import com.ensemble.scorelibrary.account.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

internal data class ManagedTeamOwner(val server:String,val team:String,val account:String,val member:String) {
    val key get()=teamId("$server/$team/$account")
    val localMember get()=teamId("$key/member/$member")
    fun session(source:TeamSession)=source.copy(server=server,principal=source.principal.copy(
        teamId=team,accountId=account,memberId=member),offline=true)
}

/** Explicit provenance only. A hashed directory name or matching PDF name is never an identity. */
@Singleton
class TeamManagedIndex internal constructor(root:File,private val key:()->SecretKey,
    private val offlineKey:()->SecretKey) {
    private val root=root.canonicalFile
    @Inject constructor(@ApplicationContext context:Context):this(context.noBackupFilesDir,
        {teamSecretKey("team-managed-identities-v1")},{teamSecretKey("team-offline-v1")})
    companion object { private val diskLock=Any() }
    private fun directory():File=File(root,"team-libraries").also {
        check(root.canonicalFile==root.absoluteFile && it.canonicalFile==it.absoluteFile)
        check(it.isDirectory || it.mkdirs())
    }
    private fun vault()=AccountVault(File(directory(),"managed-identities.enc"),8*1024*1024,key)
    private fun owner(row:JSONObject):ManagedTeamOwner {
        require(row.keys().asSequence().toSet()==setOf("server","team","account","member"))
        val s=row.get("server") as String
        require(accountServer(s)==s)
        fun uuid(name:String)=(row.get(name) as String).also { require(UUID.fromString(it).toString()==it) }
        return ManagedTeamOwner(s,uuid("team"),uuid("account"),uuid("member"))
    }
    private fun read():List<ManagedTeamOwner> {
        val raw=vault().read() ?: return emptyList()
        val row=JSONObject(raw)
        require(row.keys().asSequence().toSet()==setOf("format","owners") && row.get("format")==1)
        val rows=row.getJSONArray("owners");require(rows.length()<=10000)
        return (0 until rows.length()).map { owner(rows.getJSONObject(it)) }
            .also { require(it.map { o -> o.key }.distinct().size==it.size) }
    }
    private fun save(owners:List<ManagedTeamOwner>) {
        require(owners.size<=10000)
        vault().write(JSONObject().put("format",1).put("owners",JSONArray().apply { owners.sortedBy { it.key }.forEach { o ->
            put(JSONObject().put("server",o.server).put("team",o.team).put("account",o.account).put("member",o.member))
        } }).toString())
    }
    internal fun register(s:TeamSession):ManagedTeamOwner=synchronized(diskLock) {
        val o=owner(JSONObject().put("server",s.server).put("team",s.principal.teamId).put("account",s.principal.accountId).put("member",s.principal.memberId))
        val all=read();require(all.none { it.key==o.key && it!=o }) { "本机账号成员身份发生变化，旧缓存保留" }
        if(o !in all)save(all+o)
        o
    }
    internal fun owners(s:TeamSession):List<ManagedTeamOwner> = synchronized(diskLock) {
        val all=read().toMutableList()
        // 0.10.18 already bound its encrypted authorization to all four identity fields.
        // Discover only decryptable, canonical, exactly matching old checkpoints.
        directory().listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }.forEach { d ->
            require(d.canonicalFile==d.absoluteFile)
            if(all.none { it.key==d.name }) {
                val file=File(d,"authorization.enc")
                if(file.isFile) {
                    val row=runCatching { AccountVault(file,5*1024*1024,offlineKey).read()?.let(::JSONObject) }.getOrNull()
                    if(row!=null) {
                        val o=owner(JSONObject().put("server",row.get("server")).put("team",row.get("team"))
                            .put("account",row.get("account")).put("member",row.get("member")))
                        require(o.key==d.name) { "旧账号缓存目录与加密身份不匹配，未清理" }
                        all+=o
                    }
                }
            }
        }
        save(all)
        all.filter { it.server==s.server && it.team==s.principal.teamId }
    }
}
