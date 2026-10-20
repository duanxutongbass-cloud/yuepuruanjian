package com.ensemble.scorelibrary.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TeamInkSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val teamId = UUID.randomUUID().toString()
    private val artifact = TeamArtifact(UUID.randomUUID().toString(), "Synthetic piece", "Violin", "a".repeat(64), 1, 2, "0101001", "Test")
    private val collections = mutableListOf<TeamCollection>()
    private val roots = mutableListOf<File>()
    private fun session(role: String = "member", consent: Boolean = true) = TeamSession("https://synthetic.invalid", "synthetic-redacted-token", Long.MAX_VALUE,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), teamId, "Synthetic ensemble", "tester", "Player", role, consent))
    private fun mark(id: String = UUID.randomUUID().toString()) = InkMark(id, MarkKind.PEN, listOf(InkPoint(.2f,.3f), InkPoint(.7f,.8f)))

    private inner class Server : TeamInkTransport {
        val heads = mutableMapOf<String, JSONObject>()
        val operations = mutableMapOf<String, InkHttpReply>()
        val posts = mutableListOf<String>()
        var granted = true
        var fail = false
        var afterCommit: suspend () -> Unit = {}
        fun owner(s: TeamSession, kind: String) = if (kind == "personal") s.principal.accountId else "shared"
        fun key(s: TeamSession, page: Int, kind: String) = "${owner(s,kind)}/$page/$kind"
        fun head(s: TeamSession, page: Int, kind: String) = heads[key(s,page,kind)] ?: JSONObject().put("revision",0).put("marks",JSONArray())
        override suspend fun request(session: TeamSession, path: String, body: JSONObject?): InkHttpReply {
            if (fail) throw IOException("Synthetic offline")
            if (path == "/v1/annotations") {
                val rows=JSONArray()
                if (granted) heads.forEach { (key,value) ->
                    val bits=key.split('/');val kind=bits[2]
                    if (kind == "published" || kind == "draft" && session.principal.role=="admin" ||
                        kind == "personal" && bits[0]==session.principal.accountId && session.principal.backupConsent)
                        rows.put(JSONObject().put("artifactId",artifact.id).put("artifactHash",artifact.hash)
                            .put("pageIndex",bits[1].toInt()).put("kind",kind).put("revision",value.getLong("revision")))
                }
                return InkHttpReply(200,JSONObject().put("protocolVersion",1).put("accountId",session.principal.accountId).put("teamId",teamId).put("pages",rows))
            }
            if (path=="/v1/annotation-backups") {
                val rows=JSONArray()
                heads.forEach { (key,value) -> val bits=key.split('/');if(bits[0]==session.principal.accountId && bits[2]=="personal")
                    rows.put(JSONObject().put("artifactId",artifact.id).put("artifactHash",artifact.hash).put("pageIndex",bits[1].toInt())
                        .put("revision",value.getLong("revision")).put("title",artifact.title).put("partName",artifact.partName)) }
                return InkHttpReply(200,JSONObject().put("pages",rows))
            }
            if(path.startsWith("/v1/annotation-backups/")) return InkHttpReply(200,head(session,path.substringAfterLast('/').toInt(),"personal"))
            if(!granted) throw AccountRejected(404,"not_found")
            val parts=path.split('/'); val page=parts[4].toInt(); val kind=parts[5]
            if(kind=="personal" && !session.principal.backupConsent || kind=="draft" && session.principal.role!="admin" || kind=="published" && body!=null && session.principal.role!="admin") throw AccountRejected(403,"forbidden")
            val current=head(session,page,kind)
            if(body==null) return InkHttpReply(200,JSONObject(current.toString()).put("artifactId",artifact.id).put("artifactHash",artifact.hash)
                .put("pageIndex",page).put("kind",kind).put("accountId",session.principal.accountId))
            val request=session.principal.accountId+"/"+body.getString("requestId");posts+=request
            operations[request]?.let { return InkHttpReply(it.status,JSONObject(it.body.toString())) }
            val revision=current.getLong("revision")
            val result=if(body.getLong("baseRevision")!=revision) InkHttpReply(409,JSONObject().put("error","annotation_conflict")
                .put("requestId",body.getString("requestId")).put("current",JSONObject(current.toString())).put("candidate",body.getJSONArray("marks")))
            else InkHttpReply(200,JSONObject().put("requestId",body.getString("requestId")).put("revision",revision+1).put("marks",body.getJSONArray("marks")))
            if(result.status==200) heads[key(session,page,kind)]=JSONObject().put("revision",revision+1).put("marks",body.getJSONArray("marks"))
            operations[request]=InkHttpReply(result.status,JSONObject(result.body.toString()))
            afterCommit()
            return result
        }
    }
    private inner class Device(val context: Context, val scope: TeamLibraryScope, val c: TeamCollection, val cloud: TeamInkSync, val scores: TeamLibrarySync) {
        val repo=AnnotationRepository(c.database,scope,cloud)
        fun page(index:Int=0)=AnnotationScope(c.member,artifact.piece,artifact.part,artifact.version,index)
    }
    private suspend fun device(server:Server,s:TeamSession,existing:Context?=null):Device {
        val ctx=existing?:object:ContextWrapper(context) {
            val root=File(checkNotNull(System.getProperty("java.io.tmpdir")),"ink-${UUID.randomUUID().toString().take(8)}").apply { mkdirs();roots+=this }
            override fun getNoBackupFilesDir()=root
        }
        val scope=TeamLibraryScope(ctx)
        val scores=TeamLibrarySync(scope,object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession)=TeamCatalog(1,if(server.granted)listOf(artifact) else emptyList())
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File) {target.writeText("x")}
        },object:TeamPdfVerifier {override fun verify(file:File,pages:Int) {}})
        // The score test fixture installs already-verified synthetic metadata; ink tests use real Room.
        val member=scores.activate(s);val c=scope.active.value!!;collections+=c
        val dao=c.database.libraryDao()
        dao.insertPartDefinition(com.ensemble.scorelibrary.core.database.entity.PartDefinitionEntity(id=artifact.definition,name=artifact.partName))
        dao.insertPiece(com.ensemble.scorelibrary.core.database.entity.PieceEntity(id=artifact.piece,title=artifact.title))
        dao.insertPiecePart(com.ensemble.scorelibrary.core.database.entity.PiecePartEntity(id=artifact.part,pieceId=artifact.piece,partDefinitionId=artifact.definition))
        dao.insertScorePdf(com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity(id=artifact.pdf,title=artifact.title))
        dao.insertScorePdfVersion(com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity(id=artifact.version,scorePdfId=artifact.pdf,versionNumber=1,
            managedFilePath=File(c.directory,"synthetic.pdf").path,originalUri="team-artifact:${artifact.id}",fileHash=artifact.hash,fileSizeBytes=1,pageCount=2))
        dao.updateScorePdf(com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity(id=artifact.pdf,title=artifact.title,activeVersionId=artifact.version))
        dao.insertPartScoreBinding(com.ensemble.scorelibrary.core.database.entity.PartScoreBindingEntity(piecePartId=artifact.part,scorePdfId=artifact.pdf,startPage=1,endPage=2))
        dao.insertExtraPermission(com.ensemble.scorelibrary.core.database.entity.MemberPiecePartPermissionEntity(memberId=member,piecePartId=artifact.part))
        val cloud=TeamInkSync(scope,server).also {it.automatic=false}
        return Device(ctx,scope,c,cloud,scores)
    }
    @After fun cleanup() {collections.forEach {it.database.close()};roots.forEach {it.deleteRecursively()} }

    @Test fun accounts()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val b=device(server,s);val other=device(server,session())
        val mark=mark();a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(mark),0)
        a.cloud.sync(a.c);b.cloud.sync(b.c);other.cloud.sync(other.c)
        assertEquals(listOf(mark),b.repo.load(b.page()).personal)
        assertTrue(other.repo.load(other.page()).personal.isEmpty())
        assertEquals(1,server.operations.size)
    }
    @Test fun legacyBaselineRecoversPressureWithoutAcceptingAnotherSameRevisionRewrite()=runBlocking {
        val server=Server();val session=session();val flat=mark()
        server.heads[server.key(session,0,"personal")]=JSONObject().put("revision",7).put("marks",JSONArray(InkCodec.encode(listOf(flat))))
        val a=device(server,session);a.cloud.sync(a.c)
        assertEquals(listOf(flat),a.repo.load(a.page()).personal)
        val pressure=flat.copy(pressures=listOf(.1f,.9f))
        server.heads[server.key(session,0,"personal")]=JSONObject().put("revision",7).put("marks",JSONArray(InkCodec.encode(listOf(pressure))))
        a.cloud.sync(a.c)
        assertEquals(listOf(pressure),a.repo.load(a.page()).personal);assertTrue(server.posts.isEmpty())
        server.heads[server.key(session,0,"personal")]=JSONObject().put("revision",7).put("marks",JSONArray(InkCodec.encode(listOf(pressure.copy(color=0xff0000ff)))))
        try { a.cloud.sync(a.c);fail("A same-revision content rewrite must remain rejected") } catch(_:IllegalArgumentException) {}
        assertEquals(listOf(pressure),a.repo.load(a.page()).personal)
    }
    @Test fun recoverySourceUsesActiveCollectionEvenWhenConstructorAliasesTeamDatabase()=runBlocking {
        val server=Server();val a=device(server,session())
        val source=a.repo.load(a.page()).recoverySource!!
        assertEquals(RecoverySource("team:${a.c.key}",artifact.hash),source)
        val mother=Room.inMemoryDatabaseBuilder(context,ScoreLibraryDatabase::class.java).build()
        try {
            assertEquals(source,AnnotationRepository(mother,a.scope).load(a.page()).recoverySource)
            a.scope.deactivate()
            assertTrue("A formerly selected team DB must not become a local library after logout",runCatching { a.repo.load(a.page()) }.isFailure)
            assertTrue(runCatching { a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(mark()),0,source) }.isFailure)
        } finally { mother.close() }
    }
    @Test fun conflictLocal()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val b=device(server,s)
        a.cloud.sync(a.c);b.cloud.sync(b.c)
        val first=mark();val second=mark()
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(first),0);a.cloud.sync(a.c)
        b.repo.save(b.page(),AnnotationLayer.PERSONAL,listOf(second),0);b.cloud.sync(b.c)
        val conflict=b.repo.load(b.page()).personalConflict!!
        assertEquals(listOf(second),conflict.local);assertEquals(listOf(first),conflict.remote)
        val requests=server.posts.size;b.cloud.sync(b.c);assertEquals(requests,server.posts.size)
        b.cloud.resolve(b.c,artifact.version,0,conflict,true);b.cloud.sync(b.c);a.cloud.sync(a.c)
        assertEquals(listOf(second),a.repo.load(a.page()).personal)
        assertNull(b.repo.load(b.page()).personalConflict)
        assertEquals(3,server.operations.size)
    }
    @Test fun cloudChoice()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val b=device(server,s)
        val first=mark();val second=mark()
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(first),0);a.cloud.sync(a.c)
        b.repo.save(b.page(),AnnotationLayer.PERSONAL,listOf(second),0);b.cloud.sync(b.c)
        val conflict=b.repo.load(b.page()).personalConflict!!
        b.cloud.resolve(b.c,artifact.version,0,conflict,false)
        assertEquals(listOf(first),b.repo.load(b.page()).personal)
        val page=b.c.database.annotationDao().page(artifact.version,0,b.c.member)!!
        val sql=b.c.database.openHelper.readableDatabase.query("SELECT payload FROM annotation_revisions WHERE documentId=?",arrayOf(page.id))
        sql.use {assertTrue(it.moveToFirst());assertEquals(listOf(second),InkCodec.decode(it.getString(0)))}
        try {b.cloud.resolve(b.c,artifact.version,0,conflict,true);fail()}catch(_:IllegalStateException){}
    }
    @Test fun lostAck()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val mark=mark()
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(mark),0)
        server.afterCommit={throw IOException("Synthetic lost acknowledgment")}
        try {a.cloud.sync(a.c);fail()}catch(_:IOException){}
        val pending=a.c.database.annotationDao().cloudPages().single().requestId!!
        a.c.database.close()
        val reopenedScope=TeamLibraryScope(a.context);val reopened=reopenedScope.activate(s);collections+=reopened
        val cloud=TeamInkSync(reopenedScope,server).also {it.automatic=false}
        server.afterCommit={};cloud.sync(reopened)
        assertEquals(1,server.operations.size);assertEquals(2,server.posts.size)
        assertTrue(server.posts.all {it.endsWith(pending)})
        assertNull(reopened.database.annotationDao().cloudPages().single().requestId)
        assertEquals(listOf(mark),AnnotationRepository(reopened.database,reopenedScope,cloud).load(a.page()).personal)
    }
    @Test fun editDuringUpload()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val b=device(server,s)
        val first=mark();val second=mark()
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(first),0)
        server.afterCommit={server.afterCommit={};a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(first,second),1)}
        a.cloud.sync(a.c);b.cloud.sync(b.c)
        assertEquals(listOf(first,second),b.repo.load(b.page()).personal)
        val generation=a.repo.load(a.page()).personalGeneration
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,emptyList(),generation);a.cloud.sync(a.c);b.cloud.sync(b.c)
        assertTrue(b.repo.load(b.page()).personal.isEmpty());assertEquals(3,server.operations.size)
    }
    @Test fun lateAck()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val mark=mark()
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(mark),0)
        var other:TeamCollection?=null
        server.afterCommit={other=a.scope.activate(session());collections+=other!!}
        try{a.cloud.sync(a.c);fail()}catch(_:IllegalStateException){}
        assertTrue(other!!.database.annotationDao().pages().isEmpty())
        assertNotNull(a.c.database.annotationDao().cloudPages().single().requestId)
        assertEquals(listOf(mark),InkCodec.decode(a.c.database.annotationDao().pages().single().payload))
    }
    @Test fun consentRevoke()=runBlocking {
        val server=Server();val s=session(consent=false);val a=device(server,s);val mark=mark()
        a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(mark),0);a.cloud.sync(a.c)
        assertTrue(server.posts.isEmpty());assertTrue(a.c.database.annotationDao().cloudPages().isEmpty())
        a.c.session=s.copy(principal=s.principal.copy(backupConsent=true));a.cloud.sync(a.c)
        assertEquals(1,server.posts.size)
        server.granted=false
        a.c.database.libraryDao().updateExtraPermission(a.c.database.resolutionDao().extraPermissions().single().copy(isArchived=true))
        a.cloud.sync(a.c);assertEquals(1,server.posts.size)
        val backup=JSONObject(a.cloud.personalBackup(a.c))
        assertEquals(1,backup.getJSONArray("pages").length());assertEquals(1,backup.getJSONArray("localSavedPages").length())
    }
    @Test fun sharedPublish()=runBlocking {
        val server=Server();val admin=device(server,session("admin"));val member=device(server,session())
        val shared=mark();admin.repo.save(admin.page(),AnnotationLayer.SHARED,listOf(shared),0);admin.cloud.sync(admin.c);member.cloud.sync(member.c)
        assertTrue(member.repo.load(member.page()).shared.isEmpty())
        admin.repo.publish(admin.page());member.cloud.sync(member.c)
        assertEquals(listOf(shared),member.repo.load(member.page()).shared)
        assertFalse(member.repo.load(member.page()).admin)
        try{member.repo.save(member.page(),AnnotationLayer.SHARED,emptyList(),0);fail()}catch(_:IllegalStateException){}
        admin.repo.save(admin.page(),AnnotationLayer.PERSONAL,listOf(mark()),0);admin.cloud.sync(admin.c);member.cloud.sync(member.c)
        assertEquals(listOf(shared),member.repo.load(member.page()).shared)
        assertTrue(member.repo.load(member.page()).personal.isEmpty())
    }
    @Test fun writingLease()=runBlocking {
        val server=Server();val s=session();val a=device(server,s);val b=device(server,s)
        b.cloud.sync(b.c)
        val lease=b.cloud.hold(b.page(),AnnotationLayer.PERSONAL)
        val first=mark();a.repo.save(a.page(),AnnotationLayer.PERSONAL,listOf(first),0);a.cloud.sync(a.c)
        b.cloud.sync(b.c);assertTrue(b.repo.load(b.page()).personal.isEmpty())
        b.cloud.release(lease);b.cloud.sync(b.c)
        assertEquals(listOf(first),b.repo.load(b.page()).personal)
    }
}
