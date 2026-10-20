package com.ensemble.scorelibrary.annotation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class InkDraftStoreTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.cacheDir,"ink-recovery-${UUID.randomUUID()}").apply { mkdirs() }
    private val secret=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val storage=InkDraftStore(root,{secret})
    private val source=RecoverySource("team:synthetic-account","a".repeat(64))
    private val key=InkDraftKey(AnnotationScope("m","p","part","v",1),AnnotationLayer.PERSONAL,source.space)
    private val mark=InkMark(kind=MarkKind.SYMBOL,points=listOf(InkPoint(.2f,.3f)),symbol="fermata",symbolScale=1.5f)
    private fun write(marks:List<InkMark> = listOf(mark),generation:Long=0,before:String="[]")=InkDraftWrite(marks,generation,InkDraftStore.digest(before))
    private suspend fun rejected(block:suspend ()->Unit) { try { block();fail("Expected rejection") } catch(e:AssertionError) { throw e } catch(_:Exception) {} }
    @After fun cleanup() { val target=root.canonicalFile;check(target.parentFile==context.cacheDir.canonicalFile);target.deleteRecursively() }
    @Test fun encryptedRestart()=runBlocking {
        val first=write();val latest=write(listOf(mark,mark.copy(id="second")),1,InkCodec.encode(first.marks))
        val saved=storage.write(key,null,source,listOf(first,latest))
        assertEquals(saved,InkDraftStore(root,{secret}).read(key))
        val bytes=root.walkTopDown().single { it.extension=="enc" }.readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains("fermata"));assertFalse(bytes.contains("synthetic-account"));assertFalse(bytes.contains("sourceHash"))
        assertEquals(1.5f,saved.writes.last().marks.first().symbolScale)
    }
    @Test fun encryptedPressureRecoveryAndItsNextGenerationKeepExactSamples()=runBlocking {
        val pen=InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.1f,.2f),InkPoint(.8f,.6f)),pressures=listOf(.1f,.9f))
        val first=write(listOf(pen));val moved=pen.copy(points=pen.points.map { it.copy(y=it.y+.1f) })
        val latest=write(listOf(moved),1,InkCodec.encode(first.marks))
        storage.write(key,null,source,listOf(first,latest))
        val restored=InkDraftStore(root,{secret}).read(key)!!
        assertEquals(listOf(pen),restored.writes.first().marks)
        assertEquals(listOf(moved),restored.writes.last().marks)
        assertEquals(listOf(.1f,.9f),restored.writes.last().marks.single().pressures)
    }
    @Test fun scopeIsolation()=runBlocking {
        val saved=storage.write(key,null,source,listOf(write()))
        for(other in listOf(key.copy(space="local"),key.copy(scope=key.scope.copy(member="n")),key.copy(scope=key.scope.copy(version="other")),
            key.copy(scope=key.scope.copy(page=2)),key.copy(scope=key.scope.copy(item="concert-item")),key.copy(layer=AnnotationLayer.SHARED)))assertNull(storage.read(other))
        assertEquals(saved,storage.read(key))
    }
    @Test fun casAndExplicitClear()=runBlocking {
        val saved=storage.write(key,null,source,listOf(write()))
        rejected { storage.write(key,null,source,listOf(write(emptyList()))) }
        val claimed=storage.write(key,saved.token,source,saved.writes)
        assertNotEquals(saved.token,claimed.token)
        rejected { storage.write(key,saved.token,source,listOf(write(emptyList()))) }
        rejected { storage.clear(key,saved.token) }
        assertEquals(claimed,storage.read(key));storage.clear(key,claimed.token);assertNull(storage.read(key))
    }
    @Test fun invalidQueueAndIdentityNeverReplaceOriginal()=runBlocking {
        val first=storage.write(key,null,source,listOf(write()))
        rejected { storage.write(key,first.token,source,emptyList()) }
        rejected { storage.write(key,first.token,source,listOf(write(),write(generation=2))) }
        rejected { storage.write(key,first.token,source,listOf(write(generation=-1))) }
        rejected { storage.write(key,first.token,source.copy(sha256="invalid"),listOf(write())) }
        rejected { storage.write(key,first.token,source.copy(space="other"),listOf(write())) }
        rejected { storage.read(key.copy(scope=key.scope.copy(member="../invalid\u0000"))) }
        assertEquals(first,storage.read(key))
    }
    @Test fun corruptionRetainsFile()=runBlocking {
        storage.write(key,null,source,listOf(write()))
        val file=root.walkTopDown().single { it.extension=="enc" };val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        rejected { storage.read(key) };assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun movedCiphertextCannotChangeScope()=runBlocking {
        storage.write(key,null,source,listOf(write()))
        val original=root.walkTopDown().single { it.extension=="enc" }
        val other=key.copy(scope=key.scope.copy(page=2));storage.write(other,null,source,listOf(write()))
        val destination=root.walkTopDown().single { it.extension=="enc" && it!=original }
        destination.writeBytes(original.readBytes());rejected { storage.read(other) }
        assertNotNull(storage.read(key))
    }
    @Test fun concurrentOneWinner()=runBlocking {
        val results=(0..1).map { index -> async(Dispatchers.IO) {
            runCatching { storage.write(key,null,source,listOf(write(listOf(mark.copy(id="mark-$index"))))) }
        } }.awaitAll()
        assertEquals(1,results.count { it.isSuccess });assertEquals(results.single { it.isSuccess }.getOrThrow(),storage.read(key))
    }
    @Test fun boundedPagesPreserveExisting()=runBlocking {
        val records=(0 until 16).map { page -> val k=key.copy(scope=key.scope.copy(page=page));k to storage.write(k,null,source,listOf(write())) }
        rejected { storage.write(key.copy(scope=key.scope.copy(page=17)),null,source,listOf(write())) }
        val first=records.first();storage.write(first.first,first.second.token,source,listOf(write(emptyList())))
        assertTrue(storage.read(first.first)!!.writes.single().marks.isEmpty())
        assertEquals(16,root.walkTopDown().count { it.extension=="enc" })
    }
    @Test fun invalidInkCannotPublishUnreadableCheckpoint()=runBlocking {
        val saved=storage.write(key,null,source,listOf(write()))
        rejected { storage.write(key,saved.token,source,listOf(write(listOf(mark,mark)))) }
        assertEquals(saved,storage.read(key))
    }
}
