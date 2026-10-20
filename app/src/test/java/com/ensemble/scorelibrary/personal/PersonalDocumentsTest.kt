package com.ensemble.scorelibrary.personal

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.TEAM_SERVICE_URL
import com.ensemble.scorelibrary.backup.ArchiveFormat
import com.ensemble.scorelibrary.data.ManagedPdf
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class PersonalDocumentsTest {
    @get:Rule val temp=TemporaryFolder()
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val context=object:ContextWrapper(app) { override fun getNoBackupFilesDir()=File(temp.root,".") }
    private val first=PersonalOwner(TEAM_SERVICE_URL,UUID.randomUUID().toString())
    private val second=PersonalOwner(TEAM_SERVICE_URL,UUID.randomUUID().toString())
    private var current:PersonalOwner?=first
    private val secret=SecretKeySpec(ByteArray(32){17},"AES")
    private val bytes="Synthetic owned PDF fixture; parsing is tested by PersonalPdfNativeTest".toByteArray()
    private val source by lazy { File(temp.root,"external-original.pdf").apply { writeBytes(bytes) } }
    private val uri=Uri.parse("content://personal-test/external-original.pdf")
    private var beforeCommit:(()->Unit)?=null
    private var forcedId:String?=null
    private val copier:PersonalPdfCopier={c,u,commit->
        val dir=File(c.filesDir,"scores").apply { check(isDirectory || mkdirs()) }
        val f=File(dir,"${forcedId ?: UUID.randomUUID()}.pdf");check(f.createNewFile())
        var done=false
        try {
            f.writeBytes(source.readBytes());beforeCommit?.invoke()
            commit(ManagedPdf(f,ArchiveFormat.digest(f.readBytes()),f.length(),1,u.toString()));done=true
        } finally { if(!done)f.delete() }
    }
    private fun store(remove:(File)->Boolean={it.delete()},key:()->javax.crypto.SecretKey={secret})=
        PersonalDocumentStore(context,{current},key,remove,copier)
    private fun root(o:PersonalOwner)=File(temp.root,"personal-documents-v1/${o.key}")
    private fun pdf(o:PersonalOwner,id:String)=File(root(o),"scores/$id.pdf")
    private suspend fun add(store:PersonalDocumentStore=store(),title:String="01-01-1000-本人乐谱")=store.importPdf(current!!.key,uri,title)
    private suspend fun reject(block:suspend ()->Unit) {
        try { block();fail("Expected rejection") } catch(e:AssertionError){throw e} catch(_:Exception){}
    }
    @Test fun ownerCanImportTrashRestorePreviewAndPermanentlyEraseOnlyManagedCopy()=runBlocking {
        val store=store();val imported=add(store);val d=imported.documents.single()
        assertEquals("01-01-1000-本人乐谱",d.title);assertArrayEquals(bytes,store.open(first.key,d.id).file.readBytes())
        val trash=store.trash(first.key,d.id,imported.revision,true)
        reject { store.open(first.key,d.id) };assertArrayEquals(bytes,pdf(first,d.id).readBytes())
        val restored=store.trash(first.key,d.id,trash.revision,false)
        assertEquals(d,restored.documents.single());assertEquals(d.id,store.open(first.key,d.id).document.id)
        val again=store.trash(first.key,d.id,restored.revision,true)
        val plan=store.preview(first.key,setOf(d.id),again.revision)
        reject { store.permanent(plan,false) };assertTrue(pdf(first,d.id).exists())
        val deleted=store.permanent(plan,true);assertTrue(deleted.documents.isEmpty());assertFalse(pdf(first,d.id).exists())
        assertArrayEquals(bytes,source.readBytes());assertTrue(store().read(first.key).documents.isEmpty())
    }
    @Test fun anotherAccountAndItsSamePdfRemainIndependent()=runBlocking {
        val store=store();val a=add(store).documents.single()
        current=second;val b=add(store).documents.single();val before=File(root(second),"index.enc").readBytes()
        reject { store.open(first.key,a.id) };reject { store.trash(first.key,a.id,1,true) }
        current=first;val removed=store.trash(first.key,a.id,1,true)
        store.permanent(store.preview(first.key,setOf(a.id),removed.revision),true)
        assertArrayEquals(before,File(root(second),"index.enc").readBytes());assertArrayEquals(bytes,pdf(second,b.id).readBytes())
        current=second;assertEquals(b,store.open(second.key,b.id).document)
    }
    @Test fun logoutAndStalePickerCannotImportOrEraseInAnotherAccount()=runBlocking {
        val store=store();val saved=add(store);val d=saved.documents.single()
        val trash=store.trash(first.key,d.id,saved.revision,true);val plan=store.preview(first.key,setOf(d.id),trash.revision)
        current=null;reject { store.permanent(plan,true) };assertTrue(pdf(first,d.id).exists())
        current=first;beforeCommit={current=second};reject { store.importPdf(first.key,uri,"late picker") }
        current=first;assertEquals(listOf(d.copy(state="trash")),store.read(first.key).documents)
        assertEquals(1,File(root(first),"scores").listFiles()!!.size)
    }
    @Test fun staleRevisionAndForgedForeignPlanCannotDeleteAnything()=runBlocking {
        val store=store();val initial=add(store);val a=initial.documents.single()
        val trash=store.trash(first.key,a.id,initial.revision,true);val oldPlan=store.preview(first.key,setOf(a.id),trash.revision)
        val newer=add(store,"different file");reject { store.permanent(oldPlan,true) }
        val plan=store.preview(first.key,setOf(a.id),newer.revision)
        current=second;val b=add(store);val bTrash=store.trash(second.key,b.documents.single().id,b.revision,true)
        reject { store.permanent(plan.copy(owner=second.key,revision=bTrash.revision),true) }
        assertTrue(pdf(first,a.id).exists());assertTrue(pdf(second,b.documents.single().id).exists())
    }
    @Test fun physicalFailurePersistsRetirementAndRestartRetriesWithoutBlockingOtherFiles()=runBlocking {
        val failStore=store(remove={false});val a=add(failStore).documents.single();val both=add(failStore,"Keep")
        val keep=both.documents.single { it.id!=a.id };val trash=failStore.trash(first.key,a.id,both.revision,true)
        val plan=failStore.preview(first.key,setOf(a.id),trash.revision)
        try { failStore.permanent(plan,true);fail() } catch(_:PersonalErasePending){}
        val pending=failStore.read(first.key);assertEquals(1,pending.pending)
        reject { failStore.open(first.key,a.id) };assertEquals(keep,failStore.open(first.key,keep.id).document)
        assertTrue(pdf(first,a.id).exists());assertEquals(0,store().read(first.key).pending);assertFalse(pdf(first,a.id).exists())
        assertArrayEquals(bytes,pdf(first,keep.id).readBytes());assertArrayEquals(bytes,source.readBytes())
        forcedId=a.id;reject { add(store(),"late resurrection") };assertFalse(pdf(first,a.id).exists())
    }
    @Test fun interruptionAfterPdfEraseCanFinishFromPersistedIntent()=runBlocking {
        val store=store();val s=add(store);val d=s.documents.single();val trash=store.trash(first.key,d.id,s.revision,true)
        val plan=store.preview(first.key,setOf(d.id),trash.revision);var calls=0
        val interrupted=store(key={if(++calls==3)throw IOException("Synthetic final manifest failure");secret})
        reject { interrupted.permanent(plan,true) };assertFalse(pdf(first,d.id).exists())
        assertTrue(store().read(first.key).documents.isEmpty());assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun tamperedPdfDoesNotAdvanceRetirementOrEraseUnrelatedContent()=runBlocking {
        val store=store();val s=add(store);val d=s.documents.single();val trash=store.trash(first.key,d.id,s.revision,true)
        val plan=store.preview(first.key,setOf(d.id),trash.revision);val index=File(root(first),"index.enc").readBytes()
        val changed=bytes.clone().also { it[0]=(it[0].toInt() xor 1).toByte() };pdf(first,d.id).writeBytes(changed)
        reject { store.permanent(plan,true) };assertArrayEquals(index,File(root(first),"index.enc").readBytes())
        assertArrayEquals(changed,pdf(first,d.id).readBytes());assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun copiedAccountManifestIsRejectedBeforeAnyCleanup()=runBlocking {
        val store=store();val a=add(store).documents.single();val original=File(root(first),"index.enc").readBytes()
        current=second;store.read(second.key);File(root(second),"index.enc").writeBytes(original)
        reject { store.read(second.key) };assertArrayEquals(original,File(root(second),"index.enc").readBytes())
        assertTrue(pdf(first,a.id).exists());current=first;assertEquals(a,store.open(first.key,a.id).document)
    }
    @Test fun corruptEncryptedMetadataIsPreservedAndNeverClearedAsLoginData()=runBlocking {
        val store=store();val d=add(store).documents.single();val index=File(root(first),"index.enc")
        val changed=index.readBytes().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() };index.writeBytes(changed)
        reject { store.read(first.key) };assertArrayEquals(changed,index.readBytes());assertTrue(pdf(first,d.id).exists())
    }
    @Test fun onlyExplicitPersonalImportIsAcceptedAndUnknownFilesArePreserved()=runBlocking {
        val store=store();store.read(first.key)
        val unknown=File(root(first),"unknown.pdf").apply { writeBytes(bytes) }
        reject { store.importPdf(first.key,Uri.parse("team-artifact:${UUID.randomUUID()}"),"Not personal") }
        reject { store.open(first.key,"../other") };assertArrayEquals(bytes,unknown.readBytes())
        assertTrue(store.read(first.key).documents.isEmpty())
    }
    @Test fun trustedParentAliasWorksButChildFileCannotBecomeManagedDirectory()=runBlocking {
        val store=store();store.read(first.key);val bad=File(root(first),"scores").apply { writeBytes(bytes) }
        reject { add(store) };assertArrayEquals(bytes,bad.readBytes());assertTrue(store.read(first.key).documents.isEmpty())
    }
    @Test fun accountOriginIsPartOfOwnershipAndActiveFileCannotBePurged()=runBlocking {
        val store=store();val s=add(store);val d=s.documents.single()
        reject { store.preview(first.key,setOf(d.id),s.revision) }
        current=PersonalOwner("https://other.example",first.account)
        assertNotEquals(first.key,current!!.key);assertTrue(store.read(current!!.key).documents.isEmpty())
        reject { store.open(first.key,d.id) };assertTrue(pdf(first,d.id).exists())
    }
}
