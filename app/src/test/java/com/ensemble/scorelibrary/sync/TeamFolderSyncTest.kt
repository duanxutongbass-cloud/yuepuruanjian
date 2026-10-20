package com.ensemble.scorelibrary.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class TeamFolderSyncTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val session=TeamSession(TEAM_SERVICE_URL,"synthetic-token",System.currentTimeMillis()/1000+3600,TeamPrincipal(
        UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Team","tester","Player","member",false))
    private val devices=mutableListOf<Device>()
    private fun folder(name:String="排练")=LibraryFolder(id="folder",name=name,pieces=setOf("opaque-piece"),coverPiece="opaque-piece")
    private class Server:TeamFolderTransport {
        val heads=mutableMapOf<String,FolderHead>()
        val receipts=mutableMapOf<String,FolderHttpReply>()
        val posts=mutableListOf<String>()
        var fail=false
        var afterCommit:suspend ()->Unit={}
        var beforePost:suspend ()->Unit={}
        var afterGet:suspend ()->Unit={}
        fun head(s:TeamSession)=heads[s.principal.accountId] ?: FolderHead(0,"[]",FolderPayload.hash("[]"))
        fun put(s:TeamSession,folders:List<LibraryFolder>) {
            val payload=FolderPayload.encode(folders)
            heads[s.principal.accountId]=FolderHead(head(s).revision+1,payload,FolderPayload.hash(payload))
        }
        private fun owner(s:TeamSession)=JSONObject().put("protocolVersion",1).put("accountId",s.principal.accountId).put("teamId",s.principal.teamId)
        override suspend fun request(session:TeamSession,body:JSONObject?):FolderHttpReply {
            if(fail)throw IOException("Synthetic disconnected")
            if(body==null) {
                val h=head(session)
                val reply=FolderHttpReply(200,owner(session).put("revision",h.revision).put("payload",h.payload).put("sha256",h.sha256))
                afterGet();return reply
            }
            val request=session.principal.accountId+"/"+body.getString("requestId");posts+=request
            receipts[request]?.let { return it }
            beforePost();val h=head(session)
            val reply=if(body.getLong("baseRevision")!=h.revision)FolderHttpReply(409,owner(session).put("requestId",body.getString("requestId"))
                .put("error","folder_conflict").put("currentRevision",h.revision).put("currentSha256",h.sha256))
            else {
                put(session,FolderPayload.decode(body.getJSONArray("folders").toString()))
                val committed=head(session)
                FolderHttpReply(200,owner(session).put("requestId",body.getString("requestId")).put("revision",committed.revision).put("sha256",committed.sha256))
            }
            receipts[request]=reply;afterCommit();return reply
        }
    }
    private data class Device(val root:File,val context:Context,val key:SecretKey,val scope:TeamLibraryScope,val c:TeamCollection,
        val store:LibraryFolderStore,val journal:TeamFolderJournal,val cloud:TeamFolderSync)
    private fun device(server:Server,s:TeamSession=session):Device {
        val root=File(checkNotNull(System.getProperty("java.io.tmpdir")),"fold-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        val context=object:ContextWrapper(app) {
            override fun getNoBackupFilesDir()=root
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(root.name+"-"+name,mode)
        }
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val scope=TeamLibraryScope(context);val c=scope.activate(s)
        val store=LibraryFolderStore(context);val journal=TeamFolderJournal(root,{key})
        val cloud=TeamFolderSync(scope,store,journal,server).also { it.automatic=false }
        return Device(root,context,key,scope,c,store,journal,cloud).also { devices+=it }
    }
    @After fun clean() { devices.forEach { d -> d.scope.deactivate();d.c.database.close()
        val target=d.root.canonicalFile;check(target.parentFile==File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile && target.name.startsWith("fold-"));target.deleteRecursively()
    } }
    private suspend fun rejected(block:suspend ()->Unit) { try { block();fail("Expected rejection") } catch(e:AssertionError) { throw e } catch(_:Exception) {} }
    @Test fun twoDevices()=runBlocking {
        val server=Server();val a=device(server);val b=device(server)
        a.store.edit(a.c.member,folder());a.cloud.sync(a.c);b.cloud.sync(b.c)
        assertEquals(listOf(folder()),b.store.read(b.c.member));assertEquals(1L,server.head(session).revision)
        a.store.edit(a.c.member,null,"folder");a.cloud.sync(a.c);b.cloud.sync(b.c)
        assertTrue(b.store.read(b.c.member).isEmpty());assertEquals(2L,server.head(session).revision)
        assertFalse(b.cloud.state.value.pending)
    }
    @Test fun newDeviceKeepsCloud()=runBlocking {
        val server=Server();server.put(session,listOf(folder("云端")))
        val d=device(server);d.cloud.sync(d.c)
        assertEquals(listOf(folder("云端")),d.store.read(d.c.member));assertTrue(server.posts.isEmpty())
        val old=device(server);old.store.edit(old.c.member,folder("本机旧记录"));old.cloud.sync(old.c)
        assertTrue(old.cloud.state.value.conflict);assertEquals(listOf(folder("本机旧记录")),old.store.read(old.c.member))
    }
    @Test fun isolateAccounts()=runBlocking {
        val server=Server();val a=device(server);a.store.edit(a.c.member,folder());a.cloud.sync(a.c)
        val other=session.copy(principal=session.principal.copy(accountId=UUID.randomUUID().toString(),memberId=UUID.randomUUID().toString(),role="admin"))
        val b=device(server,other);b.cloud.sync(b.c)
        assertTrue(b.store.read(b.c.member).isEmpty());assertEquals(0L,server.head(other).revision)
        assertEquals(listOf(folder()),a.store.read(a.c.member))
    }
    @Test fun retryNewEdits()=runBlocking {
        val server=Server();val d=device(server);d.store.edit(d.c.member,folder("先上传"))
        server.afterCommit={d.store.edit(d.c.member,folder("上传时又编辑"));throw IOException("Lost reply")}
        rejected { d.cloud.sync(d.c) };val pending=d.journal.read(session).pending!!
        assertEquals(1L,server.head(session).revision);assertEquals(listOf(folder("上传时又编辑")),d.store.read(d.c.member))
        server.afterCommit={}
        val reopened=TeamFolderSync(d.scope,d.store,TeamFolderJournal(d.root,{d.key}),server).also { it.automatic=false }
        reopened.sync(d.c)
        assertEquals(server.posts[0],server.posts[1]);assertTrue(server.posts[1].endsWith(pending.requestId))
        assertEquals(2L,server.head(session).revision);assertEquals(listOf(folder("上传时又编辑")),server.head(session).folders())
        assertNull(d.journal.read(session).pending);assertFalse(reopened.state.value.pending)
    }
    @Test fun conflictChoices()=runBlocking {
        for(useLocal in listOf(true,false)) {
            val server=Server();val a=device(server);val b=device(server)
            a.cloud.sync(a.c);b.cloud.sync(b.c)
            a.store.edit(a.c.member,folder("A 的修改"));a.cloud.sync(a.c)
            b.store.edit(b.c.member,folder("B 的修改"));b.cloud.sync(b.c)
            assertTrue(b.cloud.state.value.conflict)
            val preview=b.cloud.preview(b.c)
            assertEquals(listOf(folder("B 的修改")),preview.local);assertEquals(listOf(folder("A 的修改")),preview.remote)
            b.cloud.resolve(b.c,preview,useLocal);a.cloud.sync(a.c)
            val chosen=folder(if(useLocal)"B 的修改" else "A 的修改")
            assertEquals(listOf(chosen),a.store.read(a.c.member));assertEquals(listOf(chosen),b.store.read(b.c.member))
            assertFalse(b.cloud.state.value.conflict)
        }
    }
    @Test fun stalePreview()=runBlocking {
        val server=Server();val d=device(server);d.cloud.sync(d.c)
        d.store.edit(d.c.member,folder("本机"));server.put(session,listOf(folder("云端")));d.cloud.sync(d.c)
        val preview=d.cloud.preview(d.c);d.store.edit(d.c.member,folder("预览后新改"))
        rejected { d.cloud.resolve(d.c,preview,false) };assertEquals(listOf(folder("预览后新改")),d.store.read(d.c.member))
        val fresh=d.cloud.preview(d.c);server.put(session,listOf(folder("云端又改")))
        rejected { d.cloud.resolve(d.c,fresh,true) }
        assertEquals(listOf(folder("预览后新改")),d.store.read(d.c.member));assertEquals(listOf(folder("云端又改")),server.head(session).folders())
        assertEquals(listOf(folder("云端又改")),d.cloud.preview(d.c).remote)
    }
    @Test fun casRace()=runBlocking {
        val server=Server();val d=device(server);d.cloud.sync(d.c);d.store.edit(d.c.member,folder("本人"))
        server.beforePost={server.put(session,listOf(folder("别台")));server.beforePost={}}
        d.cloud.sync(d.c)
        assertTrue(d.cloud.state.value.conflict);assertNotNull(d.journal.read(session).pending)
        assertEquals(listOf(folder("本人")),d.store.read(d.c.member));assertEquals(listOf(folder("别台")),d.cloud.preview(d.c).remote)
        val prior=d.journal.read(session).pending!!.requestId
        d.cloud.resolve(d.c,d.cloud.preview(d.c),true)
        assertNotEquals(prior,server.posts.last().substringAfterLast('/'))
        assertEquals(listOf(folder("本人")),server.head(session).folders())
    }
    @Test fun applyRecovery()=runBlocking {
        for(stage in 0..2) {
            val server=Server();val d=device(server);d.store.edit(d.c.member,folder("旧"))
            val expected=d.store.fingerprint(d.c.member);server.put(session,listOf(folder("接收")));val remote=server.head(session)
            d.journal.write(session,FolderCheckpoint(applying=FolderApply(expected,remote)))
            if(stage==1)d.store.edit(d.c.member,folder("接收"))
            if(stage==2)d.store.edit(d.c.member,folder("中断后新修改"))
            d.c.session=session.copy(offline=true);d.cloud.sync(d.c)
            if(stage==2) {
                assertEquals(listOf(folder("中断后新修改")),d.store.read(d.c.member));assertTrue(d.cloud.state.value.conflict)
            } else {
                assertEquals(listOf(folder("接收")),d.store.read(d.c.member));assertEquals(1L,d.journal.read(session).baseRevision)
            }
            assertNull(d.journal.read(session).applying);assertTrue(server.posts.isEmpty())
        }
    }
    @Test fun offlineAndRollback()=runBlocking {
        val server=Server();val d=device(server);d.cloud.sync(d.c)
        d.store.edit(d.c.member,folder());d.c.session=session.copy(offline=true);server.fail=true;d.cloud.sync(d.c)
        assertTrue(d.cloud.state.value.pending);assertTrue(server.posts.isEmpty())
        d.c.session=session;server.fail=false;d.cloud.sync(d.c);val before=d.store.read(d.c.member)
        server.heads.clear();rejected { d.cloud.sync(d.c) };assertEquals(before,d.store.read(d.c.member));assertEquals(1L,d.journal.read(session).baseRevision)
    }
    @Test fun lateAccount()=runBlocking {
        val server=Server();server.put(session,listOf(folder("迟到")));val d=device(server)
        server.afterGet={d.scope.deactivate()}
        rejected { d.cloud.sync(d.c) };assertTrue(d.store.read(d.c.member).isEmpty());assertEquals(FolderCheckpoint(),d.journal.read(session))
    }
    @Test fun pausedQueueFolders()=runBlocking {
        val server=Server();val d=device(server);d.cloud.automatic=true
        val scores=TeamLibrarySync(d.scope,object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession)=TeamCatalog(1,emptyList())
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File)=error("No PDF download expected")
        },object:TeamPdfVerifier { override fun verify(file:File,pages:Int)=error("No PDF expected") },folders=d.cloud)
        scores.activate(session);val c=d.scope.active.value!!;scores.pauseDownloads()
        d.store.edit(c.member,folder("只同步文件夹"))
        try { scores.sync(session) { _,_-> };fail("PDF queue must remain paused") } catch(_:TeamDownloadsPaused) {}
        withTimeout(10000) { while(d.cloud.state.value.member!=c.member || d.cloud.state.value.message!="我的文件夹已同步" || d.cloud.state.value.busy)delay(50) }
        assertTrue(scores.downloads.value.paused);assertEquals(listOf(folder("只同步文件夹")),server.head(session).folders())
    }
}
