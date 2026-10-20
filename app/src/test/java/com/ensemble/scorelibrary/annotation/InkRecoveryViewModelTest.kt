package com.ensemble.scorelibrary.annotation

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InkRecoveryViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.cacheDir,"ink-vm-${UUID.randomUUID()}").apply { mkdirs() }
    private val secret=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val storage=InkDraftStore(root,{secret})
    private val recovery=InkRecovery(storage)
    private val models=ViewModelStore()
    private val source=RecoverySource("local","a".repeat(64))
    private val scope=AnnotationScope("member","piece","part","version",0)
    private val key=InkDraftKey(scope,AnnotationLayer.PERSONAL,source.space)
    private val empty=AnnotationData(emptyList(),emptyList(),emptyList(),0,0,true,null,false,recoverySource=source)
    private val rows=mutableMapOf<AnnotationScope,AnnotationData>()
    private var failSave=true
    private var revoked=false
    private var lostAck=false
    private var saveGate:CompletableDeferred<Unit>?=null
    private var loadGate:CompletableDeferred<Unit>?=null
    private var saves=0
    private var publications=0
    private val repository=object:AnnotationStore {
        override suspend fun load(scope:AnnotationScope):AnnotationData {
            loadGate?.await();check(!revoked) { "已撤权" };return rows[scope] ?: empty
        }
        override suspend fun save(scope:AnnotationScope,layer:AnnotationLayer,marks:List<InkMark>,generation:Long)=
            save(scope,layer,marks,generation,null)
        override suspend fun save(scope:AnnotationScope,layer:AnnotationLayer,marks:List<InkMark>,generation:Long,source:RecoverySource?) {
            saveGate?.await();check(!revoked);check(!failSave) { "模拟保存失败" }
            val before=rows[scope] ?: empty
            check(before.recoverySource==source)
            check(layer==AnnotationLayer.PERSONAL || before.admin && before.concert==null)
            val current=if(layer==AnnotationLayer.PERSONAL)before.personal else before.draft
            val revision=if(layer==AnnotationLayer.PERSONAL)before.personalGeneration else before.sharedGeneration
            if(revision==generation+1 && current==marks)return
            check(revision==generation) { "并发修改" }
            rows[scope]=if(layer==AnnotationLayer.PERSONAL)before.copy(personal=marks,personalGeneration=generation+1)
                else before.copy(draft=marks,sharedGeneration=generation+1)
            saves++
            if(lostAck) { lostAck=false;error("丢失保存回执") }
        }
        override suspend fun publish(scope:AnnotationScope) { publications++ }
        override suspend fun freeze(scope:AnnotationScope) { publications++ }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup()=runBlocking {
        models.clear();recovery.closeForTest();runCatching { storage.read(key) };Dispatchers.resetMain()
        val target=root.canonicalFile;check(target.parentFile==context.cacheDir.canonicalFile);target.deleteRecursively();Unit
    }
    private fun model(gateway:AnnotationStore=repository)=AnnotationViewModel(gateway,dispatcher,recovery=recovery).also { models.put("ink",it) }
    private suspend fun settle(until:()->Boolean) {
        val deadline=System.nanoTime()+15_000_000_000L
        while(!until()) {
            dispatcher.scheduler.runCurrent()
            check(System.nanoTime()<deadline) { "Recovery did not reach expected state" }
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
        dispatcher.scheduler.runCurrent()
    }
    private suspend fun open(vm:AnnotationViewModel,at:AnnotationScope=scope) { vm.bind(at);settle { !vm.state.value.busy } }
    private fun mark(id:String)=InkMark(id=id,kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.3f)))
    private fun write(marks:List<InkMark>,generation:Long=0,before:List<InkMark> = emptyList())=
        InkDraftWrite(marks,generation,InkDraftStore.digest(InkCodec.encode(before)))
    @Test fun failedSaveRecoversLatestInkAfterViewModelIsDestroyed()=runTest(dispatcher) {
        val old=model();open(old);old.tool(AnnotationTool.PEN)
        old.stroke(listOf(InkPoint(.1f,.2f)));settle { old.state.value.error!=null && old.state.value.recoveryDurable }
        repeat(12) { old.stroke(listOf(InkPoint(.2f+it*.01f,.4f))) }
        settle { old.state.value.recoveryDurable }
        val expected=old.state.value.marks
        val fresh=model();open(fresh)
        assertEquals(13,fresh.state.value.marks.size);assertEquals(expected,fresh.state.value.marks)
        assertTrue(fresh.state.value.pending);assertTrue(fresh.state.value.recoveryDurable)
        failSave=false;fresh.retry();settle { !fresh.state.value.busy && !fresh.state.value.saving && !fresh.state.value.pending }
        assertEquals(expected,rows[scope]!!.personal);assertEquals(2,saves);assertNull(storage.read(key))
    }
    @Test fun slowDatabaseDoesNotPreventLatestEditsReachingRecoveryDisk()=runTest(dispatcher) {
        failSave=false;saveGate=CompletableDeferred()
        val vm=model();open(vm);vm.tool(AnnotationTool.PEN)
        vm.stroke(listOf(InkPoint(.1f,.2f)));settle { vm.state.value.saving && vm.state.value.recoveryDurable }
        repeat(10) { vm.stroke(listOf(InkPoint(.2f,.3f))) }
        settle { vm.state.value.recoveryDurable }
        assertEquals(11,storage.read(key)!!.writes.last().marks.size);assertEquals(0,saves)
        models.clear()
        saveGate=null
        val fresh=model();open(fresh);assertEquals(11,fresh.state.value.marks.size)
        fresh.retry();settle { !fresh.state.value.busy && !fresh.state.value.saving && !fresh.state.value.pending }
        assertEquals(11,rows[scope]!!.personal.size)
    }
    @Test fun lostAcknowledgementKeepsOnlyUncommittedTailOnColdOpen()=runTest(dispatcher) {
        val first=listOf(mark("first"));val last=first+mark("tail")
        storage.write(key,null,source,listOf(write(first),write(last,1,first)))
        rows[scope]=empty.copy(personal=first,personalGeneration=1)
        val vm=model();open(vm);assertEquals(last,vm.state.value.marks)
        failSave=false;vm.retry();settle { !vm.state.value.busy && !vm.state.value.saving && !vm.state.value.pending }
        assertEquals(1,saves);assertEquals(2L,rows[scope]!!.personalGeneration);assertNull(storage.read(key))
    }
    @Test fun alreadyCommittedFinalSnapshotClearsRecoveryWithoutAnotherSave()=runTest(dispatcher) {
        val marks=listOf(mark("done"));storage.write(key,null,source,listOf(write(marks)))
        rows[scope]=empty.copy(personal=marks,personalGeneration=1)
        val vm=model();open(vm)
        assertFalse(vm.state.value.pending);assertEquals(0,saves);assertEquals(marks,vm.state.value.marks);assertNull(storage.read(key))
    }
    @Test fun delayedOrFailedAuthorizationNeverShowsPrivateRecovery()=runTest(dispatcher) {
        storage.write(key,null,source,listOf(write(listOf(mark("secret")))))
        loadGate=CompletableDeferred();revoked=true
        val vm=model();vm.bind(scope);dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.data);assertTrue(vm.state.value.marks.isEmpty())
        loadGate!!.complete(Unit);settle { !vm.state.value.busy }
        assertNull(vm.state.value.data);assertTrue(vm.state.value.marks.isEmpty());assertNotNull(storage.read(key))
        revoked=false;loadGate=null;vm.retry();settle { !vm.state.value.busy }
        assertEquals("secret",vm.state.value.marks.single().id)
    }
    @Test fun differentPdfIsNotOverlaidAndRequiresExplicitDiscard()=runTest(dispatcher) {
        storage.write(key,null,source,listOf(write(listOf(mark("old-pdf")))))
        rows[scope]=empty.copy(recoverySource=source.copy(sha256="b".repeat(64)))
        val vm=model();open(vm)
        assertTrue(vm.state.value.pending);assertTrue(vm.state.value.recoveryBlocked);assertFalse(vm.state.value.editable)
        assertTrue(vm.state.value.marks.isEmpty());assertNull(vm.state.value.recoveryConflict)
        vm.tool(AnnotationTool.PEN);vm.stroke(listOf(InkPoint(.4f,.5f)));assertTrue(vm.state.value.marks.isEmpty())
        vm.discardPending();settle { !vm.state.value.busy && !vm.state.value.pending }
        assertNull(storage.read(key));assertEquals(0,saves)
    }
    @Test fun sharedDraftIsDiscoveredButLostRoleHidesItAndNeverPublishes()=runTest(dispatcher) {
        val sharedKey=key.copy(layer=AnnotationLayer.SHARED)
        storage.write(sharedKey,null,source,listOf(write(listOf(mark("private-draft")))))
        val vm=model();open(vm)
        assertEquals(AnnotationLayer.SHARED,vm.state.value.layer);assertEquals("private-draft",vm.state.value.marks.single().id)
        rows[scope]=empty.copy(admin=false)
        vm.refreshAuthorization();settle { !vm.state.value.busy }
        assertEquals(AnnotationLayer.SHARED,vm.state.value.layer);assertTrue(vm.state.value.marks.isEmpty())
        assertTrue(vm.state.value.recoveryBlocked);assertNull(vm.state.value.recoveryConflict)
        vm.retry();settle { !vm.state.value.busy }
        assertEquals(0,publications);assertEquals(0,saves);assertNotNull(storage.read(sharedKey))
    }
    @Test fun conflictingLocalAndSavedInkWaitForExplicitChoiceAndRejectStalePreview()=runTest(dispatcher) {
        val local=listOf(mark("unsaved"));val saved=listOf(mark("other"))
        storage.write(key,null,source,listOf(write(local)))
        rows[scope]=empty.copy(personal=saved,personalGeneration=1)
        val vm=model();open(vm)
        assertEquals(saved,vm.state.value.marks);assertEquals(local,vm.state.value.recoveryConflict!!.local)
        assertEquals(0,saves)
        rows[scope]=empty.copy(personal=listOf(mark("changed-again")),personalGeneration=2)
        vm.resolveRecovery(true);settle { !vm.state.value.busy }
        assertTrue(vm.state.value.recoveryBlocked);assertEquals(0,saves);assertNotNull(storage.read(key))
        failSave=false;vm.retry();settle { !vm.state.value.busy }
        assertEquals(2L,vm.state.value.recoveryConflict!!.generation)
        vm.resolveRecovery(true);settle { !vm.state.value.busy && !vm.state.value.saving && !vm.state.value.pending }
        assertEquals(local,rows[scope]!!.personal);assertEquals(3L,rows[scope]!!.personalGeneration);assertNull(storage.read(key))
    }
    @Test fun choosingSavedInkClearsOnlyOwnRecoveryWithoutWritingDatabase()=runTest(dispatcher) {
        val local=listOf(mark("unsaved"));val saved=listOf(mark("saved"))
        storage.write(key,null,source,listOf(write(local)))
        rows[scope]=empty.copy(personal=saved,personalGeneration=1)
        val vm=model();open(vm);vm.resolveRecovery(false);settle { !vm.state.value.busy && !vm.state.value.pending }
        assertEquals(saved,vm.state.value.marks);assertEquals(0,saves);assertNull(storage.read(key))
    }
    @Test fun roleRevokedDuringConflictChoiceImmediatelyHidesDraftAndPreview()=runTest(dispatcher) {
        val sharedKey=key.copy(layer=AnnotationLayer.SHARED)
        storage.write(sharedKey,null,source,listOf(write(listOf(mark("unpublished-secret")))))
        rows[scope]=empty.copy(draft=listOf(mark("other-admin")),sharedGeneration=1)
        val vm=model();open(vm);assertNotNull(vm.state.value.recoveryConflict)
        rows[scope]=empty.copy(admin=false)
        vm.resolveRecovery(true);settle { !vm.state.value.busy }
        assertNull(vm.state.value.data);assertNull(vm.state.value.recoveryConflict);assertTrue(vm.state.value.marks.isEmpty())
        assertTrue(vm.state.value.recoveryBlocked);assertNotNull(storage.read(sharedKey));assertEquals(0,saves);assertEquals(0,publications)
    }
    @Test fun deniedFreshAuthorizationDuringChoiceImmediatelyHidesBothPreviews()=runTest(dispatcher) {
        storage.write(key,null,source,listOf(write(listOf(mark("private")))))
        rows[scope]=empty.copy(personal=listOf(mark("saved")),personalGeneration=1)
        val vm=model();open(vm);assertNotNull(vm.state.value.recoveryConflict)
        revoked=true;vm.resolveRecovery(false);settle { !vm.state.value.busy }
        assertNull(vm.state.value.data);assertNull(vm.state.value.recoveryConflict);assertTrue(vm.state.value.marks.isEmpty())
        assertNotNull(storage.read(key));assertEquals(0,saves)
    }
    @Test fun accountSwitchCannotExposeOrDeleteAnotherMembersJournal()=runTest(dispatcher) {
        storage.write(key,null,source,listOf(write(listOf(mark("owner-only")))))
        val vm=model();open(vm,scope.copy(member="other"))
        assertTrue(vm.state.value.marks.isEmpty());assertFalse(vm.state.value.pending)
        vm.discardPending();settle { !vm.state.value.busy }
        assertNotNull(storage.read(key));assertEquals(0,saves)
    }
    @Test fun normalSaveFinishesAndNewStrokeAfterAckCannotBeDeletedByOldCleanup()=runTest(dispatcher) {
        failSave=false
        val vm=model();open(vm);vm.tool(AnnotationTool.PEN)
        repeat(20) {
            vm.stroke(listOf(InkPoint(.2f,.3f)))
            dispatcher.scheduler.runCurrent()
        }
        settle { !vm.state.value.saving && !vm.state.value.pending }
        assertEquals(20,rows[scope]!!.personal.size);assertNull(storage.read(key));assertEquals(0,publications)
    }
    @Test fun liveLostAcknowledgementPlusLaterEditsRestoresWithoutDoubleCommit()=runTest(dispatcher) {
        failSave=false;lostAck=true
        val vm=model();open(vm);vm.tool(AnnotationTool.PEN);vm.stroke(listOf(InkPoint(.1f,.2f)))
        settle { vm.state.value.error!=null && vm.state.value.recoveryDurable }
        vm.stroke(listOf(InkPoint(.4f,.5f)));settle { vm.state.value.recoveryDurable }
        val expected=vm.state.value.marks
        val fresh=model();open(fresh);assertEquals(expected,fresh.state.value.marks)
        fresh.retry();settle { !fresh.state.value.busy && !fresh.state.value.saving && !fresh.state.value.pending }
        assertEquals(expected,rows[scope]!!.personal);assertEquals(2,saves);assertEquals(2L,rows[scope]!!.personalGeneration)
    }
    @Test fun identitySwitchDuringDelayedRestoreCannotExposePreviousOwner()=runTest(dispatcher) {
        storage.write(key,null,source,listOf(write(listOf(mark("owner-secret")))))
        loadGate=CompletableDeferred()
        val vm=model();vm.bind(scope);dispatcher.scheduler.runCurrent()
        vm.bind(scope.copy(member="other"));dispatcher.scheduler.runCurrent()
        loadGate!!.complete(Unit);settle { !vm.state.value.busy }
        assertEquals("other",vm.state.value.scope!!.member);assertTrue(vm.state.value.marks.isEmpty());assertNotNull(storage.read(key))
    }
    @Test fun realRoomAndPdfSurviveDatabaseCloseAndFreshRecovery()=runTest(dispatcher) {
        val pdf=File(root,"original.pdf")
        withContext(Dispatchers.IO) {
            val script="from pypdf import PdfWriter, PdfReader; import sys; w=PdfWriter(); w.add_blank_page(400,600); w.write(sys.argv[1]); assert len(PdfReader(sys.argv[1],strict=True).pages)==1"
            val process=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python","-c",script,pdf.path)
                .redirectErrorStream(true).redirectOutput(File(root,"pdf-check.log")).start()
            try { check(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS) && process.exitValue()==0) { "Synthetic PDF generation failed" } }
            finally { if(process.isAlive)process.destroyForcibly() }
        }
        val hash=com.ensemble.scorelibrary.backup.ArchiveFormat.digest(pdf.readBytes())
        fun database()=androidx.room.Room.databaseBuilder(context,com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase::class.java,File(root,"library.db").path).build()
        var db=database()
        try {
            val dao=db.libraryDao()
            dao.insertPiece(com.ensemble.scorelibrary.core.database.entity.PieceEntity(id=scope.piece,title="Synthetic"))
            dao.insertPartDefinition(com.ensemble.scorelibrary.core.database.entity.PartDefinitionEntity(id="d",name="I"))
            dao.insertPiecePart(com.ensemble.scorelibrary.core.database.entity.PiecePartEntity(id=scope.part,pieceId=scope.piece,partDefinitionId="d"))
            dao.insertMember(com.ensemble.scorelibrary.core.database.entity.MemberEntity(id=scope.member,displayName="Synthetic",role=com.ensemble.scorelibrary.core.model.MemberRole.ADMIN,defaultPartDefinitionId="d"))
            dao.insertPermission(com.ensemble.scorelibrary.core.database.entity.MemberPartPermissionEntity(memberId=scope.member,partDefinitionId="d"))
            dao.insertScorePdf(com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity(id="pdf",title="Synthetic"))
            dao.insertScorePdfVersion(com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity(id=scope.version,scorePdfId="pdf",versionNumber=1,
                managedFilePath=pdf.path,originalUri=pdf.toURI().toString(),fileHash=hash,fileSizeBytes=pdf.length(),pageCount=1))
            dao.updateScorePdf(com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity(id="pdf",title="Synthetic",activeVersionId=scope.version))
            dao.insertPartScoreBinding(com.ensemble.scorelibrary.core.database.entity.PartScoreBindingEntity(id="b",piecePartId=scope.part,scorePdfId="pdf",startPage=1,endPage=1))
            fun gateway(database:com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase):AnnotationStore {
                val real=AnnotationRepository(database)
                return object:AnnotationStore by real {
                    override suspend fun save(scope:AnnotationScope,layer:AnnotationLayer,marks:List<InkMark>,generation:Long,source:RecoverySource?) {
                        check(!failSave) { "模拟 Room 写入前故障" };real.save(scope,layer,marks,generation,source)
                    }
                }
            }
            val old=model(gateway(db));open(old);old.tool(AnnotationTool.SYMBOL);old.symbolScale(1.5f)
            old.stroke(listOf(InkPoint(.3f,.4f)));settle { old.state.value.error!=null && old.state.value.recoveryDurable }
            val expected=old.state.value.marks;models.clear();db.close();db=database()
            val fresh=model(gateway(db));open(fresh);assertEquals(expected,fresh.state.value.marks)
            failSave=false;fresh.retry();settle { !fresh.state.value.busy && !fresh.state.value.saving && !fresh.state.value.pending }
            assertEquals(expected,AnnotationRepository(db).load(scope).personal)
            assertEquals(hash,com.ensemble.scorelibrary.backup.ArchiveFormat.digest(pdf.readBytes()))
            assertNull(storage.read(key))
        } finally { models.clear();db.close() }
    }
}
