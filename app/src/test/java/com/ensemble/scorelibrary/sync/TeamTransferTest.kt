package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TeamTransferTest {
    private fun id()=UUID.randomUUID().toString()
    private fun gateway(server:String,device:String,session:TeamSession) = object:TeamAccountGateway {
        override val server=server;override val device=device
        override fun restore()=session
        override fun clear() {}
        override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
        override fun request(session:TeamSession,path:String,body:JSONObject?)=TeamAccountApi().request(server,path,device,session,body)
    }
    @Test fun realRangeRechecksGrants()=runBlocking {
        val source=listOf(File("../team-service/sync_interop_fixture.py"),File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val root=Files.createTempDirectory("range-http-").toFile()
        val process=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python",source.path,root.path,"--large","--trace")
            .redirectOutput(File(root,"out.log")).redirectError(File(root,"err.log")).start()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scopes=mutableListOf<TeamLibraryScope>();val collections=mutableListOf<TeamCollection>()
        try {
            val ready=File(root,"ready-port");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!ready.exists() && process.isAlive && System.nanoTime()<deadline) Thread.sleep(50)
            check(ready.exists()) { File(root,"err.log").readText().take(1500) }
            val server="http://127.0.0.1:${ready.readText().trim()}";val device=id();val api=TeamAccountApi()
            val session=api.login(server,device,"Range test","director","synthetic rehearsal passphrase")
            val client=TeamScoreClient(gateway(server,device,session));val a=client.catalog(session).artifacts.single()
            assertEquals(1000,a.pages);assertTrue(a.size>65536)
            val target=File(root,"partial.pdf")
            val pause=IOException("synthetic user pause")
            val stopped=runCatching { client.transfer(session,a,target) { done,_-> if(done>=65536)throw pause } }.exceptionOrNull()
            assertTrue(stopped is IOException);assertEquals(pause.message,stopped?.message)
            val offset=target.length();assertTrue(offset in 1 until a.size)
            val updates=mutableListOf<Long>()
            TeamScoreClient(gateway(server,device,session)).transfer(session,a,target) { done,total->assertEquals(a.size,total);updates+=done }
            assertEquals(offset,updates.first());assertEquals(a.size,updates.last());assertEquals(a.hash,TeamLibrarySync.hash(target))
            val requests=File(root,"ranges.jsonl").readLines().map(::JSONObject)
            assertTrue(requests[0].isNull("range"));assertEquals("bytes=$offset-",requests[1].getString("range"))
            assertTrue(requests.all { it.getString("ifMatch")=="\"${a.hash}\"" })
            // Exercise the actual queue, private Room database and a closed/reopened account,
            // rather than treating a standalone HTTP range check as the whole sync workflow.
            lateinit var activeQueue:TeamDownloads
            var pauseOnce=true
            val transport=object:TeamScoreTransport {
                override suspend fun catalog(session:TeamSession)=client.catalog(session)
                override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File)=client.download(session,artifact,target)
                override suspend fun transfer(session:TeamSession,artifact:TeamArtifact,target:File,progress:(Long,Long)->Unit) {
                    client.transfer(session,artifact,target) { done,total ->
                        if(pauseOnce && done in 16384 until total) { pauseOnce=false;activeQueue.pause() }
                        progress(done,total)
                    }
                }
            }
            val verifier=object:TeamPdfVerifier {
                override fun verify(file:File,pages:Int) {
                    val code="from pypdf import PdfReader; import sys; p=PdfReader(sys.argv[1],strict=True); assert not p.is_encrypted; assert len(p.pages)==int(sys.argv[2])"
                    val p=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python","-c",code,file.path,pages.toString())
                        .redirectOutput(File(root,"verify.log")).redirectError(File(root,"verify-error.log")).start()
                    try { check(p.waitFor(20,TimeUnit.SECONDS) && p.exitValue()==0) { File(root,"verify-error.log").readText().take(1000) } }
                    finally { if(p.isAlive) { p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS) } }
                }
            }
            val firstScope=TeamLibraryScope(context).also { scopes+=it };activeQueue=TeamDownloads(firstScope)
            val first=TeamLibrarySync(firstScope,transport,verifier,queue=activeQueue)
            first.activate(session);val old=firstScope.active.value!!.also { collections+=it }
            assertTrue(runCatching { first.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
            val prefix=activeQueue.partial(old,a).length();assertTrue(prefix in 1 until a.size)
            assertTrue(old.database.resolutionDao().versions().isEmpty())
            first.deactivate();old.database.close()
            val restoredScope=TeamLibraryScope(context).also { scopes+=it };activeQueue=TeamDownloads(restoredScope)
            val restored=TeamLibrarySync(restoredScope,transport,verifier,queue=activeQueue)
            restored.activate(session);val c=restoredScope.active.value!!.also { collections+=it }
            assertTrue(activeQueue.state.value.paused)
            val requestCount=File(root,"ranges.jsonl").readLines().size
            assertTrue(runCatching { restored.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
            assertEquals(requestCount,File(root,"ranges.jsonl").readLines().size)
            restored.resumeDownloads();assertEquals(1,restored.sync(session) { _,_-> })
            val actualResume=JSONObject(File(root,"ranges.jsonl").readLines().last())
            assertEquals("bytes=$prefix-",actualResume.getString("range"))
            val pdf=c.database.resolutionDao().versions().single()
            assertEquals(1000,pdf.pageCount);assertEquals(a.hash,TeamLibrarySync.hash(File(pdf.managedFilePath)))
            assertEquals(DownloadPhase.READY,activeQueue.state.value.rows.single().phase)
            api.request(server,"/v1/admin/grants",device,session,JSONObject().put("accountId",session.principal.accountId).put("artifactId",a.id).put("allowed",false))
            assertEquals(0,restored.sync(session) { _,_-> })
            assertTrue(activeQueue.state.value.rows.isEmpty())
            assertTrue(ScoreResolver(c.database.resolutionDao().snapshot()).resolvePiece(c.member,a.piece) is DomainResult.Failure)
            assertEquals(a.hash,TeamLibrarySync.hash(File(pdf.managedFilePath)))
            val other=File(root,"revoked.partial").apply { writeBytes(target.readBytes().take(offset.toInt()).toByteArray()) }
            assertEquals(404,(runCatching { client.transfer(session,a,other) { _,_-> } }.exceptionOrNull() as AccountRejected).status)
            assertEquals(offset,other.length());assertEquals(a.hash,TeamLibrarySync.hash(target))
        } finally {
            scopes.forEach { it.deactivate() };collections.distinctBy { it.database }.forEach { it.database.close() }
            collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { target ->
                check(target.parentFile==File(context.noBackupFilesDir,"team-libraries").canonicalFile)
                check(target.name.matches(Regex("[a-f0-9-]{36}")));target.deleteRecursively()
            }
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS) }
            check(root.canonicalFile.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile);root.deleteRecursively()
        }
    }
    private fun boundary(mode:String,verify:suspend (TeamScoreClient,TeamSession,TeamArtifact,File,ByteArray)->Unit)=runBlocking {
        val root=Files.createTempDirectory("range-boundary-").toFile()
        val bytes=ByteArray(160000) { (it%251).toByte() }
        val checksum=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val source=listOf(File("../team-service/download_boundary_fixture.py"),File("team-service/download_boundary_fixture.py")).first { it.isFile }.canonicalFile
        val process=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python",source.path,root.path,mode)
            .redirectOutput(File(root,"out.log")).redirectError(File(root,"err.log")).start()
        try {
        val ready=File(root,"ready-port");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(!ready.exists() && process.isAlive && System.nanoTime()<deadline) Thread.sleep(50)
        check(ready.exists()) { File(root,"err.log").readText().take(1500) }
        val device=id();val base="http://127.0.0.1:${ready.readText().trim()}"
        val session=TeamSession(base,"s".repeat(43),Long.MAX_VALUE,TeamPrincipal(id(),id(),id(),"Test","player","Player","member",true))
        val artifact=TeamArtifact(id(),"Boundary fixture","Violin",checksum,bytes.size.toLong(),1,"0101001","Test")
        verify(TeamScoreClient(gateway(base,device,session)),session,artifact,File(root,"partial").apply { writeBytes(bytes.take(1000).toByteArray()) },bytes)
        } finally {
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS) }
            check(root.canonicalFile.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile);root.deleteRecursively()
        }
    }
    @Test fun wrongRangeCannotAppend()=boundary("range") { client,session,a,file,_->
        assertTrue(runCatching { client.transfer(session,a,file) { _,_-> } }.exceptionOrNull() is TeamDownloadCorrupt)
        assertEquals(1000,file.length())
    }
    @Test fun wrongHashCannotAppend()=boundary("etag") { client,session,a,file,_->
        assertTrue(runCatching { client.transfer(session,a,file) { _,_-> } }.exceptionOrNull() is TeamDownloadCorrupt)
        assertEquals(1000,file.length())
    }
    @Test fun fullResponseReplacesPartial()=boundary("full") { client,session,a,file,bytes->
        client.transfer(session,a,file) { _,_-> };assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun interruptedBodyKeepsPrefix()=boundary("truncated") { client,session,a,file,bytes->
        assertTrue(runCatching { client.transfer(session,a,file) { _,_-> } }.isFailure)
        assertTrue(file.length() in 1001 until a.size);assertArrayEquals(bytes.take(file.length().toInt()).toByteArray(),file.readBytes())
    }
    @Test fun corruptCompleteFileIsRejected()=boundary("full") { client,session,a,file,bytes->
        file.writeBytes(bytes.apply { this[0]=99 })
        assertTrue(runCatching { client.transfer(session,a,file) { _,_-> } }.exceptionOrNull() is TeamDownloadCorrupt)
    }
}
