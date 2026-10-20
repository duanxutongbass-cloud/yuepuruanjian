package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.ui.LibraryFolder
import com.ensemble.scorelibrary.ui.LibraryFolderStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class FolderSyncState(val member:String?=null,val busy:Boolean=false,val pending:Boolean=false,
    val conflict:Boolean=false,val message:String="",val changed:Long=0)
data class FolderComparison(val member:String,val local:List<LibraryFolder>,val remote:List<LibraryFolder>,
    val localFingerprint:String,val remoteRevision:Long,val remoteSha:String)

/** Local edits are durable first; one encrypted outbox survives every uncertain HTTP result. */
@Singleton
class TeamFolderSync @Inject constructor(private val scope:TeamLibraryScope,private val store:LibraryFolderStore,
    private val journal:TeamFolderJournal,private val client:TeamFolderTransport) {
    private val mutex=Mutex()
    private val background=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val requests=Channel<TeamCollection>(Channel.CONFLATED)
    private val mutable=MutableStateFlow(FolderSyncState())
    val state=mutable.asStateFlow()
    val active=scope.active
    internal var automatic=true
    init {
        background.launch { store.changes.collect { member -> scope.active.value?.takeIf { it.member==member }?.let(::schedule) } }
        background.launch { for(c in requests) { delay(750);try { sync(c) } catch(_:Exception) { /* durable state retained */ } } }
    }
    fun current()=scope.active.value
    fun schedule(c:TeamCollection?=current()) { if(automatic && c!=null)requests.trySend(c) }
    /** Serialized with network writes. Retired references also leave uncertain retries and recovery candidates. */
    internal suspend fun retire(s:TeamSession,member:String,pieces:Set<String>,guard:()->Unit)=withContext(Dispatchers.IO) { mutex.withLock {
        val expected=teamId(s.server+"/"+s.principal.teamId+"/"+s.principal.accountId)
        require(member==teamId("$expected/member/${s.principal.memberId}"));guard()
        store.retire(member,pieces,guard)
        val before=journal.read(s)
        fun head(h:FolderHead):FolderHead {
            val payload=store.sanitize(member,h.payload)
            return h.copy(payload=payload,sha256=FolderPayload.hash(payload))
        }
        val pending=before.pending?.let { p ->
            val payload=store.sanitize(member,p.payload)
            if(payload==p.payload)p else TeamFolderProtocol.pending(p.baseRevision,payload)
        }
        // An in-flight apply has a stale local fingerprint after pruning. Preserve its
        // non-retired content as a conflict candidate rather than overwrite newer local edits.
        val conflict=before.conflict?.let(::head) ?: before.applying?.head?.let(::head)
        val after=before.copy(pending=if(conflict==null)pending else null,conflict=conflict,applying=null)
        guard();journal.write(s,after)
    } }
    private fun session(c:TeamCollection):TeamSession {
        scope.requireCurrent(c)
        val s=checkNotNull(c.session)
        check(c.key==teamId(s.server+"/"+s.principal.teamId+"/"+s.principal.accountId) &&
            c.member==teamId("${c.key}/member/${s.principal.memberId}")) { "文件夹账号已变化" }
        return s
    }
    private fun save(c:TeamCollection,s:TeamSession,value:FolderCheckpoint) { session(c);journal.write(s,value) }
    private fun status(c:TeamCollection,value:FolderCheckpoint,message:String,busy:Boolean=false) {
        if(current()!==c)return
        val pending=value.pending!=null || value.applying!=null || runCatching { store.snapshot(c.member).sha256!=value.baseSha }.getOrDefault(true)
        mutable.value=FolderSyncState(c.member,busy,pending,value.conflict!=null,message,mutable.value.changed)
    }
    private fun changed(c:TeamCollection) {
        if(current()===c)mutable.value=mutable.value.copy(member=c.member,changed=mutable.value.changed+1)
    }
    private suspend fun head(c:TeamCollection,s:TeamSession):FolderHead {
        val reply=client.request(s);session(c);require(reply.status==200)
        return TeamFolderProtocol.head(reply.body,s)
    }
    private fun monotonic(value:FolderCheckpoint,head:FolderHead) {
        require(head.revision>=value.baseRevision &&
            (head.revision!=value.baseRevision || value.baseSha==null || head.sha256==value.baseSha)) { "文件夹云端版本回退，未覆盖本机内容" }
    }
    private fun recover(c:TeamCollection,s:TeamSession,value:FolderCheckpoint):FolderCheckpoint {
        val applying=value.applying ?: return value
        val local=store.snapshot(c.member)
        if(local.sha256!=applying.head.sha256) {
            val stamp=scope.preparationStamp(c)
            val done=store.replace(c.member,applying.head.payload,applying.expectedLocal) { commit -> scope.enterPrepared(c,stamp,commit) }
            if(!done) {
                // An edit won after the intent was saved; keep it and offer both snapshots.
                return value.copy(applying=null,conflict=applying.head).also { save(c,s,it) }
            }
            changed(c)
        }
        return FolderCheckpoint(applying.head.revision,applying.head.sha256).also { save(c,s,it) }
    }
    private fun apply(c:TeamCollection,s:TeamSession,value:FolderCheckpoint,head:FolderHead,fingerprint:String):FolderCheckpoint {
        val intent=value.copy(pending=null,conflict=null,applying=FolderApply(fingerprint,head))
        save(c,s,intent)
        return recover(c,s,intent)
    }
    suspend fun sync(c:TeamCollection)=withContext(Dispatchers.IO) { mutex.withLock {
        val s=session(c)
        var value=FolderCheckpoint()
        try {
            value=journal.read(s)
            status(c,value,"正在同步我的文件夹",true)
            value=recover(c,s,value)
            if(s.offline) { status(c,value,"文件夹已保存在本机，联网核验后继续同步");return@withLock }
            repeat(4) {
                ensureActive();session(c)
                if(value.conflict!=null) {
                    val latest=head(c,s);monotonic(value,latest)
                    require(latest.revision>=checkNotNull(value.conflict).revision) { "文件夹云端版本回退" }
                    value=value.copy(conflict=latest);save(c,s,value)
                    status(c,value,"两台设备的文件夹有不同修改，请查看并选择保留内容");return@withLock
                }
                val pending=value.pending
                if(pending!=null) {
                    val reply=client.request(s,pending.body());session(c)
                    if(reply.status==409) {
                        val receipt=TeamFolderProtocol.conflict(reply.body,s,pending)
                        val latest=head(c,s);monotonic(value,latest)
                        require(latest.revision>=receipt.revision && (latest.revision!=receipt.revision || latest.sha256==receipt.sha256))
                        value=value.copy(conflict=latest);save(c,s,value)
                        status(c,value,"云端已有其他设备的修改，本机内容和待同步记录均已保留");return@withLock
                    }
                    require(reply.status==200)
                    val receipt=TeamFolderProtocol.receipt(reply.body,s,pending)
                    value=FolderCheckpoint(receipt.revision,receipt.sha256);save(c,s,value)
                    // Never reapply the sent candidate: newer local edits may already exist.
                } else {
                    val latest=head(c,s);monotonic(value,latest)
                    val local=store.snapshot(c.member)
                    when {
                        local.sha256==latest.sha256 -> {
                            value=FolderCheckpoint(latest.revision,latest.sha256);save(c,s,value)
                            status(c,value,"我的文件夹已同步");return@withLock
                        }
                        value.baseSha==null && local.payload=="[]" || value.baseSha!=null && local.sha256==value.baseSha -> {
                            value=apply(c,s,value,latest,local.fingerprint)
                        }
                        latest.sha256==value.baseSha || value.baseSha==null && latest.revision==0L -> {
                            value=FolderCheckpoint(latest.revision,latest.sha256,TeamFolderProtocol.pending(latest.revision,local.payload));save(c,s,value)
                        }
                        else -> {
                            value=value.copy(conflict=latest);save(c,s,value)
                            status(c,value,"本机和云端都有不同文件夹，请查看并选择保留内容");return@withLock
                        }
                    }
                }
            }
            status(c,value,"本机仍有新修改，将在下次同步时继续")
            schedule(c)
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) {
            status(c,value,if(error is AccountRejected && error.status==401)"账号需要重新核验，文件夹和待同步记录保留"
                else "文件夹同步未完成，本机内容保留；请联网后重试")
            throw error
        } finally {
            if(current()===c)mutable.value=mutable.value.copy(busy=false)
        }
    } }
    suspend fun preview(c:TeamCollection):FolderComparison=withContext(Dispatchers.IO) { mutex.withLock {
        val s=session(c);val value=recover(c,s,journal.read(s))
        val remote=checkNotNull(value.conflict) { "当前没有待处理的文件夹冲突" }
        val local=store.snapshot(c.member)
        FolderComparison(c.member,FolderPayload.decode(local.payload),remote.folders(),local.fingerprint,remote.revision,remote.sha256)
    } }
    suspend fun resolve(c:TeamCollection,preview:FolderComparison,useLocal:Boolean)=withContext(Dispatchers.IO) {
        mutex.withLock {
            val s=session(c);if(s.offline)throw OfflineUnavailable("请联网核验后处理文件夹冲突")
            require(preview.member==c.member && store.fingerprint(c.member)==preview.localFingerprint) { "预览后本机文件夹已变化，请重新查看" }
            val value=journal.read(s);val conflict=checkNotNull(value.conflict)
            require(conflict.revision==preview.remoteRevision && conflict.sha256==preview.remoteSha) { "冲突记录已变化，请重新查看" }
            val latest=head(c,s);monotonic(value,latest)
            if(latest.revision!=preview.remoteRevision || latest.sha256!=preview.remoteSha) {
                save(c,s,value.copy(conflict=latest));error("预览后云端文件夹已变化，请重新查看")
            }
            val local=store.snapshot(c.member);require(local.fingerprint==preview.localFingerprint) { "预览后本机文件夹已变化，请重新查看" }
            if(useLocal)save(c,s,FolderCheckpoint(latest.revision,latest.sha256,TeamFolderProtocol.pending(latest.revision,local.payload)))
            else {
                val applied=apply(c,s,value,latest,preview.localFingerprint)
                check(applied.conflict==null) { "确认期间本机文件夹变化，已保留两边内容，请重新查看" }
            }
        }
        sync(c)
    }
}
