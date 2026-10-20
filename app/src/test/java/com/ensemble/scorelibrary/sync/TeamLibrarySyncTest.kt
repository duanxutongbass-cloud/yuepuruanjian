package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.core.database.entity.AnnotationPageEntity
import com.ensemble.scorelibrary.core.database.entity.AnnotationRevisionEntity
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.data.RoomScoreRepository
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TeamLibrarySyncTest {
    @Test fun reusedNumberDoesNotInheritOldRoomPieceOrPrivateInk() = runBlocking {
        val old=artifact();catalog=TeamCatalog(1,listOf(old));val c=activate();sync.sync(session) { _,_-> }
        val page=AnnotationPageEntity(versionId=old.version,pageIndex=0,ownerKey=c.member)
        c.database.annotationDao().insertPage(page)
        val revision=AnnotationRevisionEntity(documentId=page.id,payload="[]",createdBy=c.member)
        c.database.annotationDao().insertRevision(revision)
        // Number release follows the authenticated permanent-deletion cleanup.
        // Merely losing catalog permission must never clear a local number.
        val journalRoot=File(context.cacheDir,"number-release-${UUID.randomUUID()}").apply { mkdirs() }
        val journal=TeamDeletionJournal(journalRoot,session.server,session.principal.teamId) {
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { 8 },"AES")
        }
        val request=UUID.randomUUID().toString()
        val deleted=TeamDeletionPage(session.server,UUID.randomUUID().toString(),session.principal.teamId,session.principal.accountId,
            0,1,1,false,listOf(TeamDeletion(1,old.id,request,1792350000)),listOf(TeamDeletionImpact(request,emptySet(),emptySet(),setOf(old.piece))))
        journal.stage(deleted,session.principal.accountId);c.deletionChanging=true
        TeamLocalDeletion().purge(c,setOf(old.id),journal);c.deletionChanging=false
        check(journalRoot.parentFile==context.cacheDir);journalRoot.deleteRecursively()
        catalog=TeamCatalog(2,emptyList());sync.sync(session) { _,_-> }
        val reused=artifact(title="New score with released number").copy(pieceGeneration=UUID.randomUUID().toString())
        catalog=TeamCatalog(3,listOf(reused));sync.sync(session) { _,_-> }
        val data=c.database.resolutionDao().snapshot()
        assertNotEquals(old.piece,reused.piece);assertNotEquals(old.part,reused.part)
        assertEquals(reused.piece,data.pieces.single { !it.isArchived }.id)
        assertEquals("New score with released number",data.pieces.single { !it.isArchived }.title)
        assertFalse(data.pieces.any { it.id==old.piece })
        assertFalse(c.database.annotationDao().pages().any { it.versionId==reused.version })
        assertTrue(File(data.versions.single { it.id==reused.version }.managedFilePath).isFile)
    }
    @Test fun expandedNumbersKeepDistinctRoomReferencesAndCategories() = runBlocking {
        val numbers=listOf("0101999", "01-01-1000", "01-01-10000", "01-01-9223372036854775807")
        val artifacts=numbers.map { artifact(it,title="Work $it") }
        catalog=TeamCatalog(1,artifacts)
        val current=activate();sync.sync(session) { _,_-> }
        val data=current.database.resolutionDao().snapshot()
        assertEquals(setOf("999","1000","10000","9223372036854775807"),data.pieces.map { it.organizerSequence }.toSet())
        assertTrue(data.pieces.all { it.categoryCode=="01" })
        assertEquals(4,data.versions.size)
        assertTrue(data.versions.all { TeamLibrarySync.hash(File(it.managedFilePath))==hash })
        assertEquals(numbers,artifacts.map { it.number })
    }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = TeamLibraryScope(context)
    private val local = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
    private val collections = mutableListOf<TeamCollection>()
    private val bytes = "%PDF-synthetic bytes for storage boundary tests".toByteArray()
    private val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private var catalog = TeamCatalog(1, emptyList())
    private var downloads = 0
    private var downloadAction: suspend () -> Unit = {}
    private var invalidPdf = false
    private var freezeRequests=0
    private var duringFreeze:suspend ()->Unit = {}
    private val frozenMark=InkMark(kind=MarkKind.SYMBOL,points=listOf(InkPoint(.3f,.4f)),symbolScale=.75f)
    private val client = object : TeamScoreTransport {
        override suspend fun catalog(session: TeamSession) = catalog
        override suspend fun download(session: TeamSession, artifact: TeamArtifact, target: File) {
            downloads++
            target.writeBytes(bytes)
            downloadAction()
        }
        override suspend fun frozen(session:TeamSession,program:TeamProgram,catalog:TeamCatalog):TeamProgramSnapshot {
            freezeRequests++;duringFreeze();return syntheticTeamFreeze(program,catalog,listOf(frozenMark))
        }
    }
    private val verifier = object : TeamPdfVerifier {
        override fun verify(file: File, pages: Int) { if (invalidPdf) throw TeamSyncFailure("invalid PDF") }
    }
    private val sync = TeamLibrarySync(scope, client, verifier)
    private val session = identity()
    private fun identity() = TeamSession(TEAM_SERVICE_URL, "synthetic-session", Long.MAX_VALUE,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "Synthetic ensemble", "director", "Director", "admin", true))
    private fun artifact(number: String = "0101001", title: String = "Synthetic score", part: String = "01 Violin") =
        TeamArtifact(UUID.randomUUID().toString(), title, part, hash, bytes.size.toLong(), 1, number, "01 Test category")
    private suspend fun activate(identity: TeamSession = session): TeamCollection {
        sync.activate(identity)
        return scope.active.value!!.also { collections += it }
    }
    @Test fun bulkCatalog()=runBlocking {
        val artifacts=(1..80).flatMap { piece -> (1..3).map { part ->
            artifact("%02d01%03d".format(part,piece),"Synthetic $piece","Part $part") } }
        catalog=TeamCatalog(1,artifacts);val current=activate()
        val started=System.nanoTime();sync.sync(session) { _,_-> }
        println("CATALOG_240_INSTALL_MS=${(System.nanoTime()-started)/1_000_000}")
        assertEquals(240,downloads)
        val first=current.database.resolutionDao().snapshot()
        val resolver=com.ensemble.scorelibrary.domain.ScoreResolver(first)
        assertEquals(3,(resolver.visibleParts(current.member,artifacts.first().piece) as com.ensemble.scorelibrary.domain.DomainResult.Success).value.size)
        assertTrue(resolver.resolvePart(current.member,artifacts.first().piece,artifacts.first().part) is com.ensemble.scorelibrary.domain.DomainResult.Success)
        sync.sync(session) { _,_-> };assertEquals(240,downloads)
        val removed=artifacts.first();catalog=TeamCatalog(2,artifacts.drop(1));sync.sync(session) { _,_-> }
        assertEquals(240,downloads)
        val after=current.database.resolutionDao().snapshot()
        assertTrue(com.ensemble.scorelibrary.domain.ScoreResolver(after).resolvePart(current.member,removed.piece,removed.part) is com.ensemble.scorelibrary.domain.DomainResult.Failure)
        assertEquals(first.versions.map { it.id to it.fileHash }.toSet(),after.versions.map { it.id to it.fileHash }.toSet())
        assertTrue(after.versions.all { File(it.managedFilePath).isFile })
    }
    @After fun cleanup() {
        scope.deactivate()
        local.close()
        collections.distinctBy { it.database }.forEach { it.database.close() }
        val root = File(context.noBackupFilesDir, "team-libraries").canonicalFile
        collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { target ->
            check(target.parentFile == root && target.name.matches(Regex("[a-f0-9-]{36}")))
            target.deleteRecursively()
        }
    }
    @Test fun freezeUsesOriginalVoicesAfterNewVoiceAndLiveInkUpdates() = runBlocking {
        val a=artifact();val p=TeamProgram(UUID.randomUUID().toString(),"Concert",listOf(a.pieceKey),UUID.randomUUID().toString(),listOf(a.id))
        catalog=TeamCatalog(1,listOf(a),listOf(p))
        val c=activate();sync.sync(session) { _,_-> }
        val saved=c.database.annotationDao().snapshot(p.concert)!!
        val annotations=AnnotationRepository(local,scope)
        val ink=AnnotationScope(c.member,a.piece,a.part,a.version,0,p.item(a.pieceKey))
        assertEquals(listOf(frozenMark),annotations.load(ink).shared)
        assertTrue(annotations.load(ink).locked)
        assertTrue(runCatching { annotations.freeze(ink) }.isFailure)
        // A later live publication and a newly assigned voice cannot alter this scene.
        val page=AnnotationPageEntity(versionId=a.version,pageIndex=0,ownerKey="shared")
        c.database.annotationDao().insertPage(page)
        val revision=AnnotationRevisionEntity(documentId=page.id,payload="[]",createdBy=c.member)
        c.database.annotationDao().insertRevision(revision)
        c.database.annotationDao().updatePage(page.copy(publishedRevisionId=revision.id))
        val b=artifact("0201001",part="02 Cello")
        catalog=TeamCatalog(2,listOf(a,b),listOf(p));sync.sync(session) { _,_-> }
        assertEquals(1,freezeRequests)
        assertEquals(saved,c.database.annotationDao().snapshot(p.concert))
        val raw=c.database.resolutionDao().snapshot()
        val frozen=scope.project(c,raw,saved)
        val concert=(ScoreResolver(frozen).resolveConcert(c.member,p.concert) as DomainResult.Success).value
        assertEquals(a.part,concert.items.single().part.id)
        assertEquals(2,raw.parts.size)
        assertEquals(listOf(frozenMark),annotations.load(ink).shared)
        assertTrue(raw.versions.all { TeamLibrarySync.hash(File(it.managedFilePath))==hash })
    }
    @Test fun grantChangeDuringFreezeDownloadCannotInstallStaleScene() = runBlocking {
        val a=artifact();val p=TeamProgram(UUID.randomUUID().toString(),"Concert",listOf(a.pieceKey),UUID.randomUUID().toString(),listOf(a.id))
        catalog=TeamCatalog(1,listOf(a),listOf(p));val c=activate()
        duringFreeze={catalog=TeamCatalog(2,emptyList())}
        assertTrue(runCatching { sync.sync(session) { _,_-> } }.exceptionOrNull() is TeamSyncFailure)
        assertNull(c.database.annotationDao().snapshot(p.concert))
        assertTrue(c.database.resolutionDao().snapshot().extraPermissions.none { !it.isArchived })
    }
    // Robolectric puts the method name in native Windows SQLite paths (MAX_PATH).
    @Test fun freezeColdRestore() = runBlocking {
        val a=artifact();val p=TeamProgram(UUID.randomUUID().toString(),"Concert",listOf(a.pieceKey),UUID.randomUUID().toString(),listOf(a.id))
        catalog=TeamCatalog(1,listOf(a),listOf(p))
        val now=System.currentTimeMillis()/1000
        val verified=session.copy(expiresAt=now+OFFLINE_SECONDS,verifiedAt=now,lastSeenAt=now,deviceId=UUID.randomUUID().toString())
        val secret=javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val checkpoint=TeamOfflineStore(context.noBackupFilesDir,{secret})
        val online=TeamLibrarySync(scope,client,verifier,offline=checkpoint)
        online.activate(verified);val first=scope.active.value!!.also { collections+=it }
        online.sync(verified) { _,_-> }
        val original=first.database.annotationDao().snapshot(p.concert)!!
        scope.deactivate();first.database.close()
        val restoredScope=TeamLibraryScope(context)
        val noNetwork=object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession):TeamCatalog=error("offline catalogue network call")
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File)=error("offline PDF network call")
        }
        val restored=TeamLibrarySync(restoredScope,noNetwork,verifier,offline=checkpoint)
        val offline=verified.copy(offline=true)
        try {
            restored.activate(offline);val c=restoredScope.active.value!!.also { collections+=it }
            assertEquals(original,c.database.annotationDao().snapshot(p.concert))
            val ink=AnnotationRepository(local,restoredScope).load(AnnotationScope(c.member,a.piece,a.part,a.version,0,p.item(a.pieceKey)))
            assertTrue(ink.locked);assertEquals(listOf(frozenMark),ink.shared)
            c.database.openHelper.writableDatabase.execSQL("DELETE FROM annotation_concert_snapshots WHERE concertId=?",arrayOf(p.concert))
            restoredScope.deactivate();restored.activate(offline)
            val missing=restoredScope.active.value!!.also { collections+=it }
            val data=missing.database.resolutionDao().snapshot()
            assertTrue(data.concerts.single { it.id==p.concert }.isArchived)
            assertTrue(ScoreResolver(data).resolvePart(missing.member,a.piece,a.part) is DomainResult.Success)
        } finally { restoredScope.deactivate() }
    }
    @Test fun installKeepsLocalAndRole() = runBlocking {
        local.libraryDao().insertPiece(PieceEntity(id = "local-only", title = "Private local original"))
        val a = artifact()
        val b = artifact("0201001", part = "02 Cello")
        catalog = TeamCatalog(1, listOf(a, b))
        val collection = activate()
        assertEquals(2, sync.sync(session) { _, _ -> })
        val data = collection.database.resolutionDao().snapshot()
        assertEquals(1, data.pieces.size)
        assertEquals("001", data.pieces.single().organizerSequence)
        assertEquals("01 Test category", data.pieces.single().categoryName)
        assertEquals(2, data.parts.size)
        assertEquals(MemberRole.MEMBER, data.members.single().role)
        assertTrue(data.permissions.isEmpty())
        assertEquals(2, (ScoreResolver(data).visibleParts(collection.member, a.piece) as DomainResult.Success).value.size)
        assertTrue(ScoreResolver(data).resolvePart(collection.member, a.piece, a.part) is DomainResult.Success)
        assertEquals("local-only", local.resolutionDao().pieces().single().id)
        assertTrue(data.versions.all { File(it.managedFilePath).isFile && it.managedFilePath.startsWith(context.noBackupFilesDir.path) })
    }
    @Test fun repeatKeepsAnnotations() = runBlocking {
        val a = artifact(); catalog = TeamCatalog(1, listOf(a))
        val collection = activate(); sync.sync(session) { _, _ -> }
        val annotations = AnnotationRepository(local, scope)
        val ink = AnnotationScope(collection.member, a.piece, a.part, a.version, 0)
        annotations.save(ink, AnnotationLayer.PERSONAL, emptyList(), 0)
        sync.sync(session) { _, _ -> }
        assertEquals(1, downloads)
        assertEquals(1L, annotations.load(ink).personalGeneration)
        assertEquals(1, collection.database.resolutionDao().versions().size)
    }
    @Test fun revokeBeforeFailedDownload() = runBlocking {
        val a = artifact(); val b = artifact("0201002", "Revoked score", "02 Cello")
        catalog = TeamCatalog(1, listOf(a, b))
        val collection = activate(); sync.sync(session) { _, _ -> }
        val revokedFile = collection.database.resolutionDao().versions().single { it.id == b.version }.managedFilePath
        val c = artifact("0301003", "New score", "03 Viola")
        catalog = TeamCatalog(2, listOf(a, c))
        downloadAction = { throw IOException("offline") }
        assertTrue(runCatching { sync.sync(session) { _, _ -> } }.isFailure)
        val data = scope.project(collection, collection.database.resolutionDao().snapshot())
        assertEquals(listOf(a.piece), data.pieces.map { it.id })
        assertTrue(ScoreResolver(data).resolvePart(collection.member, b.piece, b.part) is DomainResult.Failure)
        assertTrue(File(revokedFile).isFile) // Retain immutable data and annotations; revoke readability.
        assertFalse(collection.directory.listFiles()!!.any { it.extension == "pending" })
    }
    @Test fun recheckBeforeCommit() = runBlocking {
        val a = artifact(); catalog = TeamCatalog(1, listOf(a))
        val collection = activate()
        downloadAction = { catalog = TeamCatalog(2, emptyList()) }
        assertTrue(runCatching { sync.sync(session) { _, _ -> } }.isFailure)
        assertTrue(collection.database.resolutionDao().versions().isEmpty())
        assertTrue(collection.directory.listFiles()!!.isEmpty())
    }
    @Test fun logoutDropsLateDownload() = runBlocking {
        val a = artifact(); catalog = TeamCatalog(1, listOf(a))
        val collection = activate()
        downloadAction = { sync.deactivate() }
        assertTrue(runCatching { sync.sync(session) { _, _ -> } }.isFailure)
        assertNull(scope.active.value)
        assertTrue(collection.database.resolutionDao().versions().isEmpty())
        assertTrue(collection.directory.listFiles()!!.isEmpty())
    }
    @Test fun corruptPdfNotInstalled() = runBlocking {
        val a = artifact(); catalog = TeamCatalog(1, listOf(a.copy(hash = "0".repeat(64))))
        val collection = activate()
        assertTrue(runCatching { sync.sync(session) { _, _ -> } }.isFailure)
        catalog = TeamCatalog(2, listOf(a)); invalidPdf = true
        assertTrue(runCatching { sync.sync(session) { _, _ -> } }.isFailure)
        assertTrue(collection.database.resolutionDao().versions().isEmpty())
        assertTrue(collection.directory.listFiles()!!.isEmpty())
    }
    @Test fun accountAndRestartIsolation() = runBlocking {
        val a = artifact(); catalog = TeamCatalog(1, listOf(a))
        val first = activate(); sync.sync(session) { _, _ -> }
        val secondSession = identity()
        val second = activate(secondSession)
        assertNotEquals(first.directory, second.directory)
        assertTrue(second.database.resolutionDao().versions().isEmpty())
        assertTrue(runCatching { RoomScoreRepository(local, scope).resolvePiece(first.member, a.piece) }.isFailure)
        val reopened = activate()
        assertTrue(scope.project(reopened, reopened.database.resolutionDao().snapshot()).pieces.isEmpty())
        sync.sync(session) { _, _ -> }
        assertEquals(1, downloads)
        assertEquals(1, scope.project(reopened, reopened.database.resolutionDao().snapshot()).pieces.size)
    }
    @Test fun repairKeepsAnnotations() = runBlocking {
        val a = artifact(); catalog = TeamCatalog(1, listOf(a))
        val collection = activate(); sync.sync(session) { _, _ -> }
        val annotations = AnnotationRepository(local, scope)
        val ink = AnnotationScope(collection.member, a.piece, a.part, a.version, 0)
        annotations.save(ink, AnnotationLayer.PERSONAL, emptyList(), 0)
        val old = collection.database.resolutionDao().versions().single()
        File(old.managedFilePath).writeText("damaged")
        sync.sync(session) { _, _ -> }
        val repaired = collection.database.resolutionDao().versions().single()
        assertEquals(old.id, repaired.id)
        assertNotEquals(old.managedFilePath, repaired.managedFilePath)
        assertEquals(a.hash, TeamLibrarySync.hash(File(repaired.managedFilePath)))
        assertEquals(1L, annotations.load(ink).personalGeneration)
    }
}
