package com.ensemble.scorelibrary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryManagementTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ScoreLibraryDatabase
    private lateinit var service: LibraryManagement
    private var inspectionBarrier: CompletableDeferred<Unit>?=null
    private val inspected=mutableListOf<String>()
    private val engine=object:PdfEngine {
        override suspend fun inspect(segments:List<ResolvedScoreSegment>):List<PageSize>{inspectionBarrier?.await();inspected+=segments.map{it.versionId};return listOf(PageSize(600,900))}
        override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame=error("Metadata tests do not render PDFs")
    }
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,ScoreLibraryDatabase::class.java).build();service=LibraryManagement(context,db,engine)
        val dao=db.libraryDao()
        dao.insertPartDefinition(PartDefinitionEntity(id="d1",name="I"));dao.insertPartDefinition(PartDefinitionEntity(id="d2",name="II"))
        for((id,role) in listOf("a" to MemberRole.ADMIN,"m" to MemberRole.MEMBER)) {
            dao.insertMember(MemberEntity(id=id,displayName=id,role=role,defaultPartDefinitionId="d1"))
            dao.insertPermission(MemberPartPermissionEntity(memberId=id,partDefinitionId="d1"))
        }
        dao.insertPiece(PieceEntity(id="p1",title="One"));dao.insertPiece(PieceEntity(id="p2",title="Two"))
        dao.insertPiecePart(PiecePartEntity(id="part1",pieceId="p1",partDefinitionId="d1"))
        dao.insertPiecePart(PiecePartEntity(id="part2",pieceId="p1",partDefinitionId="d2"))
        dao.insertPiecePart(PiecePartEntity(id="part3",pieceId="p2",partDefinitionId="d1"))
        for((pdf,version) in listOf("pdf1" to "v1","pdf2" to "foreign")) {
            dao.insertScorePdf(ScorePdfEntity(id=pdf,title=pdf))
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id=version,scorePdfId=pdf,versionNumber=1,managedFilePath="file",originalUri="content://fixture",fileHash="hash",fileSizeBytes=1,pageCount=4))
            dao.updateScorePdf(ScorePdfEntity(id=pdf,title=pdf,activeVersionId=version))
        }
        dao.insertPartScoreBinding(PartScoreBindingEntity(id="b1",piecePartId="part1",scorePdfId="pdf1",startPage=2,endPage=3))
        dao.insertPartScoreBinding(PartScoreBindingEntity(id="b2",piecePartId="part2",scorePdfId="pdf1",startPage=2,endPage=3))
        dao.insertPartScoreBinding(PartScoreBindingEntity(id="b3",piecePartId="part3",scorePdfId="pdf2",startPage=1,endPage=2))
        dao.insertAssignment(PiecePartAssignmentEntity(id="assignment1",memberId="m",piecePartId="part1"))
        dao.insertConcert(ConcertEntity(id="c",title="Concert"))
        dao.insertConcertItem(ConcertItemEntity(id="i1",concertId="c",pieceId="p1",sortOrder=0))
        dao.insertConcertItem(ConcertItemEntity(id="i2",concertId="c",pieceId="p2",sortOrder=1))
        dao.insertConcertItem(ConcertItemEntity(id="i3",concertId="c",pieceId="p1",sortOrder=2,isArchived=true))
    }
    @After fun close(){db.close()}
    private suspend fun rejected(block:suspend()->Unit){try{block();fail("Must reject") }catch(_:IllegalStateException){}catch(_:IllegalArgumentException){}}
    private suspend fun data()=db.resolutionDao().snapshot()
    private suspend fun score()=ScoreResolver(data()).resolvePiece("m","p1")
    private suspend fun freeze()=AnnotationRepository(db).freeze(AnnotationScope("a","p1","part1","v1",1,"i1"))

    @Test fun nonAdministratorCannotReadPrivateCatalogOrWriteAnyCommand()=runBlocking {
        val before=data();rejected{service.catalog("m")};rejected{service.edit("m",ManagementCommand.Piece(PieceEntity(title="Forbidden")))}
        assertEquals(before,data())
    }
    @Test fun protectsLastAdministratorAndPromotionDoesNotGrantScores()=runBlocking {
        val admin=data().members.first{it.id=="a"}
        rejected{service.edit("a",ManagementCommand.Member(admin.copy(role=MemberRole.MEMBER)))}
        rejected{service.edit("a",ManagementCommand.Member(admin.copy(isArchived=true)))}
        service.edit("a",ManagementCommand.Member(MemberEntity(id="b",displayName="Other admin",role=MemberRole.ADMIN)))
        assertTrue(ScoreResolver(data()).resolvePiece("b","p1") is DomainResult.Failure)
        assertNotNull(service.catalog("b"))
        service.edit("a",ManagementCommand.Member(admin.copy(isArchived=true)))
        rejected{service.catalog("a")}
    }
    @Test fun scopedGrantAssignmentRevocationNeverSilentlyUsesDefault()=runBlocking {
        rejected{service.edit("a",ManagementCommand.Assignment("m","p1","part2"))}
        assertEquals("part1",(score() as DomainResult.Success).value.part.id)
        service.edit("a",ManagementCommand.ScopedGrant("m","part2",true))
        service.edit("a",ManagementCommand.Assignment("m","p1","part2"))
        assertEquals(1,data().assignments.count{it.memberId=="m" && !it.isArchived && it.piecePartId in setOf("part1","part2")})
        service.edit("a",ManagementCommand.ScopedGrant("m","part2",false))
        assertEquals(ErrorCode.UNAUTHORIZED,(score() as DomainResult.Failure).error.code)
        assertTrue(data().assignments.any{it.memberId=="m" && it.piecePartId=="part2" && !it.isArchived})
        service.edit("a",ManagementCommand.ScopedGrant("m","part2",true))
        assertEquals(1,data().extraPermissions.count{it.memberId=="m" && it.piecePartId=="part2"})
        service.edit("a",ManagementCommand.Assignment("m","p1",null))
        assertEquals("part1",(score() as DomainResult.Success).value.part.id)
    }
    @Test fun globalAndScopedPermissionSourcesStayIndependent()=runBlocking {
        service.edit("a",ManagementCommand.GlobalGrant("m","d2",true))
        service.edit("a",ManagementCommand.ScopedGrant("m","part2",true))
        service.edit("a",ManagementCommand.Assignment("m","p1","part2"))
        service.edit("a",ManagementCommand.ScopedGrant("m","part2",false))
        assertEquals("part2",(score() as DomainResult.Success).value.part.id)
        service.edit("a",ManagementCommand.GlobalGrant("m","d2",false))
        assertTrue(score() is DomainResult.Failure)
    }
    @Test fun invalidAssignmentCannotDestroyOldOverrideAndDefaultDoesNotGrant()=runBlocking {
        rejected{service.edit("a",ManagementCommand.Assignment("m","p1","part3"))}
        val member=data().members.first{it.id=="m"}
        service.edit("a",ManagementCommand.Member(member.copy(defaultPartDefinitionId="d2")))
        assertEquals("part1",(score() as DomainResult.Success).value.part.id)
        rejected{service.edit("a",ManagementCommand.Assignment("m","p1",null))}
        assertEquals("part1",(score() as DomainResult.Success).value.part.id)
        assertTrue(data().permissions.none{it.memberId=="m" && it.partDefinitionId=="d2" && !it.isArchived})
    }
    @Test fun bindingValidatesMovementRangeAndWholeMovementExclusivity()=runBlocking {
        service.edit("a",ManagementCommand.Movement(MovementEntity(id="mov",pieceId="p1",title="First",sortOrder=0)))
        service.edit("a",ManagementCommand.Movement(MovementEntity(id="other",pieceId="p2",title="Other",sortOrder=0)))
        val binding=data().bindings.first{it.id=="b1"}
        for(v in listOf(binding.copy(startPage=0),binding.copy(endPage=5),binding.copy(movementId="other"))) rejected{service.edit("a",ManagementCommand.Binding(v))}
        rejected{service.edit("a",ManagementCommand.Binding(binding.copy(id="new",movementId="mov")))}
        service.edit("a",ManagementCommand.Binding(binding.copy(movementId="mov")))
        assertEquals("mov",(score() as DomainResult.Success).value.segments.single().movementId)
    }
    @Test fun movementCompletenessFailsExplicitlyAndOrderingStillComesFromDomain()=runBlocking {
        service.edit("a",ManagementCommand.Movement(MovementEntity(id="first",pieceId="p1",title="First",sortOrder=5)))
        val b=data().bindings.first{it.id=="b1"};service.edit("a",ManagementCommand.Binding(b.copy(movementId="first")))
        service.edit("a",ManagementCommand.Movement(MovementEntity(id="second",pieceId="p1",title="Second",sortOrder=1)))
        assertEquals(ErrorCode.BINDING_MISSING,(score() as DomainResult.Failure).error.code)
        service.edit("a",ManagementCommand.Binding(PartScoreBindingEntity(id="second-binding",piecePartId="part1",movementId="second",scorePdfId="pdf1",startPage=4,endPage=4)))
        assertEquals(listOf("second","first"),(score() as DomainResult.Success).value.segments.map{it.movementId})
    }
    @Test fun uniquenessIncludesArchivedAndArchiveRecoveryKeepsIdentity()=runBlocking {
        rejected{service.edit("a",ManagementCommand.Part(PiecePartEntity(pieceId="p1",partDefinitionId="d1")))}
        val member=data().members.first{it.id=="m"};service.edit("a",ManagementCommand.Member(member.copy(isArchived=true)))
        rejected{service.edit("a",ManagementCommand.Member(MemberEntity(displayName="m")))}
        service.edit("a",ManagementCommand.Member(member))
        assertEquals(2,data().members.size);assertEquals("part1",(score() as DomainResult.Success).value.part.id)
    }
    @Test fun archivingLegacyDuplicateWholeBindingRepairsAmbiguityWithoutDeleting()=runBlocking {
        val duplicate=data().bindings.first{it.id=="b1"}.copy(id="legacy-duplicate")
        db.libraryDao().insertPartScoreBinding(duplicate)
        assertEquals(ErrorCode.AMBIGUOUS_BINDING,(score() as DomainResult.Failure).error.code)
        service.edit("a",ManagementCommand.Binding(duplicate.copy(isArchived=true)))
        assertTrue(score() is DomainResult.Success);assertEquals(4,data().bindings.size)
    }
    @Test fun reorderHandlesArchivedSlotsAndLegacyNegativeOrderAtomically()=runBlocking {
        val old=data().items.first{it.id=="i3"};db.libraryDao().updateConcertItem(old.copy(sortOrder=Int.MIN_VALUE))
        service.edit("a",ManagementCommand.Reorder("c",listOf("i2","i1")))
        assertEquals(listOf("i2","i1"),(ScoreResolver(data()).resolveConcert("m","c") as DomainResult.Success).value.items.map{it.itemId})
        assertEquals(listOf(0,1,2),data().items.filter{it.concertId=="c"}.map{it.sortOrder}.sorted())
        assertTrue(data().items.first{it.id=="i3"}.isArchived)
        val before=data();rejected{service.edit("a",ManagementCommand.Reorder("c",listOf("i1","foreign")))};assertEquals(before,data())
    }
    @Test fun lockedProgrammeProtectsStructureButRevocationStillFailsClosed()=runBlocking {
        freeze();val d=data()
        for(command in listOf<ManagementCommand>(ManagementCommand.Piece(d.pieces.first{it.id=="p1"}.copy(title="Changed")),
            ManagementCommand.Binding(d.bindings.first{it.id=="b1"}.copy(endPage=4)),ManagementCommand.Item(d.items.first{it.id=="i1"}.copy(isEnabled=false)),
            ManagementCommand.Reorder("c",listOf("i2","i1")),ManagementCommand.Definition(d.definitions.first{it.id=="d1"}.copy(name="Changed"))))
            rejected{service.edit("a",command)}
        assertEquals(d,data())
        service.edit("a",ManagementCommand.GlobalGrant("m","d1",false));assertTrue(score() is DomainResult.Failure)
        assertNotNull(db.annotationDao().snapshot("c"))
    }
    @Test fun explicitAssignmentChangeInvalidatesOldConcertRouteEvenWhenSnapshotExists()=runBlocking {
        freeze();service.edit("a",ManagementCommand.ScopedGrant("m","part2",true));service.edit("a",ManagementCommand.Assignment("m","p1","part2"))
        val resolver=ScoreResolver(data());val presentation=resolver.presentation("m",ScreenRequest.Viewer("p1","part1","c","i1")) as DomainResult.Success
        assertTrue(presentation.value.content is DomainResult.Failure)
        assertEquals("part2",(resolver.resolveConcert("m","c") as DomainResult.Success).value.items.first().part.id)
    }
    @Test fun versionSwitchChecksOwnershipRangesAndPreservesVersionScopedInk()=runBlocking {
        val old=data().versions.first{it.id=="v1"}
        db.libraryDao().insertScorePdfVersion(old.copy(id="short",versionNumber=2,pageCount=2))
        db.libraryDao().insertScorePdfVersion(old.copy(id="new",versionNumber=3,pageCount=5))
        rejected{service.activateVersion("a","pdf1","foreign")};rejected{service.activateVersion("a","pdf1","short")}
        assertEquals("v1",data().pdfs.first{it.id=="pdf1"}.activeVersionId)
        val ink=InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.3f)))
        val scope=AnnotationScope("m","p1","part1","v1",1);AnnotationRepository(db).save(scope,AnnotationLayer.PERSONAL,listOf(ink),0)
        service.activateVersion("a","pdf1","new")
        assertTrue(AnnotationRepository(db).load(scope.copy(version="new")).personal.isEmpty())
        rejected{AnnotationRepository(db).load(scope)}
        service.activateVersion("a","pdf1","v1")
        assertEquals(listOf(ink),AnnotationRepository(db).load(scope).personal)
        assertEquals(3,data().versions.count{it.scorePdfId=="pdf1"})
    }
    @Test fun staleSnapshotCannotOverwriteAndLockedVersionCannotChange()=runBlocking {
        val before=data();val piece=before.pieces.first{it.id=="p1"}
        service.edit("a",ManagementCommand.Piece(piece.copy(title="Fresh")))
        rejected{service.edit("a",ManagementCommand.Piece(piece.copy(title="Stale")),before)}
        assertEquals("Fresh",data().pieces.first{it.id=="p1"}.title)
        val old=data().versions.first{it.id=="v1"};db.libraryDao().insertScorePdfVersion(old.copy(id="new",versionNumber=2))
        freeze();rejected{service.activateVersion("a","pdf1","new")};assertEquals("v1",data().pdfs.first{it.id=="pdf1"}.activeVersionId)
    }
    @Test fun administratorIsRevalidatedAfterAsynchronousFileInspection()=runBlocking {
        val old=data().versions.first{it.id=="v1"};db.libraryDao().insertScorePdfVersion(old.copy(id="new",versionNumber=2))
        val barrier=CompletableDeferred<Unit>();inspectionBarrier=barrier
        val ready=async(Dispatchers.Default){runCatching{service.activateVersion("a","pdf1","new")}}
        delay(100);val actor=data().members.first{it.id=="a"};db.libraryDao().updateMember(actor.copy(role=MemberRole.MEMBER))
        barrier.complete(Unit);assertTrue(ready.await().isFailure)
        assertEquals("v1",data().pdfs.first{it.id=="pdf1"}.activeVersionId)
    }
}
