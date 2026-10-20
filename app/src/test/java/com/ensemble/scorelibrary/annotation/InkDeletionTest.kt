package com.ensemble.scorelibrary.annotation

import com.ensemble.scorelibrary.account.AccountVault
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class InkDeletionTest {
    @get:Rule val temp=TemporaryFolder()
    private val secret=SecretKeySpec(ByteArray(32){9},"AES")
    private val source=RecoverySource("team:verified-account","a".repeat(64))
    private val version=UUID.randomUUID().toString()
    private val key=InkDraftKey(AnnotationScope("member","piece","part",version,0),AnnotationLayer.PERSONAL,source.space)
    private val write=InkDraftWrite(listOf(InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.1f,.2f)))),0,InkDraftStore.digest("[]"))
    private fun store(remove:(File)->Boolean={it.delete()})=InkDraftStore(temp.root,{secret},remove)
    private suspend fun reject(block:suspend ()->Unit) {
        try { block();fail("Expected rejection") } catch(e:AssertionError){throw e} catch(_:Exception){}
    }
    private fun drafts()=temp.root.listFiles()!!.filter { it.isDirectory && it.name!="retired-v1" }.flatMap { it.listFiles()!!.toList() }

    @Test fun allPagesLayersAndScenesKeepOtherOwners()=runBlocking {
        val store=store()
        val targets=listOf(key,key.copy(scope=key.scope.copy(page=1)),key.copy(layer=AnnotationLayer.SHARED),
            key.copy(scope=key.scope.copy(item="scene")))
        targets.forEach { store.write(it,null,source,listOf(write)) }
        val others=listOf(key.copy(scope=key.scope.copy(version=UUID.randomUUID().toString())),
            key.copy(scope=key.scope.copy(member="other")),key.copy(space="team:other"),key.copy(space="local"))
        val saved=others.associateWith { store.write(it,null,source.copy(space=it.space),listOf(write)) }
        val keep=drafts().map { it to it.readBytes() }.toMap()
        assertEquals(4,store.retireVersions(key.space,key.scope.member,setOf(version)) {})
        targets.forEach { reject { store().read(it) };reject { store().write(it,null,source,listOf(write)) } }
        saved.forEach { (k,record) -> assertEquals(record,store().read(k)) }
        drafts().forEach { assertArrayEquals(keep[it],it.readBytes()) }
        assertEquals(0,store().retireVersions(key.space,key.scope.member,setOf(version)) {})
    }
    @Test fun physicalFailurePersistsBarrierAndRetryKeepsUnrelatedInk()=runBlocking {
        val store=store();val old=store.write(key,null,source,listOf(write))
        val other=key.copy(scope=key.scope.copy(version=UUID.randomUUID().toString()))
        val unrelated=store.write(other,null,source,listOf(write))
        val failStore=store { false }
        reject { failStore.retireVersions(key.space,key.scope.member,setOf(version)) {} }
        reject { store().read(key) };reject { store().write(key,old.token,source,listOf(write)) };reject { store().clear(key,old.token) }
        assertEquals(unrelated,store().read(other))
        assertEquals(1,store().retireVersions(key.space,key.scope.member,setOf(version)) {})
        assertEquals(unrelated,store().read(other))
    }
    @Test fun invalidCiphertextIsPreservedBeforeAnyErase()=runBlocking {
        val store=store();store.write(key,null,source,listOf(write))
        val next=key.copy(scope=key.scope.copy(page=1));store.write(next,null,source,listOf(write))
        val files=drafts();files.last().writeBytes(files.last().readBytes().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() })
        val before=files.associateWith { it.readBytes() }
        reject { store.retireVersions(key.space,key.scope.member,setOf(version)) {} }
        before.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
        reject { store().read(key) }
    }
    @Test fun copiedRecordCannotDeleteTheWrongPage()=runBlocking {
        val store=store();store.write(key,null,source,listOf(write));store.write(key.copy(scope=key.scope.copy(page=1)),null,source,listOf(write))
        val files=drafts();files[1].writeBytes(files[0].readBytes())
        val before=files.associateWith { it.readBytes() }
        reject { store.retireVersions(key.space,key.scope.member,setOf(version)) {} }
        before.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
    }
    @Test fun interruptedWritesAreDecodedInsteadOfGuessedFromNames()=runBlocking {
        val store=store();store.write(key,null,source,listOf(write))
        val original=drafts().single()
        val pending=File(original.parentFile,".session-12345.pending").apply { writeBytes(original.readBytes()) }
        val other=key.copy(scope=key.scope.copy(version=UUID.randomUUID().toString()))
        store.write(other,null,source,listOf(write))
        val otherFile=drafts().single { it!=original && it!=pending }
        val unrelated=File(original.parentFile,".session-54321.pending").apply { writeBytes(otherFile.readBytes()) }
        val bytes=unrelated.readBytes()
        assertEquals(2,store.retireVersions(key.space,key.scope.member,setOf(version)) {})
        assertFalse(original.exists());assertFalse(pending.exists());assertArrayEquals(bytes,unrelated.readBytes())
        assertNotNull(store.read(other))
    }
    @Test fun unknownPendingOrWrongNamespaceDoesNotEraseAnything()=runBlocking {
        val store=store();store.write(key,null,source,listOf(write))
        val original=drafts().single();val unknown=File(original.parentFile,"unknown.txt").apply { writeText("original") }
        reject { store.retireVersions(key.space,key.scope.member,setOf(version)) {} }
        assertTrue(original.exists());assertEquals("original",unknown.readText())
        reject { store.retireVersions("local",key.scope.member,setOf(version)) {} }
    }
    @Test fun untrustedFileRootAliasRemainsRejected()=runBlocking {
        val untrusted=InkDraftStore(File(temp.root,"."),{secret})
        reject { untrusted.retireVersions(key.space,key.scope.member,setOf(version)) {} }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
    @Test fun changingAuthorityKeepsFilesAndBlocksRecoveryAfterRestart()=runBlocking {
        val store=store();store.write(key,null,source,listOf(write));val file=drafts().single();val bytes=file.readBytes()
        reject { store.retireVersions(key.space,key.scope.member,setOf(version)) { error("wrong account") } }
        assertNotNull(store.read(key))
        var checks=0
        reject { store.retireVersions(key.space,key.scope.member,setOf(version)) { if(++checks==2)error("account changed") } }
        assertArrayEquals(bytes,file.readBytes());reject { store().read(key) }
        assertEquals(1,store().retireVersions(key.space,key.scope.member,setOf(version)) {})
    }
    @Test fun tamperedRetirementLedgerCannotSilentlyReopenOldVersion()=runBlocking {
        val store=store();store.write(key,null,source,listOf(write));store.retireVersions(key.space,key.scope.member,setOf(version)) {}
        val file=File(temp.root,"retired-v1").listFiles()!!.single()
        val bytes=file.readBytes().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() };file.writeBytes(bytes)
        reject { store().read(key) };reject { store().write(key,null,source,listOf(write)) }
        assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun retiredVersionIsCumulativeAndEncrypted()=runBlocking {
        val store=store();val other=UUID.randomUUID().toString()
        store.retireVersions(key.space,key.scope.member,setOf(version)) {}
        store().retireVersions(key.space,key.scope.member,setOf(other)) {}
        for(v in listOf(version,other))reject { store().write(key.copy(scope=key.scope.copy(version=v)),null,source,listOf(write)) }
        val file=File(temp.root,"retired-v1").listFiles()!!.single()
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(version))
        val row=org.json.JSONObject(AccountVault(file,32*1024*1024){secret}.read()!!)
        assertEquals(2,row.getJSONArray("versions").length())
    }
    @Test fun runningRecoveryAndLateCallbacksCannotRecreateDeletedInk()=runBlocking {
        val storage=store();val recovery=InkRecovery(storage)
        try {
            val session=recovery.open(key,source)
            recovery.await(recovery.submit(session,listOf(write)))
            assertEquals(1,recovery.retireVersions(key.space,key.scope.member,setOf(version)) {})
            reject { recovery.submit(session,listOf(write)) };reject { recovery.submit(session,emptyList()) }
            recovery.release(session)
            reject { recovery.open(key,source) };reject { InkRecovery(store()).open(key,source) }
            assertTrue(drafts().isEmpty())
        } finally { recovery.closeForTest() }
    }
    @Test fun alreadyEncodingWriterCannotOutlivePermanentRetirement()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var pause=false
        val storage=InkDraftStore(temp.root,{
            if(pause) { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) };secret
        })
        val recovery=InkRecovery(storage)
        try {
            val session=recovery.open(key,source);pause=true
            val receipt=recovery.submit(session,listOf(write))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
            val deletion=async(Dispatchers.IO) { recovery.retireVersions(key.space,key.scope.member,setOf(version)) {} }
            release.countDown();withTimeout(10000){deletion.await()}
            runCatching { recovery.await(receipt) }
            reject { store().write(key,null,source,listOf(write)) };assertTrue(drafts().isEmpty())
        } finally { release.countDown();recovery.closeForTest() }
    }
}
