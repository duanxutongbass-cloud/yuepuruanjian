package com.ensemble.scorelibrary.annotation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class InkRecoveryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.cacheDir,"ink-worker-${UUID.randomUUID()}").apply { mkdirs() }
    private val secret=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val fail=AtomicBoolean(false)
    private var block:CountDownLatch?=null
    private var entered:CountDownLatch?=null
    private val storage=InkDraftStore(root,{
        entered?.countDown()
        check(block?.await(10,TimeUnit.SECONDS)!=false) { "Test disk gate timed out" }
        if(fail.get())throw IOException("Synthetic unavailable disk")
        secret
    })
    private val recovery=InkRecovery(storage)
    private val source=RecoverySource("local","a".repeat(64))
    private val key=InkDraftKey(AnnotationScope("member","piece","part","v",0),AnnotationLayer.PERSONAL,source.space)
    private fun write(id:String,generation:Long=0,before:List<InkMark> = emptyList())=InkDraftWrite(
        listOf(InkMark(id=id,kind=MarkKind.PEN,points=listOf(InkPoint(.1f,.2f)))),generation,InkDraftStore.digest(InkCodec.encode(before)))
    private suspend fun rejected(block:suspend ()->Unit) {
        try { block();fail("Expected rejection") } catch(e:AssertionError) { throw e } catch(_:Exception) {}
    }
    @After fun cleanup()=runBlocking {
        block?.countDown();recovery.closeForTest()
        // Wait for any cancelled IO to leave the storage lock before deleting the isolated fixture.
        runCatching { storage.read(key) }
        val target=root.canonicalFile;check(target.parentFile==context.cacheDir.canonicalFile);target.deleteRecursively();Unit
    }
    @Test fun coalescesWhileDiskIsSlowAndRetirementStillFlushes()=runBlocking {
        val session=recovery.open(key,source)
        block=CountDownLatch(1);entered=CountDownLatch(1)
        val first=write("first");recovery.submit(session,listOf(first))
        assertTrue(withContext(Dispatchers.IO) { entered!!.await(5,TimeUnit.SECONDS) })
        var last:InkReceipt?=null
        repeat(13) { last=recovery.submit(session,listOf(first,write("new-$it",1,first.marks))) }
        recovery.release(session)
        rejected { recovery.submit(session,listOf(first)) }
        block!!.countDown()
        withTimeout(10000) { recovery.await(last!!) }
        assertEquals("new-12",storage.read(key)!!.writes.last().marks.single().id)
        assertEquals(2,storage.read(key)!!.writes.size)
    }
    @Test fun coldReaderClaimsTokenAndKeepsOriginalQueue()=runBlocking {
        val saved=storage.write(key,null,source,listOf(write("unsaved")))
        val session=recovery.open(key,source)
        assertEquals(saved.writes,session.recovered!!.writes)
        assertNotEquals(saved.token,session.recovered.token)
        rejected { storage.clear(key,saved.token) }
        recovery.release(session)
        assertEquals("unsaved",storage.read(key)!!.writes.single().marks.single().id)
    }
    @Test fun baselineHashesAreProducedByBackgroundWorker()=runBlocking {
        val session=recovery.open(key,source)
        val first=write("first");val latest=write("latest",1,first.marks)
        recovery.await(recovery.submitEdits(session,listOf(InkDraftEdit(first.marks,0,emptyList()),InkDraftEdit(latest.marks,1,first.marks))))
        assertEquals(listOf(first,latest),storage.read(key)!!.writes)
    }
    @Test fun lateClearCannotEraseNewEdits()=runBlocking {
        val session=recovery.open(key,source)
        recovery.await(recovery.submit(session,listOf(write("old"))))
        block=CountDownLatch(1);entered=CountDownLatch(1)
        recovery.submit(session,emptyList())
        assertTrue(withContext(Dispatchers.IO) { entered!!.await(5,TimeUnit.SECONDS) })
        val latest=recovery.submit(session,listOf(write("new")))
        block!!.countDown();withTimeout(10000) { recovery.await(latest) }
        assertEquals("new",storage.read(key)!!.writes.single().marks.single().id)
    }
    @Test fun failedDiskKeepsLastDurableCheckpointAndManualRetry()=runBlocking {
        val session=recovery.open(key,source)
        recovery.await(recovery.submit(session,listOf(write("durable"))))
        fail.set(true)
        rejected { withTimeout(10000) { recovery.await(recovery.submit(session,listOf(write("latest")))) } }
        fail.set(false)
        assertEquals("durable",storage.read(key)!!.writes.single().marks.single().id)
        recovery.await(recovery.submit(session,listOf(write("latest"))))
        assertEquals("latest",storage.read(key)!!.writes.single().marks.single().id)
    }
    @Test fun takeoverRetiresPreviousAndFlushesItsAcceptedEdits()=runBlocking {
        val old=recovery.open(key,source)
        block=CountDownLatch(1);entered=CountDownLatch(1)
        recovery.submit(old,listOf(write("latest")))
        assertTrue(withContext(Dispatchers.IO) { entered!!.await(5,TimeUnit.SECONDS) })
        val next=async { recovery.open(key,source) }
        yield();assertFalse(next.isCompleted)
        block!!.countDown()
        val restored=withTimeout(10000) { next.await() }
        rejected { recovery.submit(old,emptyList()) }
        assertEquals("latest",restored.recovered!!.writes.single().marks.single().id)
    }
    @Test fun changedPdfPreservesOldInkUntilExplicitDiscard()=runBlocking {
        val saved=storage.write(key,null,source,listOf(write("old-pdf")))
        val session=recovery.open(key,source.copy(sha256="b".repeat(64)))
        assertEquals(source,session.recovered!!.source)
        rejected { recovery.submit(session,listOf(write("different-pdf"))) }
        assertEquals(saved.writes,storage.read(key)!!.writes)
        recovery.await(recovery.submit(session,emptyList()))
        assertNull(storage.read(key))
        recovery.await(recovery.submit(session,listOf(write("new-pdf"))))
        assertEquals("b".repeat(64),storage.read(key)!!.source.sha256)
    }
    @Test fun unrelatedAccountCannotClearJournal()=runBlocking {
        val session=recovery.open(key,source)
        recovery.await(recovery.submit(session,listOf(write("private"))))
        val other=recovery.open(key.copy(scope=key.scope.copy(member="other")),source)
        recovery.await(recovery.submit(other,emptyList()))
        assertEquals("private",storage.read(key)!!.writes.single().marks.single().id)
    }
    @Test fun invalidSourceHashCannotStartAnEditableRecoverySession()=runBlocking {
        rejected { recovery.open(key,source.copy(sha256="invalid")) }
        assertNull(storage.read(key))
    }
    @Test fun externalClaimRefusesStaleSaveAndClear()=runBlocking {
        val session=recovery.open(key,source)
        recovery.await(recovery.submit(session,listOf(write("original"))))
        val old=storage.read(key)!!
        val claim=storage.write(key,old.token,source,old.writes)
        rejected { recovery.await(recovery.submit(session,listOf(write("stale")))) }
        rejected { recovery.await(recovery.submit(session,emptyList())) }
        assertEquals(claim,storage.read(key))
    }
}
