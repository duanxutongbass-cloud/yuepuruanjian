package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** Synthetic bytes isolate storage/queue/authorization behavior. Real PDF/HTTP is separately tested. */
@RunWith(RobolectricTestRunner::class)
class TeamDownloadsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val scopes=mutableListOf<TeamLibraryScope>()
    private val collections=mutableListOf<TeamCollection>()
    private val bytes=ByteArray(80000) { (it%251).toByte() }
    private val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun id()=UUID.randomUUID().toString()
    private val session=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,TeamPrincipal(id(),id(),id(),"Test","player","Player","member",true))
    private fun artifact(number:String)=TeamArtifact(id(),"合成 $number","Violin",hash,bytes.size.toLong(),1,number,"Test")
    private var catalog=TeamCatalog(1,emptyList())
    private var requests=0
    private var chunk:(TeamArtifact,Long)->Unit={_,_->}
    private var fetched:()->Unit={}
    private val transfers=mutableListOf<Pair<String,Long>>()
    private val client=object:TeamScoreTransport {
        override suspend fun catalog(session:TeamSession):TeamCatalog { requests++;fetched();return catalog }
        override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File) { error("Expected resumable transfer") }
        override suspend fun transfer(session:TeamSession,artifact:TeamArtifact,target:File,progress:(Long,Long)->Unit) {
            val offset=target.takeIf { it.isFile }?.length() ?: 0
            transfers+=artifact.id to offset
            java.io.FileOutputStream(target,true).use { output ->
                var done=offset.toInt();progress(done.toLong(),bytes.size.toLong())
                while(done<bytes.size) {
                    val count=minOf(16000,bytes.size-done);output.write(bytes,done,count);done+=count
                    chunk(artifact,done.toLong());progress(done.toLong(),bytes.size.toLong())
                }
                output.fd.sync()
            }
        }
    }
    private var verifyHook:()->Unit={}
    private val verifier=object:TeamPdfVerifier {
        override fun verify(file:File,pages:Int) { assertEquals(hash,TeamLibrarySync.hash(file)) }
        override fun verify(file:File,pages:Int,check:()->Unit) { verifyHook();check();verify(file,pages) }
    }
    private suspend fun setup(identity:TeamSession=session):Triple<TeamLibraryScope,TeamDownloads,TeamLibrarySync> {
        val scope=TeamLibraryScope(context).also { scopes+=it };val queue=TeamDownloads(scope)
        val sync=TeamLibrarySync(scope,client,verifier,queue=queue)
        sync.activate(identity);collections+=scope.active.value!!
        return Triple(scope,queue,sync)
    }
    @After fun cleanup() {
        scopes.forEach { it.deactivate() };collections.distinctBy { it.database }.forEach { it.database.close() }
        collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { target ->
            check(target.parentFile==File(context.noBackupFilesDir,"team-libraries").canonicalFile)
            check(target.name.matches(Regex("[a-f0-9-]{36}")));target.deleteRecursively()
        }
    }
    @Test fun pauseColdResume()=runBlocking {
        val a=artifact("0101001");val b=artifact("0101002");catalog=TeamCatalog(1,listOf(a,b))
        val (scope,queue,sync)=setup();val c=scope.active.value!!
        chunk={file,_->if(file.id==b.id)queue.pause()}
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
        assertEquals(a.size,queue.partial(c,a).length());assertEquals(16000,queue.partial(c,b).length())
        assertEquals(16000L,queue.state.value.rows.single { it.id==b.id }.received)
        assertTrue(c.database.resolutionDao().versions().isEmpty());assertTrue(queue.state.value.paused);assertFalse(queue.state.value.active)
        scope.deactivate();c.database.close();chunk={_,_->}
        val (restoredScope,restoredQueue,restored)=setup();val current=restoredScope.active.value!!
        assertTrue(restoredQueue.state.value.paused)
        val before=transfers.size;val auth=requests
        assertTrue(runCatching { restored.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
        assertTrue(requests>auth);assertEquals(before,transfers.size)
        restored.resumeDownloads();assertEquals(2,restored.sync(session) { _,_-> })
        assertEquals(listOf(a.id to a.size,b.id to 16000L),transfers.drop(before))
        assertTrue(restoredQueue.state.value.rows.all { it.phase==DownloadPhase.READY && it.received==it.total })
        assertFalse(restoredQueue.partial(current,a).exists());assertFalse(restoredQueue.partial(current,b).exists())
        assertTrue(current.database.resolutionDao().versions().all { TeamLibrarySync.hash(File(it.managedFilePath))==hash })
    }
    @Test fun pausedRevocation()=runBlocking {
        val a=artifact("0101001");catalog=TeamCatalog(1,listOf(a))
        val (scope,queue,sync)=setup();val c=scope.active.value!!;sync.sync(session) { _,_-> }
        val original=File(c.database.resolutionDao().versions().single().managedFilePath)
        val b=artifact("0101002");catalog=TeamCatalog(2,listOf(a,b));chunk={file,_->if(file.id==b.id)queue.pause()}
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
        val partial=queue.partial(c,b);assertTrue(partial.isFile)
        catalog=TeamCatalog(3,emptyList());val before=transfers.size
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
        assertTrue(queue.state.value.rows.isEmpty());assertFalse(partial.exists());assertEquals(before,transfers.size)
        assertTrue(ScoreResolver(c.database.resolutionDao().snapshot()).resolvePiece(c.member,a.piece) is DomainResult.Failure)
        assertEquals(hash,TeamLibrarySync.hash(original))
    }
    @Test fun corruptRetry()=runBlocking {
        val a=artifact("0101001");catalog=TeamCatalog(1,listOf(a))
        val (scope,queue,sync)=setup();val c=scope.active.value!!
        queue.partial(c,a).writeBytes(ByteArray(bytes.size))
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadCorrupt)
        assertFalse(queue.partial(c,a).exists());assertTrue(c.database.resolutionDao().versions().isEmpty())
        assertEquals(DownloadPhase.FAILED,queue.state.value.rows.single().phase)
        assertEquals(1,sync.sync(session) { _,_-> });assertEquals(0L,transfers.last().second)
    }
    @Test fun switchIgnoresLateProgress()=runBlocking {
        val a=artifact("0101001");catalog=TeamCatalog(1,listOf(a))
        val (scope,queue,sync)=setup();val first=scope.active.value!!
        val other=session.copy(principal=session.principal.copy(accountId=id(),memberId=id()))
        chunk={_,_->
            val c=scope.activate(other).also { collections+=it };queue.bind(c)
        }
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.isFailure)
        assertNotEquals(first.key,queue.state.value.key);assertTrue(queue.state.value.rows.isEmpty())
        assertTrue(scope.active.value!!.directory.listFiles().orEmpty().isEmpty())
        assertTrue(first.database.resolutionDao().versions().isEmpty())
    }
    @Test fun finalRevocationClearsQueue()=runBlocking {
        val a=artifact("0101001");catalog=TeamCatalog(1,listOf(a))
        val (scope,queue,sync)=setup();val c=scope.active.value!!
        chunk={_,_->catalog=TeamCatalog(2,emptyList())}
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamSyncFailure)
        assertTrue(queue.state.value.rows.isEmpty());assertFalse(queue.partial(c,a).exists())
        assertTrue(c.database.resolutionDao().versions().isEmpty());assertTrue(c.directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun cancelledBytesRemain()=runBlocking {
        val a=artifact("0101001");catalog=TeamCatalog(1,listOf(a))
        val (scope,queue,sync)=setup();val c=scope.active.value!!
        chunk={_,_->throw CancellationException("synthetic process interruption")}
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is CancellationException)
        assertEquals(16000,queue.partial(c,a).length());assertTrue(c.database.resolutionDao().versions().isEmpty())
        chunk={_,_->};sync.sync(session) { _,_-> };assertEquals(16000L,transfers.last().second)
    }
    @Test fun staleControlRejected()=runBlocking {
        val (_,queue,_)=setup()
        assertTrue(runCatching { queue.pause("other-account") }.isFailure)
        assertFalse(queue.state.value.paused)
        assertTrue(runCatching { queue.resume("other-account") }.isFailure)
    }
    @Test fun pauseDuringVerification()=runBlocking {
        val a=artifact("0101001");catalog=TeamCatalog(1,listOf(a))
        val (scope,queue,sync)=setup();val c=scope.active.value!!
        verifyHook={queue.pause()}
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamDownloadsPaused)
        assertEquals(a.size,queue.partial(c,a).length());assertTrue(c.database.resolutionDao().versions().isEmpty())
        verifyHook={};sync.resumeDownloads();assertEquals(1,sync.sync(session) { _,_-> })
        assertEquals(a.size,transfers.last().second)
    }
}
