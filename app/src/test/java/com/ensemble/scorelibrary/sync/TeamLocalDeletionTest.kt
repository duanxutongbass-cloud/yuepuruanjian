package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.core.database.entity.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class TeamLocalDeletionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = TeamLibraryScope(context)
    private val bytes = "%PDF-synthetic local deletion storage fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val session = TeamSession(TEAM_SERVICE_URL, "synthetic-session", Long.MAX_VALUE,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "Synthetic ensemble", "director", "Director", "admin", true))
    private val journal = TeamDeletionJournal(context.noBackupFilesDir, session.server, session.principal.teamId) { SecretKeySpec(ByteArray(32) { 6 }, "AES") }
    private fun artifact(number: String) = TeamArtifact(UUID.randomUUID().toString(), "Synthetic score ${number.takeLast(3)}",
        "Instrument ${number.take(2)}", hash, bytes.size.toLong(), 1, number, "01 Test")
    private val a = artifact("0101001")
    private val b = artifact("0201001")
    private val other = artifact("0101002")
    private val scene = TeamProgram(UUID.randomUUID().toString(), "Target scene", listOf(a.pieceKey), UUID.randomUUID().toString(), listOf(a.id, b.id))
    private val unrelated = TeamProgram(UUID.randomUUID().toString(), "Unrelated scene", listOf(other.pieceKey), UUID.randomUUID().toString(), listOf(other.id))
    private lateinit var c: TeamCollection
    private suspend fun install(scores: List<TeamArtifact> = listOf(a, b, other), programs: List<TeamProgram> = listOf(scene, unrelated)) {
        val catalog = TeamCatalog(1, scores, programs)
        val client = object : TeamScoreTransport {
            override suspend fun catalog(session: TeamSession) = catalog
            override suspend fun download(session: TeamSession, artifact: TeamArtifact, target: File) { target.writeBytes(bytes) }
            override suspend fun frozen(session: TeamSession, program: TeamProgram, catalog: TeamCatalog) = syntheticTeamFreeze(program, catalog)
        }
        val sync = TeamLibrarySync(scope, client, object : TeamPdfVerifier { override fun verify(file: File, pages: Int) {} })
        sync.activate(session);c = checkNotNull(scope.active.value)
        sync.sync(session) { _, _ -> }
    }
    private fun stage(ids: Set<String>, barrier: Boolean = true): TeamDeletionPage {
        val request = UUID.randomUUID().toString()
        val rows = JSONArray().apply { ids.forEachIndexed { i, id -> put(JSONObject().put("sequence", (i + 1).toString())
            .put("artifactId", id).put("requestId", request).put("deletedAt", 1792350000)) } }
        val reply = JSONObject().put("protocolVersion", 1).put("storageId", UUID.randomUUID().toString())
            .put("teamId", session.principal.teamId).put("accountId", session.principal.accountId)
            .put("after", "0").put("through", ids.size.toString()).put("nextAfter", ids.size.toString()).put("more", false)
            .put("deletions", rows).put("jobs", JSONArray().put(JSONObject().put("requestId", request)
                .put("programIds", JSONArray()).put("freezeIds", JSONArray()).put("pieceReferences", JSONArray())))
        val page = TeamDeletionPage.parse(reply, session.server, session.principal.teamId, session.principal.accountId, null, 0)
        journal.stage(page, session.principal.accountId);c.deletionChanging = barrier
        return page
    }
    private suspend fun notes(score: TeamArtifact) {
        val dao = c.database.annotationDao()
        for (owner in listOf(c.member, "shared")) {
            val page = AnnotationPageEntity(versionId = score.version, pageIndex = 0, ownerKey = owner, payload = "target-ink", generation = 1)
            dao.insertPage(page)
            val revision = AnnotationRevisionEntity(documentId = page.id, payload = "history-ink", createdBy = c.member)
            dao.insertRevision(revision);dao.updatePage(page.copy(publishedRevisionId = revision.id))
        }
        dao.putCloud(AnnotationCloudEntity("cloud-${score.id}", score.version, 0, "personal", baseline = "baseline-ink",
            requestId = UUID.randomUUID().toString(), requestBody = "pending-ink", conflictPayload = "conflict-ink"))
        if (c.database.resolutionDao().assignments().none { it.memberId == c.member && it.piecePartId == score.part })
            c.database.libraryDao().insertAssignment(PiecePartAssignmentEntity(memberId = c.member, piecePartId = score.part))
    }
    private suspend fun file(score: TeamArtifact): File = File(c.database.resolutionDao().versions().single { it.id == score.version }.managedFilePath)
    private fun partial(score: TeamArtifact) = File(c.directory, "resume-${teamId("partial/${score.id}/${score.hash}/${score.size}/${score.pages}")}.part")
    private fun count(table: String): Int = c.database.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst();it.getInt(0) }
    private fun foreignKeys() { c.database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) } }
    @After fun cleanup() {
        scope.deactivate()
        if (::c.isInitialized) {
            c.database.close()
            val path = checkNotNull(c.directory.parentFile).canonicalFile
            check(path.parentFile == File(context.noBackupFilesDir, "team-libraries").canonicalFile && path.name == c.key)
            path.deleteRecursively()
        }
        val retired = File(context.noBackupFilesDir, "team-deletions/${teamId("${session.server}/${session.principal.teamId}")}").canonicalFile
        check(retired.parentFile == File(context.noBackupFilesDir, "team-deletions").canonicalFile)
        retired.deleteRecursively()
    }
    @Test fun erasesOnlyTargetPdfAndInk() = runBlocking {
        install();notes(a);notes(other)
        val original = File(context.cacheDir, UUID.randomUUID().toString() + ".pdf").also { it.writeBytes(bytes) }
        try {
            val deleted = file(a);val kept = file(b);val independent = file(other)
            partial(a).writeBytes(bytes.take(8).toByteArray())
            val p = stage(setOf(a.id))
            val result = TeamLocalDeletion().purge(c, setOf(a.id), journal)
            assertFalse(deleted.exists());assertFalse(partial(a).exists());assertArrayEquals(bytes, kept.readBytes())
            assertArrayEquals(bytes, independent.readBytes());assertArrayEquals(bytes, original.readBytes())
            val data = c.database.resolutionDao().snapshot()
            assertEquals(setOf(b.version, other.version), data.versions.map { it.id }.toSet())
            assertEquals(setOf(a.piece, other.piece), data.pieces.map { it.id }.toSet())
            assertEquals(setOf(unrelated.concert), data.concerts.map { it.id }.toSet())
            assertEquals(2, count("annotation_pages"));assertEquals(2, count("annotation_revisions"));assertEquals(1, count("annotation_cloud"))
            assertEquals(setOf(other.version), c.database.annotationDao().cloudPages().map { it.versionId }.toSet())
            assertEquals(setOf(a.version), result.versions);assertTrue(result.removedLocalPieces.isEmpty())
            assertEquals(setOf(scene.concert), result.concerts);assertEquals(0L, journal.read().cursor);assertEquals(p, journal.read().pending)
            assertTrue(c.deletionChanging);assertTrue(runCatching { scope.requireCurrent(c) }.isFailure)
            foreignKeys()
        } finally { original.delete() }
    }
    @Test fun removesLastPartAndRetries() = runBlocking {
        val alone = scene.copy(frozenArtifacts = listOf(a.id))
        install(listOf(a, other), listOf(alone, unrelated));notes(a)
        c.database.libraryDao().insertMovement(MovementEntity(pieceId = a.piece, title = "Movement", sortOrder = 1))
        stage(setOf(a.id));val first = TeamLocalDeletion().purge(c, setOf(a.id), journal)
        assertEquals(setOf(a.piece), first.removedLocalPieces)
        val data = c.database.resolutionDao().snapshot()
        assertEquals(listOf(other.piece), data.pieces.map { it.id });assertTrue(data.movements.isEmpty())
        assertTrue(data.assignments.none { it.piecePartId == a.part });assertTrue(data.extraPermissions.none { it.piecePartId == a.part })
        assertTrue(data.items.none { it.pieceId == a.piece });assertEquals(0, count("annotation_revisions"))
        val second = TeamLocalDeletion().purge(c, setOf(a.id), journal)
        assertTrue(second.versions.isEmpty());assertEquals(0, second.removedFiles);foreignKeys()
    }
    @Test fun legacyPathAlias()=runBlocking {
        install()
        val target=file(a)
        val old=c.database.resolutionDao().versions().single { it.id==a.version }
        val alias=File(File(target.parentFile,"."),target.name)
        assertEquals(target.canonicalFile,alias.canonicalFile)
        c.database.libraryDao().updateVersion(old.copy(managedFilePath=alias.absolutePath))
        stage(setOf(a.id))
        TeamLocalDeletion().purge(c,setOf(a.id),journal)
        assertFalse(target.exists())
        assertTrue(file(b).exists())
        assertFalse(c.database.resolutionDao().versions().any { it.id==a.version })
    }
    @Test fun preservesChangedOrExternalFile() = runBlocking {
        install();val target = file(a);target.writeBytes("Unexpected replacement".toByteArray());stage(setOf(a.id))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        assertEquals("Unexpected replacement", target.readText());assertEquals(3, count("score_pdf_versions"))
        target.writeBytes(bytes)
        val external = File(context.cacheDir, UUID.randomUUID().toString() + ".pdf").also { it.writeBytes(bytes) }
        try {
            val old = c.database.resolutionDao().versions().single { it.id == a.version }
            c.database.libraryDao().updateVersion(old.copy(managedFilePath = external.absolutePath))
            assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
            assertArrayEquals(bytes, external.readBytes());assertTrue(target.exists());assertEquals(3, count("score_pdf_versions"))
        } finally { external.delete() }
    }
    @Test fun retriesAfterSqlFailure() = runBlocking {
        install();notes(a);val target = file(a);stage(setOf(a.id))
        val raw = c.database.openHelper.writableDatabase
        raw.execSQL("CREATE TABLE deletion_test_guard(id TEXT PRIMARY KEY,version TEXT REFERENCES score_pdf_versions(id) ON DELETE RESTRICT)")
        raw.execSQL("INSERT INTO deletion_test_guard VALUES('guard',?)", arrayOf(a.version))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        assertFalse(target.exists());assertEquals(3, count("score_pdf_versions"));assertEquals(2, count("annotation_pages"))
        assertEquals(0L, journal.read().cursor);assertNotNull(journal.read().pending)
        raw.execSQL("DROP TABLE deletion_test_guard")
        TeamLocalDeletion().purge(c, setOf(a.id), journal)
        assertEquals(2, count("score_pdf_versions"));assertEquals(0, count("annotation_pages"));foreignKeys()
    }
    @Test fun retriesAfterUnlinkFailure() = runBlocking {
        install();stage(setOf(a.id, other.id));var calls = 0
        val fault = TeamLocalDeletion { file -> if (++calls == 2) throw IllegalStateException("Injected unlink fault") else file.delete() }
        assertTrue(runCatching { fault.purge(c, setOf(a.id, other.id), journal) }.isFailure)
        assertEquals(3, count("score_pdf_versions"));assertEquals(0L, journal.read().cursor)
        TeamLocalDeletion().purge(c, setOf(a.id, other.id), journal)
        assertEquals(listOf(b.version), c.database.resolutionDao().versions().map { it.id });assertArrayEquals(bytes, file(b).readBytes())
        foreignKeys()
    }
    @Test fun requiresAuthorityAndBarrier() = runBlocking {
        install();val target = file(a)
        c.deletionChanging = true
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        stage(setOf(a.id), barrier = false)
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        c.deletionChanging = true
        val version = c.database.resolutionDao().versions().single { it.id == a.version }
        c.database.libraryDao().updateVersion(version.copy(originalUri = "local-original"))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        assertTrue(target.exists());assertEquals(3, count("score_pdf_versions"))
    }
    @Test fun rejectsCorruptFreeze() = runBlocking {
        install();stage(setOf(a.id));val target = file(a)
        c.database.openHelper.writableDatabase.execSQL("UPDATE annotation_concert_snapshots SET payload='bad-json' WHERE concertId=?", arrayOf(scene.concert))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        assertTrue(target.exists());assertEquals(3, count("score_pdf_versions"));assertEquals(0L, journal.read().cursor)
    }
    @Test fun preservesSharedFileIdentity() = runBlocking {
        install();val target = file(a);val otherVersion = c.database.resolutionDao().versions().single { it.id == other.version }
        c.database.libraryDao().updateVersion(otherVersion.copy(managedFilePath = target.absolutePath))
        stage(setOf(a.id))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        assertArrayEquals(bytes, target.readBytes());assertEquals(3, count("score_pdf_versions"));assertEquals(0L, journal.read().cursor)
        c.database.libraryDao().updateVersion(otherVersion)
        val cache = c.database.resolutionDao().caches().single { it.scorePdfVersionId == other.version }
        c.database.libraryDao().updateCache(cache.copy(cachedFilePath = target.absolutePath))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        c.database.libraryDao().updateCache(cache)
        val pdf = c.database.resolutionDao().pdfs().single { it.id == other.pdf }
        c.database.libraryDao().updateScorePdf(pdf.copy(activeVersionId = a.version))
        assertTrue(runCatching { TeamLocalDeletion().purge(c, setOf(a.id), journal) }.isFailure)
        assertArrayEquals(bytes, target.readBytes());assertEquals(3, count("score_pdf_versions"))
    }
}
