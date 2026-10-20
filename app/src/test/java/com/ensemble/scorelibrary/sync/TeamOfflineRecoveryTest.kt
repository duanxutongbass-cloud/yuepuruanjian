package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.KeyGenerator

/** Synthetic immutable bytes exercise storage/authorization; native PDF rendering is a device check. */
@RunWith(RobolectricTestRunner::class)
class TeamOfflineRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = TeamLibraryScope(context)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = System.currentTimeMillis() / 1000
    private val storage = TeamOfflineStore(context.noBackupFilesDir, { key }, { now })
    private val session = TeamSession(TEAM_SERVICE_URL, "s".repeat(43), now + OFFLINE_SECONDS,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "Synthetic ensemble", "violinist", "Violinist", "member", true), now, now, UUID.randomUUID().toString())
    private val bytes = "%PDF-only synthetic storage boundary fixture".toByteArray()
    private val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun artifact(number: String) = TeamArtifact(UUID.randomUUID().toString(), "Score $number", "Violin", hash, bytes.size.toLong(), 1, number, "Test")
    private var catalog = TeamCatalog(1, emptyList())
    private var offline = false
    private var failDownload = false
    private var requests = 0
    private val transport = object : TeamScoreTransport {
        override suspend fun catalog(session: TeamSession): TeamCatalog {
            requests++; if (offline) throw IOException("no connection"); return catalog
        }
        override suspend fun download(session: TeamSession, artifact: TeamArtifact, target: File) {
            requests++; if (offline || failDownload) throw IOException("download interrupted"); target.writeBytes(bytes)
        }
    }
    private val sync = TeamLibrarySync(scope, transport, object : TeamPdfVerifier { override fun verify(file: File, pages: Int) {} }, offline = storage)
    private val collections = mutableListOf<TeamCollection>()
    private suspend fun activate(s: TeamSession = session): TeamCollection {
        sync.activate(s)
        return scope.active.value!!.also { collections += it }
    }
    private suspend fun visible(c: TeamCollection) = scope.project(c, c.database.resolutionDao().snapshot()).pieces.map { it.id }.toSet()
    @After fun cleanup() {
        scope.deactivate()
        collections.distinctBy { it.database }.forEach { it.database.close() }
        val root = File(context.noBackupFilesDir, "team-libraries").canonicalFile
        val target = File(root, teamId(session.server + "/" + session.principal.teamId + "/" + session.principal.accountId)).canonicalFile
        check(target.parentFile == root)
        target.deleteRecursively()
    }
    // Keep test names short: Robolectric includes them in Windows native SQLite paths.
    @Test fun restoreOffline() = runBlocking {
        val a = artifact("0101001")
        val programme = TeamProgram(UUID.randomUUID().toString(), "Synthetic programme", listOf(a.pieceKey))
        catalog = TeamCatalog(1, listOf(a), listOf(programme))
        val original = activate(); sync.sync(session) { _, _ -> }
        val ink = AnnotationScope(original.member, a.piece, a.part, a.version, 0)
        val mark = InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(0.1f, 0.2f), InkPoint(0.2f, 0.3f)))
        AnnotationRepository(original.database, scope).save(ink, AnnotationLayer.PERSONAL, listOf(mark), 0)
        val count = requests
        sync.deactivate(); offline = true
        val remembered = session.copy(offline = true)
        val restored = activate(remembered)
        assertEquals(setOf(a.piece), visible(restored))
        assertEquals(1, sync.sync(remembered) { _, _ -> })
        assertEquals(count, requests)
        assertTrue(ScoreResolver(restored.database.resolutionDao().snapshot()).resolveConcert(restored.member, programme.concert) is DomainResult.Success)
        assertEquals(listOf(mark), AnnotationRepository(restored.database, scope).load(ink).personal)
        val ciphertext = File(restored.directory.parentFile, "authorization.enc").readText(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(a.title)); assertFalse(ciphertext.contains(session.token))
    }
    @Test fun revokeBeforeRestart() = runBlocking {
        val a = artifact("0101001"); val b = artifact("0101002"); val c = artifact("0101003")
        catalog = TeamCatalog(1, listOf(a, b))
        val original = activate(); sync.sync(session) { _, _ -> }
        val bFile = File(original.database.resolutionDao().versions().single { it.id == b.version }.managedFilePath)
        catalog = TeamCatalog(2, listOf(a, c)); failDownload = true
        assertTrue(runCatching { sync.sync(session) { _, _ -> } }.isFailure)
        assertEquals(setOf(a.piece), visible(original))
        sync.deactivate(); offline = true
        val restored = activate(session.copy(offline = true))
        assertEquals(setOf(a.piece), visible(restored))
        assertTrue(ScoreResolver(restored.database.resolutionDao().snapshot()).resolvePart(restored.member, b.piece, b.part) is DomainResult.Failure)
        assertTrue(bFile.isFile)
    }
    @Test fun invalidCache() = runBlocking {
        val a = artifact("0101001"); val b = artifact("0101002")
        catalog = TeamCatalog(1, listOf(a,b), listOf(TeamProgram(UUID.randomUUID().toString(), "Complete programme", listOf(a.pieceKey,b.pieceKey))))
        val original = activate(); sync.sync(session) { _, _ -> }
        val versions = original.database.resolutionDao().versions()
        File(versions.single { it.id == a.version }.managedFilePath).writeText("corrupt")
        assertTrue(File(versions.single { it.id == b.version }.managedFilePath).delete())
        sync.deactivate(); offline = true
        val restored = activate(session.copy(offline = true))
        assertTrue(visible(restored).isEmpty())
        assertTrue(restored.database.resolutionDao().concerts().all { it.isArchived })
        offline = false
        assertEquals(2, sync.sync(session) { _, _ -> })
        assertEquals(setOf(a.piece,b.piece), visible(restored))
    }
    @Test fun credentialBinding() = runBlocking {
        val a = artifact("0101001"); catalog = TeamCatalog(1, listOf(a))
        activate(); sync.sync(session) { _, _ -> }
        assertTrue(runCatching { activate(session.copy(token = "x".repeat(43), offline = true)) }.isFailure)
        assertNull(scope.active.value)
        activate(); sync.sync(session) { _, _ -> }
        assertTrue(runCatching { activate(session.copy(deviceId = UUID.randomUUID().toString(), offline = true)) }.isFailure)
        assertNull(scope.active.value)
        assertTrue(runCatching { activate(session.copy(verifiedAt = 0, offline = true)) }.isFailure)
    }
    @Test fun invalidCheckpoint() = runBlocking {
        val a = artifact("0101001"); catalog = TeamCatalog(1, listOf(a))
        val original = activate(); sync.sync(session) { _, _ -> }
        now = session.expiresAt
        assertTrue(runCatching { activate(session.copy(offline = true)) }.isFailure)
        now = session.verifiedAt
        activate(); sync.sync(session) { _, _ -> }
        val file = File(original.directory.parentFile, "authorization.enc")
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertTrue(runCatching { activate(session.copy(offline = true)) }.isFailure)
        assertNull(scope.active.value)
        val repaired = activate(); sync.sync(session) { _, _ -> }
        assertEquals(setOf(a.piece), visible(repaired))
    }
}
