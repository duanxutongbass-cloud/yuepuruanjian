package com.ensemble.scorelibrary.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.reading.*
import com.ensemble.scorelibrary.ui.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class PurgeFlowTest {
    private val root=File(System.getProperty("java.io.tmpdir"),"purge-${UUID.randomUUID().toString().take(8)}").apply { check(mkdirs()) }
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val context=object:ContextWrapper(app) {
        override fun getNoBackupFilesDir()=root
        override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(root.name+name,mode)
    }
    private val key=SecretKeySpec(ByteArray(32) { 8 },"AES")
    private val now=System.currentTimeMillis()/1000
    private val s=TeamSession(TEAM_SERVICE_URL,"synthetic-token",now+3600,TeamPrincipal(
        UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Team","player","Player","admin",true),now,now,UUID.randomUUID().toString())
    private val other=s.copy(principal=s.principal.copy(accountId=UUID.randomUUID().toString(),memberId=UUID.randomUUID().toString()))
    private val scope=TeamLibraryScope(context)
    private val index=TeamManagedIndex(root,{key},{key})
    private val offline=TeamOfflineStore(root,{key},{now})
    private val reading=ReadingActivityStore(File(root,"reading")) { key }
    private val drafts=InkDraftStore(File(root,"drafts"),{key})
    private val recovery=InkRecovery(drafts)
    private val store=LibraryFolderStore(context)
    private val folderJournal=TeamFolderJournal(root,{key})
    private val cloud=TeamFolderSync(scope,store,folderJournal,object:TeamFolderTransport {
        override suspend fun request(session:TeamSession,body:JSONObject?):FolderHttpReply=error("No unrelated network IO")
    }).also { it.automatic=false }
    private val runs=PerformanceRuns()
    private fun journal()=TeamDeletionJournal(root,s.server,s.principal.teamId) { key }
    private val bytes="%PDF-synthetic lifecycle fixture".toByteArray()
    private val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun artifact(n:String)=TeamArtifact(UUID.randomUUID().toString(),"Score $n","Violin",hash,bytes.size.toLong(),1,n,"Category")
    private val a=artifact("0101001")
    private val keep=artifact("0101002")
    private val catalog=TeamCatalog(1,listOf(a,keep))
    private val collections=mutableListOf<TeamCollection>()
    private var calls=0
    private val storage=UUID.randomUUID().toString()
    private val request=UUID.randomUUID().toString()
    private fun coordinator(erase:TeamLocalDeletion=TeamLocalDeletion(),global:Boolean=true,events:TeamDeletionTransport?=null,ink:InkRecovery=recovery)=TeamDeletionCoordinator(root,scope,index,offline,reading,ink,cloud,runs,
        {journal()},events ?: TeamDeletionTransport { session,state ->
            calls++
            if(state.cursor==0L)TeamDeletionPage(s.server,storage,s.principal.teamId,session.principal.accountId,0,1,1,false,
                listOf(TeamDeletion(1,a.id,request,now)),listOf(TeamDeletionImpact(request,emptySet(),emptySet(),if(global)setOf(a.piece) else emptySet())))
            else TeamDeletionPage(s.server,storage,s.principal.teamId,session.principal.accountId,1,1,1,false,emptyList(),emptyList())
        },erase)
    private suspend fun install(session:TeamSession):TeamCollection {
        val client=object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession)=catalog
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File) { target.writeBytes(bytes) }
        }
        val sync=TeamLibrarySync(scope,client,object:TeamPdfVerifier { override fun verify(file:File,pages:Int) {} },offline=offline)
        sync.activate(session);sync.sync(session) { _,_-> }
        return scope.active.value!!.also { c -> collections+=c;index.register(session)
            reading.record(ReadingOwner("team:${c.key}",c.member),RecentReading(a.piece,a.part,"a".repeat(64),0))
            reading.favorite(ReadingOwner("team:${c.key}",c.member),a.piece,true)
            store.edit(c.member,LibraryFolder(id="folder",name="排练",pieces=setOf(a.piece,keep.piece),coverPiece=a.piece))
            folderJournal.write(session,FolderCheckpoint(pending=TeamFolderProtocol.pending(0,store.snapshot(c.member).payload)))
        }
    }
    private suspend fun file(c:TeamCollection,a:TeamArtifact)=File(c.database.resolutionDao().versions().single { it.id==a.version }.managedFilePath)
    @After fun clean() {
        scope.deactivate();collections.distinctBy { it.database }.forEach { it.database.close() }
        val target=root.canonicalFile
        check(target.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile && target.name.startsWith("purge-"))
        target.deleteRecursively()
    }
    @Test fun accounts()=runBlocking {
        val first=install(other);val active=install(s)
        val files=listOf(file(first,a),file(active,a))
        val draftKey=InkDraftKey(AnnotationScope(active.member,a.piece,a.part,a.version,0),AnnotationLayer.PERSONAL,"team:${active.key}")
        val draft=recovery.open(draftKey,RecoverySource(draftKey.space,a.hash))
        val mark=InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.1f,.2f),InkPoint(.2f,.3f)))
        recovery.await(recovery.submitEdits(draft,listOf(InkDraftEdit(listOf(mark),0,emptyList()))))
        coordinator().refresh(active)
        assertThrows(IllegalStateException::class.java) { recovery.submitEdits(draft,listOf(InkDraftEdit(listOf(mark),0,emptyList()))) }
        try { drafts.read(draftKey);fail() } catch(_:IllegalStateException) {}
        assertEquals(1L,journal().read().cursor);assertNull(journal().read().pending)
        for(c in listOf(first,active)) {
            assertEquals(listOf(keep.version),c.database.resolutionDao().versions().map { it.id })
            assertTrue(reading.read(ReadingOwner("team:${c.key}",c.member)).favorites.isEmpty())
            assertEquals(setOf(keep.piece),store.read(c.member).single().pieces)
            assertFalse(folderJournal.read(c.session!!).pending!!.payload.contains(a.piece))
            assertEquals(listOf(keep),offline.read(c.session!!).catalog.artifacts)
        }
        assertTrue(files.none { it.exists() });assertFalse(active.deletionChanging)
        val epoch=scope.readEpoch.value
        coordinator().refresh(active)
        assertEquals(epoch,scope.readEpoch.value) // Empty polling never hides/re-renders an unchanged preview.
        assertThrows(IllegalArgumentException::class.java) { coordinator().requireLive(active,catalog) }
        // A stale UI callback remains filtered after the coordinator releases its barrier.
        store.edit(active.member,LibraryFolder(id="late",name="旧页面",pieces=setOf(a.piece)))
        assertTrue(store.read(active.member).single { it.id=="late" }.pieces.isEmpty())
    }
    @Test fun failureRetry()=runBlocking {
        val c=install(s);val original=file(c,a)
        try { coordinator(TeamLocalDeletion { false }).refresh(c);fail() }
        catch(error:TeamCleanupFailure) { assertTrue(error.cause is IllegalStateException) }
        assertTrue(c.deletionChanging);assertEquals(0L,journal().read().cursor);assertNotNull(journal().read().pending)
        assertTrue(original.exists());assertThrows(IllegalStateException::class.java) { scope.requireCurrent(c) }
        coordinator().refresh(c)
        assertFalse(original.exists());assertFalse(c.deletionChanging);assertEquals(1L,journal().read().cursor)
    }
    private fun aliasedDrafts(remove:(File)->Boolean={it.delete()}):InkDraftStore {
        val aliased=object:ContextWrapper(context) { override fun getNoBackupFilesDir()=File(root,".") }
        return InkDraftStore(InkDraftStore.managedRoot(aliased),{key},remove)
    }
    @Test fun trustedAliasCleansNonemptyRetirementAndKeepsOtherDrafts()=runBlocking {
        val c=install(s)
        val canonical=InkDraftStore(InkDraftStore.managedRoot(context),{key})
        val draftKey=InkDraftKey(AnnotationScope(c.member,a.piece,a.part,a.version,0),AnnotationLayer.PERSONAL,"team:${c.key}")
        val otherKey=draftKey.copy(scope=draftKey.scope.copy(piece=keep.piece,part=keep.part,version=keep.version))
        val write=InkDraftWrite(listOf(InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.1f,.2f)))),0,InkDraftStore.digest("[]"))
        canonical.write(draftKey,null,RecoverySource(draftKey.space,a.hash),listOf(write))
        val otherDraft=canonical.write(otherKey,null,RecoverySource(otherKey.space,keep.hash),listOf(write))
        val aliased=aliasedDrafts();val ink=InkRecovery(aliased)
        try {
            coordinator(ink=ink).refresh(c)
            assertEquals(1L,journal().read().cursor);assertNull(journal().read().pending)
            assertEquals(listOf(keep.version),c.database.resolutionDao().versions().map { it.id })
            assertEquals(otherDraft,aliasedDrafts().read(otherKey))
            try { aliasedDrafts().read(draftKey);fail("Retired draft reopened") } catch(_:IllegalStateException) {}
            coordinator(ink=ink).refresh(c)
            assertEquals(1L,journal().read().cursor);scope.requireCurrent(c)
        } finally { ink.closeForTest() }
    }
    @Test fun trustedAliasFailureRetainsCheckpointThenColdActivationRetriesBeforePublish()=runBlocking {
        val c=install(s);val original=file(c,a);val kept=file(c,keep).readBytes()
        val canonical=InkDraftStore(InkDraftStore.managedRoot(context),{key})
        val draftKey=InkDraftKey(AnnotationScope(c.member,a.piece,a.part,a.version,0),AnnotationLayer.PERSONAL,"team:${c.key}")
        val write=InkDraftWrite(emptyList(),0,InkDraftStore.digest("[]"))
        canonical.write(draftKey,null,RecoverySource(draftKey.space,a.hash),listOf(write))
        val failInk=InkRecovery(aliasedDrafts { false })
        try { try { coordinator(ink=failInk).refresh(c);fail("Physical failure ignored") }
            catch(error:TeamCleanupFailure) { assertTrue(error.cause is IllegalStateException) } }
        finally { failInk.closeForTest() }
        assertNotNull(journal().read().pending);assertEquals(0L,journal().read().cursor)
        assertTrue(original.exists());assertTrue(c.deletionChanging)
        try { canonical.read(draftKey);fail("Retirement barrier was lost") } catch(_:IllegalStateException) {}
        scope.deactivate();assertNull(scope.active.value)
        val coldInk=InkRecovery(aliasedDrafts())
        val client=object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession)=catalog.copy(artifacts=listOf(keep))
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File) { target.writeBytes(bytes) }
        }
        val sync=TeamLibrarySync(scope,client,object:TeamPdfVerifier { override fun verify(file:File,pages:Int) {} },
            offline=offline,deletions=coordinator(ink=coldInk))
        try {
            sync.activate(s)
            val active=scope.active.value!!.also { collections+=it }
            assertEquals(1L,journal().read().cursor);assertNull(journal().read().pending)
            assertFalse(original.exists());assertArrayEquals(kept,file(active,keep).readBytes())
            assertEquals(listOf(keep.version),active.database.resolutionDao().versions().map { it.id })
            scope.requireCurrent(active)
            sync.sync(s){_,_->};assertEquals(1L,journal().read().cursor)
        } finally { coldInk.closeForTest() }
    }
    @Test fun legacyCheckpointUpgradeCleansBeforeColdPublication()=runBlocking {
        val old=install(s);val target=file(old,a);val other=file(old,keep);val kept=other.readBytes()
        val page=TeamDeletionPage(s.server,storage,s.principal.teamId,s.principal.accountId,0,1,1,false,
            listOf(TeamDeletion(1,a.id,request,now)),listOf(TeamDeletionImpact(request,emptySet(),emptySet(),setOf(a.piece))))
        journal().stage(page,s.principal.accountId)
        scope.deactivate()
        val upgraded=s.copy(principal=s.principal.copy(numberingProtocol=2))
        val cold=scope.openManaged(upgraded).also { collections+=it }
        coordinator().activate(cold)
        assertNull(scope.active.value)
        assertFalse(target.exists());assertArrayEquals(kept,other.readBytes())
        assertEquals(1L,journal().read().cursor);assertNull(journal().read().pending)
        assertEquals(listOf(keep.version),cold.database.resolutionDao().versions().map { it.id })
        assertEquals(listOf(keep),offline.read(s).catalog.artifacts)
        coordinator().activate(cold)
        scope.publish(cold);scope.requireCurrent(cold)
        assertEquals(setOf(a.piece),journal().read().pieces)
    }
    @Test fun coldStart()=runBlocking {
        val c=install(s);coordinator().refresh(c)
        scope.deactivate()
        val cold=scope.openManaged(s.copy(offline=true)).also { collections+=it }
        coordinator().activate(cold)
        assertNull(scope.active.value)
        assertEquals(listOf(keep),offline.read(s.copy(offline=true)).catalog.artifacts)
        scope.publish(cold);scope.requireCurrent(cold)
        assertEquals(setOf(a.piece),journal().read().pieces)
    }
    @Test fun localIsNotGlobal()=runBlocking {
        val c=install(s);coordinator(global=false).refresh(c)
        // This account lost its last part, but another account may still have another part.
        assertEquals(setOf(a.piece),reading.read(ReadingOwner("team:${c.key}",c.member)).favorites)
        assertEquals(setOf(a.piece,keep.piece),store.read(c.member).single().pieces)
    }
    @Test fun enteredRun()=runBlocking {
        val c=install(s)
        val resolved=ScoreResolver(c.database.resolutionDao().snapshot()).resolvePart(c.member,a.piece,a.part) as DomainResult.Success
        val item=resolved.value.copy(itemId=UUID.randomUUID().toString())
        val concert=ResolvedConcert(UUID.randomUUID().toString(),"Scene",listOf(item))
        val inkScope=AnnotationScope(c.member,a.piece,a.part,a.version,0,item.itemId)
        val data=AnnotationData(emptyList(),emptyList(),emptyList(),0,0,false,concert.id,true)
        val prepared=PreparedPerformance(1,mapOf(inkScope to data),c.key,c.expiresAt) { it() }
        val run=runs.begin(c.member,"scene",item,concert,prepared)
        assertSame(run,runs.current())
        coordinator().refresh(c)
        assertNull(runs.current());assertNull(runs.page(run.id,inkScope))
    }
    @Test fun legacyOwner()=runBlocking {
        val first=install(other)
        // Simulate upgrading an 0.10.18 install that has only encrypted authorizations.
        val indexFile=File(root,"team-libraries/managed-identities.enc")
        check(indexFile.delete())
        val c=install(s)
        assertTrue(index.owners(s).any { it.key==first.key })
        coordinator().refresh(c)
        assertEquals(listOf(keep.version),first.database.resolutionDao().versions().map { it.id })
        val bad=other.copy(principal=other.principal.copy(memberId=UUID.randomUUID().toString()))
        assertThrows(IllegalArgumentException::class.java) { index.register(bad) }
        Unit
    }
    @Test fun lateDownload()=runBlocking {
        var removed=false
        val events=TeamDeletionTransport { session,state ->
            if(!removed)TeamDeletionPage(s.server,storage,s.principal.teamId,session.principal.accountId,0,0,0,false,emptyList(),emptyList())
            else if(state.cursor==0L)TeamDeletionPage(s.server,storage,s.principal.teamId,session.principal.accountId,0,1,1,false,
                listOf(TeamDeletion(1,a.id,request,now)),listOf(TeamDeletionImpact(request,emptySet(),emptySet(),setOf(a.piece))))
            else TeamDeletionPage(s.server,storage,s.principal.teamId,session.principal.accountId,1,1,1,false,emptyList(),emptyList())
        }
        val client=object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession)=if(removed)catalog.copy(artifacts=listOf(keep)) else catalog
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File) {
                target.writeBytes(bytes);if(artifact.id==a.id)removed=true
            }
        }
        val sync=TeamLibrarySync(scope,client,object:TeamPdfVerifier { override fun verify(file:File,pages:Int) {} },
            offline=offline,folders=cloud,deletions=coordinator(events=events))
        sync.activate(s);val c=scope.active.value!!.also { collections+=it }
        try { sync.sync(s) { _,_-> };fail() } catch(_:TeamSyncFailure) {}
        assertTrue(c.database.resolutionDao().versions().isEmpty())
        assertTrue(c.directory.listFiles().orEmpty().none { it.extension in setOf("pdf","part") })
        assertEquals(1L,journal().read().cursor)
        sync.sync(s) { _,_-> }
        assertEquals(listOf(keep.version),c.database.resolutionDao().versions().map { it.id })
        assertEquals(listOf(keep),offline.read(s).catalog.artifacts)
    }
}
