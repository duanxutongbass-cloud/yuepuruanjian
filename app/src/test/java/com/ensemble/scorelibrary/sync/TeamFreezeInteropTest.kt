package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.data.RoomScoreRepository
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** Real Python HTTP -> PDF/SHA -> Room -> frozen ink -> finite run -> revoke -> cold restore.
 * Native Android PDF rendering and physical tablets remain separate device checks. */
@RunWith(RobolectricTestRunner::class)
class TeamFreezeInteropTest {
    @Test fun httpFreezeFlow() = runBlocking {
        fun id() = UUID.randomUUID().toString()
        val source = listOf(File("../team-service/sync_interop_fixture.py"), File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val root = Files.createTempDirectory("tf-http-").toFile()
        val python = System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python"
        val process = ProcessBuilder(python, source.path, root.path).redirectOutput(File(root,"out.log"))
            .redirectError(File(root,"err.log")).start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val scopes = mutableListOf<TeamLibraryScope>()
        val collections = mutableListOf<TeamCollection>()
        val runs = PerformanceRuns()
        try {
            val ready = File(root,"ready-port"); val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!ready.exists() && process.isAlive && System.nanoTime()<deadline) Thread.sleep(50)
            check(ready.exists()) { File(root,"err.log").readText().take(1500) }
            val port = ready.readText().trim().toInt().also { check(it in 1..65535) }
            val server = "http://127.0.0.1:$port"; val device = id(); val api = TeamAccountApi()
            var now = System.currentTimeMillis()/1000
            val session = api.login(server,device,"Freeze test","director","synthetic rehearsal passphrase").let {
                api.request(server,"/v1/me/backup-consent",device,it,JSONObject().put("accepted",true))
                it.copy(principal=it.principal.copy(backupConsent=true),verifiedAt=now,lastSeenAt=now,deviceId=device)
            }
            var network = true
            var freezeDownloads = 0
            val gateway = object:TeamAccountGateway {
                override val server = server; override val device = device
                override fun restore() = session
                override fun clear() {}
                override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean) = session
                override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject {
                    check(network) { "Offline recovery attempted an HTTP request" }
                    if(path.startsWith("/v1/program-freezes/")) freezeDownloads++
                    return api.request(server,path,device,session,body)
                }
            }
            val client = TeamScoreClient(gateway)
            val verifier = object:TeamPdfVerifier {
                override fun verify(file:File,pages:Int) {
                    val script = "from pypdf import PdfReader; import sys; p=PdfReader(sys.argv[1],strict=True); assert not p.is_encrypted; assert len(p.pages)==int(sys.argv[2]); assert all(float(x.mediabox.width)>0 and float(x.mediabox.height)>0 for x in p.pages)"
                    val p = ProcessBuilder(python,"-c",script,file.path,pages.toString()).redirectOutput(File(root,"verify.log"))
                        .redirectError(File(root,"verify-error.log")).start()
                    try { check(p.waitFor(20,TimeUnit.SECONDS) && p.exitValue()==0) {
                        "Independent PDF verification failed: " + File(root,"verify-error.log").takeIf { it.isFile }?.readText()?.take(1200)
                    } }
                    finally { if(p.isAlive) { p.destroyForcibly(); p.waitFor(10,TimeUnit.SECONDS) } }
                }
            }
            val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val checkpoint = TeamOfflineStore(context.noBackupFilesDir,{secret},{now})
            val scope = TeamLibraryScope(context).also { scopes+=it }
            val cloud = TeamInkSync(scope,TeamInkClient(gateway)).also { it.automatic=false }
            val sync = TeamLibrarySync(scope,client,verifier,ink=cloud,offline=checkpoint)
            sync.activate(session); val c = scope.active.value!!.also { collections+=it }
            sync.sync(session) { _,_-> }
            val a = client.catalog(session).artifacts.single()
            val annotations = AnnotationRepository(local,scope,cloud)
            val page = AnnotationScope(c.member,a.piece,a.part,a.version,0)
            val shared = InkMark(kind=MarkKind.SYMBOL,points=listOf(InkPoint(.2f,.3f)),symbolScale=.75f)
            val personal = shared.copy(id=id(),symbolScale=1.5f)
            annotations.save(page,AnnotationLayer.PERSONAL,listOf(personal),0)
            annotations.save(page,AnnotationLayer.SHARED,listOf(shared),0)
            cloud.sync(c); annotations.publish(page)
            val programId = id()
            gateway.request(session,"/v1/admin/programs",JSONObject().put("id",programId).put("title","合成正式场次")
                .put("pieceKeys",JSONArray().put(a.pieceKey)).put("accountIds",JSONObject.NULL).put("archived",false).put("expectedRevision",0))
            val request = JSONObject().put("programId",programId).put("expectedRevision",1)
                .put("requestId",teamId("program-freeze/$programId/1"))
            val receipt = gateway.request(session,"/v1/admin/program-freezes",request)
            val replay = gateway.request(session,"/v1/admin/program-freezes",request)
            assertEquals(receipt.keys().asSequence().associateWith { receipt.get(it) },
                replay.keys().asSequence().associateWith { replay.get(it) })
            sync.sync(session) { _,_-> }
            val program = client.catalog(session).programs.single()
            val frozenPage = page.copy(item=program.item(a.pieceKey))
            assertTrue(annotations.load(frozenPage).locked)
            assertEquals(listOf(shared),annotations.load(frozenPage).shared)
            assertEquals(listOf(personal),annotations.load(frozenPage).personal)
            val saved = c.database.annotationDao().snapshot(program.concert)!!
            assertEquals(1,freezeDownloads)
            // Live publishing and a later same-number voice must not rewrite this programme.
            val currentShared = annotations.load(page).sharedGeneration
            annotations.save(page,AnnotationLayer.SHARED,emptyList(),currentShared)
            cloud.sync(c); annotations.publish(page)
            val sourceFile = File(c.database.resolutionDao().versions().single().managedFilePath)
            val job = TeamPublishJob(c.key,sourceFile,"0201001合成同步练习曲.pdf",a.hash,a.size,a.pages,
                TeamPublishDetails(a.title,"02 Cello","0201001",a.category,setOf(session.principal.accountId)))
            TeamPublisher(gateway).publish(session,job) { _,_-> }
            sync.sync(session) { _,_-> }
            assertEquals(saved,c.database.annotationDao().snapshot(program.concert))
            assertEquals(1,freezeDownloads)
            assertEquals(2,c.database.resolutionDao().snapshot().parts.size)
            assertEquals(emptyList<InkMark>(),annotations.load(page).shared)
            assertEquals(listOf(shared),annotations.load(frozenPage).shared)
            assertEquals(a.hash,TeamLibrarySync.hash(sourceFile))
            val concert = (RoomScoreRepository(local,scope).resolveConcert(c.member,program.concert) as DomainResult.Success).value
            assertEquals(a.part,concert.items.single().part.id)
            assertTrue(local.resolutionDao().pieces().isEmpty())
            // Close the database and restore from encrypted account-bound authorization, without HTTP.
            scope.deactivate(); c.database.close(); network=false
            val restoredScope = TeamLibraryScope(context).also { scopes+=it }
            val restoredSync = TeamLibrarySync(restoredScope,client,verifier,offline=checkpoint)
            restoredSync.activate(session.copy(offline=true))
            val restored = restoredScope.active.value!!.also { collections+=it }
            val restoredAnnotations = AnnotationRepository(local,restoredScope)
            assertEquals(saved,restored.database.annotationDao().snapshot(program.concert))
            val restoredConcert = (RoomScoreRepository(local,restoredScope).resolveConcert(restored.member,program.concert) as DomainResult.Success).value
            val engine = object:PdfEngine {
                override suspend fun inspect(segments:List<ResolvedScoreSegment>):List<PageSize> = segments.flatMap { segment ->
                    val file = File(segment.managedFilePath)
                    assertEquals(segment.fileHash,TeamLibrarySync.hash(file));verifier.verify(file,segment.endPage)
                    List(segment.endPage) { PageSize(595,842) }
                }
                override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame =
                    error("Native rendering is a separate Android device check")
            }
            val preparation = PerformancePreparation(engine,restoredAnnotations,restoredScope)
            val prepared = preparation.prepare(restored.member,restoredConcert.items,true) { _,_-> }
            val pending = preparation.prepare(restored.member,restoredConcert.items,true) { _,_-> }
            val run = runs.begin(restored.member,"synthetic-entered-scene",restoredConcert.items.single(),restoredConcert,prepared)
            assertEquals(listOf(shared),runs.page(run.id,frozenPage)!!.shared)
            assertEquals(listOf(personal),runs.page(run.id,frozenPage)!!.personal)
            // Actual server revocation immediately denies general reading and new transfers.
            network=true
            gateway.request(session,"/v1/admin/grants",JSONObject().put("accountId",session.principal.accountId)
                .put("artifactId",a.id).put("allowed",false))
            restoredSync.sync(session) { _,_-> }
            val current = restoredScope.project(restored,restored.database.resolutionDao().snapshot())
            assertTrue(ScoreResolver(current).resolvePart(restored.member,a.piece,a.part) is DomainResult.Failure)
            assertTrue(RoomScoreRepository(local,restoredScope).resolvePiece(restored.member,a.piece) is DomainResult.Success) // The later cello still has its own grant.
            assertTrue(RoomScoreRepository(local,restoredScope).resolveConcert(restored.member,program.concert) is DomainResult.Failure)
            assertTrue(runCatching { restoredAnnotations.load(frozenPage) }.isFailure)
            assertEquals(404,(runCatching { client.frozen(session,program,TeamCatalog(0,listOf(a),listOf(program))) }.exceptionOrNull() as AccountRejected).status)
            assertEquals(404,(runCatching { client.download(session,a,File(root,"denied.pending")) }.exceptionOrNull() as AccountRejected).status)
            assertTrue(runCatching { runs.begin(restored.member,"stale-prepared",restoredConcert.items.single(),restoredConcert,pending) }.isFailure)
            // The already entered run alone retains its exact prepared, read-only page.
            assertEquals(run,runs.current());assertEquals(listOf(shared),runs.page(run.id,frozenPage)!!.shared)
            assertEquals(a.hash,TeamLibrarySync.hash(sourceFile))
            runs.end(run.id);assertNull(runs.current());assertNull(runs.page(run.id,frozenPage))
            assertTrue(runCatching { preparation.prepare(restored.member,restoredConcert.items,true) { _,_-> } }.isFailure)
            // The saved revocation also wins after a second offline cold restore.
            restoredScope.deactivate();restored.database.close();network=false
            val afterScope = TeamLibraryScope(context).also { scopes+=it }
            val afterSync = TeamLibrarySync(afterScope,client,verifier,offline=checkpoint)
            afterSync.activate(session.copy(offline=true));val after = afterScope.active.value!!.also { collections+=it }
            assertTrue(after.database.resolutionDao().concerts().all { it.isArchived })
            assertTrue(RoomScoreRepository(local,afterScope).resolveConcert(after.member,program.concert) is DomainResult.Failure)
            assertEquals(a.hash,TeamLibrarySync.hash(sourceFile))
            assertNotNull(after.database.annotationDao().snapshot(program.concert)) // Historical data preserved, not new admission.
            now=session.expiresAt
            assertTrue(runCatching { afterSync.activate(session.copy(offline=true)) }.exceptionOrNull() is OfflineUnavailable)
            assertNull(afterScope.active.value)
        } finally {
            runs.end();scopes.forEach { it.deactivate() };local.close()
            collections.distinctBy { it.database }.forEach { it.database.close() }
            collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { target ->
                check(target.parentFile==File(context.noBackupFilesDir,"team-libraries").canonicalFile)
                check(target.name.matches(Regex("[a-f0-9-]{36}")));target.deleteRecursively()
            }
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS) }
            check(root.canonicalFile.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile);root.deleteRecursively()
        }
    }
}
