package com.ensemble.scorelibrary.sync

import android.content.Context
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.InkRecovery
import com.ensemble.scorelibrary.pdf.PerformanceRuns
import com.ensemble.scorelibrary.reading.ReadingActivityStore
import com.ensemble.scorelibrary.reading.ReadingOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface TeamDeletionTransport { suspend fun page(s:TeamSession,state:TeamDeletionCheckpoint):TeamDeletionPage? }

class TeamCleanupFailure(cause:Exception):Exception("本机已删除内容的清理未完成，账号仍已登录。请重试加载曲库；清理完成前不会开放旧缓存，请勿重复彻底删除。",cause)

/** Null means a legacy server without deletion capability, never a failed deletion request. */
internal class HttpTeamDeletions:TeamDeletionTransport {
    override suspend fun page(s:TeamSession,state:TeamDeletionCheckpoint):TeamDeletionPage? {
        fun request(storage:String?,after:Long)=TeamAccountApi().request(s.server,"/v1/deletions",s.deviceId,s,JSONObject()
            .put("protocolVersion",1).put("storageId",storage ?: JSONObject.NULL).put("after",after.toString()).put("limit",100))
        var rebound=false
        val value=try { request(state.storage,state.cursor) }
        catch(e:AccountRejected) {
            if(e.status==404 && state.storage==null)return null
            if(e.status==409 && e.code=="deletion_storage_changed" && state.pending==null) { rebound=true;request(null,0) } else throw e
        }
        return TeamDeletionPage.parse(value,s.server,s.principal.teamId,s.principal.accountId,
            if(rebound)null else state.storage,if(rebound)0 else state.cursor)
    }
}

@Singleton
class TeamDeletionCoordinator internal constructor(root:File,private val scope:TeamLibraryScope,
    private val index:TeamManagedIndex,private val offline:TeamOfflineStore,private val reading:ReadingActivityStore,
    private val ink:InkRecovery,private val folders:TeamFolderSync,private val runs:PerformanceRuns,
    private val journalFactory:(TeamSession)->TeamDeletionJournal,private val transport:TeamDeletionTransport,
    private val erase:TeamLocalDeletion=TeamLocalDeletion()) {
    private val root=root.canonicalFile
    @Inject constructor(@ApplicationContext context:Context,scope:TeamLibraryScope,index:TeamManagedIndex,
        offline:TeamOfflineStore,reading:ReadingActivityStore,ink:InkRecovery,folders:TeamFolderSync,runs:PerformanceRuns):
        this(context.noBackupFilesDir,scope,index,offline,reading,ink,folders,runs,
            { s -> TeamDeletionJournal(context.noBackupFilesDir,s.server,s.principal.teamId) { teamSecretKey("team-deletions-v1") } },HttpTeamDeletions())
    private val mutex=Mutex()
    internal fun requireLive(c:TeamCollection,catalog:TeamCatalog) {
        val retired=journalFactory(checkNotNull(c.session)).read()
        require(catalog.artifacts.none { it.id in retired.retired } && catalog.programs.none { it.freezeId in retired.freezes }) {
            "服务清单包含已彻底删除的内容，未重新下载或授权；请重新同步"
        }
    }
    /** Runs before publication of a cold account, including offline activation. */
    internal suspend fun activate(c:TeamCollection)=withContext(Dispatchers.IO) { mutex.withLock {
        val s=checkNotNull(c.session);index.register(s)
        val journal=journalFactory(s)
        c.deletionChanging=true
        clean(c,journal) { check(scope.active.value==null) { "清理期间账号已切换" } }
        c.deletionChanging=false
    } }
    /** The library sync mutex serializes this with transfers and installation. */
    internal suspend fun refresh(c:TeamCollection)=withContext(Dispatchers.IO) { mutex.withLock {
        val s=checkNotNull(c.session);require(!s.offline);scope.requireActive(c)
        val journal=journalFactory(s)
        if(journal.read().pending!=null || c.deletionChanging) {
            if(!c.deletionChanging)scope.beginDeletion(c)
            clean(c,journal) { scope.requireActive(c) }
            scope.endDeletion(c)
        }
        repeat(5000) {
            val before=journal.read()
            val page=transport.page(s,before) ?: return@withLock
            scope.requireActive(c)
            if(page.deletions.isEmpty()) {
                journal.stage(page,s.principal.accountId,allowRebind=true)
                journal.acknowledge(page)
                return@withLock
            }
            scope.beginDeletion(c)
            journal.stage(page,s.principal.accountId,allowRebind=true)
            clean(c,journal) { scope.requireActive(c) }
            scope.endDeletion(c)
            if(!page.more)return@withLock
        }
        error("删除通知超过本次同步上限，请继续同步")
    } }
    private suspend fun clean(active:TeamCollection,journal:TeamDeletionJournal,identity:()->Unit) {
        try { cleanLocal(active,journal,identity) }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(error:TeamCleanupFailure) { throw error }
        catch(error:Exception) { throw TeamCleanupFailure(error) }
    }
    private suspend fun cleanLocal(active:TeamCollection,journal:TeamDeletionJournal,identity:()->Unit) {
        identity()
        val state=journal.read()
        if(state.retired.isEmpty()) { state.pending?.let { journal.acknowledge(it) };return }
        val s=checkNotNull(active.session)
        val owners=index.owners(s)
        require(owners.any { it.key==active.key })
        for(owner in owners) {
            identity()
            val c=if(owner.key==active.key)active else scope.openManaged(owner.session(s))
            require(c.directory.absoluteFile==File(root,"team-libraries/${c.key}/scores").absoluteFile &&
                c.directory.canonicalFile==c.directory.absoluteFile)
            c.deletionChanging=true
            val guard={ identity();check(c.deletionChanging && journal.read().retired.containsAll(state.retired)) }
            val versions=state.retired.map { teamId("version/$it") }.toSet()
            val current=runs.current()
            if(current?.owner==c.key) {
                val manifest=c.database.annotationDao().snapshot(current.concert.id)?.manifest
                val freeze=manifest?.takeIf { it.startsWith("team-freeze-v1|") }?.split('|',limit=6)?.getOrNull(1)
                if(current.concert.items.any { item -> item.pieceId in state.pieces || item.segments.any { it.versionId in versions } } ||
                    current.concert.id in state.programs.map { teamId("concert/$it") } || freeze in state.freezes)runs.end(current.id)
            }
            val old=c.database.resolutionDao().snapshot()
            val pdfs=state.retired.map { teamId("pdf/$it") }.toSet()
            val parts=old.bindings.filter { it.scorePdfId in pdfs }.map { it.piecePartId }.toSet()
            ink.retireVersions("team:${c.key}",c.member,versions,guard)
            offline.retire(checkNotNull(c.session),state.retired,state.programs,state.freezes,guard)
            reading.retire(ReadingOwner("team:${c.key}",c.member),state.pieces,parts,guard)
            folders.retire(checkNotNull(c.session),c.member,state.pieces,guard)
            // Old partial names cannot be reversed into an artifact identity. Under the
            // transfer barrier, remove only this identified account's uninstalled partials.
            c.directory.listFiles().orEmpty().filter { it.name.matches(Regex("resume-[a-f0-9-]{36}\\.part")) }.forEach { file ->
                guard();require(file.isFile && file.canonicalFile==file.absoluteFile && file.parentFile==c.directory)
                check(file.delete() || !file.exists()) { "下载暂存清理未完成，请重试" }
            }
            state.retired.toList().chunked(100).forEach { erase.purge(c,it.toSet(),journal) }
            guard()
        }
        state.pending?.let { identity();journal.acknowledge(it) }
    }
}
