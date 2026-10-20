package com.ensemble.scorelibrary.personal

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.backup.ArchiveFormat
import com.ensemble.scorelibrary.data.ManagedPdf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class PersonalDocumentsControllerTest {
    @get:Rule val temp=TemporaryFolder()
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val context=object:ContextWrapper(app) { override fun getNoBackupFilesDir()=temp.root }
    private val member=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Team","member","Member","member",true)
    private val first=PersonalOwner(TEAM_SERVICE_URL,member.accountId)
    private val second=PersonalOwner(TEAM_SERVICE_URL,UUID.randomUUID().toString())
    private var current:PersonalOwner?=first
    private val key=SecretKeySpec(ByteArray(32){4},"AES")
    private val bytes="Synthetic personal controller file".toByteArray()
    private val uri=Uri.parse("content://personal-controller/source.pdf")
    private var erase=true
    private var entered:CompletableDeferred<Unit>?=null
    private var release:CompletableDeferred<Unit>?=null
    private val copier:PersonalPdfCopier={c,u,commit->
        val dir=File(c.filesDir,"scores").apply { check(isDirectory||mkdirs()) }
        val f=File(dir,"${UUID.randomUUID()}.pdf");check(f.createNewFile());var done=false
        try { f.writeBytes(bytes);entered?.complete(Unit);release?.await();commit(ManagedPdf(f,ArchiveFormat.digest(bytes),bytes.size.toLong(),1,u.toString()));done=true }
        finally { if(!done)f.delete() }
    }
    private fun store()=PersonalDocumentStore(context,{current},{key},{erase && it.delete()},copier)
    private suspend fun settled(c:PersonalDocumentsController)=withTimeout(10000) { c.state.first { !it.busy } }
    @Test fun ordinaryVerifiedAccountOwnsPersonalSpaceWithoutTeamAdministratorPrivilege() {
        val state=TeamAccountState(principal=member)
        assertEquals("member",member.role);assertEquals(first,personalOwner(state))
        assertEquals(first,personalOwner(state.copy(principal=member.copy(role="admin",memberId=UUID.randomUUID().toString()))))
        assertNull(personalOwner(state.copy(busy=true)));assertNull(personalOwner(state.copy(principal=null)))
        assertNotEquals(first,personalOwner(state.copy(principal=member.copy(accountId=second.account))))
    }
    @Test fun confirmationAndRestoreFlowKeepsOwnerWhileDeletingOnlySelectedPdf()=runBlocking {
        val store=store();val c=PersonalDocumentsController(store,this)
        try {
            c.bind(first);settled(c);c.importPdf(uri,"本人PDF");val imported=settled(c);val d=imported.snapshot!!.documents.single()
            c.trash(d.id,true);settled(c);c.preview(d.id);settled(c)
            c.permanent(false);val refused=settled(c);assertNotNull(refused.plan);assertTrue(store.read(first.key).documents.isNotEmpty())
            c.permanent(true);val done=settled(c);assertEquals(first,done.owner);assertTrue(done.snapshot!!.documents.isEmpty());assertNull(done.plan)
        } finally { c.close() }
    }
    @Test fun batchPreviewAndCommitDeleteExactlySelectedCopiesAndKeepOtherTrashAndActiveFiles()=runBlocking {
        val store=store();val c=PersonalDocumentsController(store,this)
        try {
            c.bind(first);settled(c)
            for(title in listOf("Delete one","Delete two","Keep trash","Keep active")) { c.importPdf(uri,title);settled(c) }
            val docs=c.state.value.snapshot!!.documents
            for(d in docs.filter { it.title!="Keep active" }) { c.trash(d.id,true);settled(c) }
            val ids=docs.filter { it.title.startsWith("Delete") }.map { it.id }.toSet()
            c.preview(ids);val planned=settled(c);assertEquals(ids,planned.plan!!.documents.map { it.id }.toSet())
            c.permanent(false);settled(c);assertEquals(4,store.read(first.key).documents.size)
            c.permanent(true);val done=settled(c)
            assertEquals(setOf("Keep trash","Keep active"),done.snapshot!!.documents.map { it.title }.toSet())
            assertTrue(done.snapshot.documents.single { it.title=="Keep trash" }.trashed)
            c.preview(setOf(docs.single { it.title=="Keep active" }.id));val refused=settled(c)
            assertNull(refused.plan);assertEquals(2,store.read(first.key).documents.size)
        } finally { c.close() }
    }
    @Test fun accountSwitchCancelsLatePickerAndNeverDisplaysPreviousAccountsFiles()=runBlocking {
        val store=store();val c=PersonalDocumentsController(store,this)
        try {
            c.bind(first);settled(c);entered=CompletableDeferred();release=CompletableDeferred()
            c.importPdf(uri,"Must not appear for another account");entered!!.await()
            current=second;c.bind(second);release!!.complete(Unit)
            val after=settled(c);assertEquals(second,after.owner);assertTrue(after.snapshot!!.documents.isEmpty())
            current=first;assertTrue(store.read(first.key).documents.isEmpty())
            c.bind(null);assertNull(c.state.value.owner);assertNull(c.state.value.snapshot);assertNull(c.state.value.plan)
        } finally { release?.complete(Unit);c.close() }
    }
    @Test fun interruptedEraseClosesPreviewAndOffersRetryWithoutLosingLoginOrOtherFiles()=runBlocking {
        val store=store();val c=PersonalDocumentsController(store,this)
        try {
            c.bind(first);settled(c);c.importPdf(uri,"Delete");val d=settled(c).snapshot!!.documents.single()
            c.importPdf(uri,"Keep");settled(c);c.open(d.id);settled(c);assertNotNull(c.state.value.opened)
            c.trash(d.id,true);settled(c);c.preview(d.id);settled(c);erase=false
            c.permanent(true);val pending=settled(c);assertEquals(first,pending.owner);assertEquals(1,pending.snapshot!!.pending)
            assertNull(pending.plan);assertNull(pending.opened);assertTrue(pending.message!!.contains("重试"))
            erase=true;c.refresh();val done=settled(c);assertEquals(0,done.snapshot!!.pending)
            assertEquals(listOf("Keep"),done.snapshot.documents.map { it.title });assertEquals(first,done.owner)
        } finally { c.close() }
    }
}
