package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.data.RoomScoreRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TeamScoreInteropTest {
    @Test fun publishDownloadRevoke() = runBlocking {
        val source = listOf(File("../team-service/sync_interop_fixture.py"), File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val directory = Files.createTempDirectory("team-score-interop-").toFile()
        val python = System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python"
        val process = ProcessBuilder(python, source.path, directory.path)
            .redirectOutput(File(directory, "stdout.log")).redirectError(File(directory, "stderr.log"))
            .apply { environment()["PYTHONDONTWRITEBYTECODE"] = "1" }.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = TeamLibraryScope(context)
        val local = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        var collection: TeamCollection? = null
        try {
            val ready = File(directory, "ready-port")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(ready.exists()) { "Synthetic publisher fixture did not start: " + File(directory, "stderr.log").readText().take(1500) }
            val port=ready.readText().trim().also { check(it.toIntOrNull()?.let { value -> value in 1..65535 } == true && process.isAlive) { "Invalid fixture readiness" } }
            val server = "http://127.0.0.1:" + port
            val api = TeamAccountApi()
            val device = UUID.randomUUID().toString()
            val session = api.login(server, device, "Synthetic pad", "director", "synthetic rehearsal passphrase")
            val gateway = object : TeamAccountGateway {
                override val server = server
                override val device = device
                override fun restore() = session
                override fun clear() {}
                override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean) = session
                override fun request(session: TeamSession, path: String, body: JSONObject?) = api.request(server, path, device, session, body)
            }
            val client = TeamScoreClient(gateway)
            // Desktop Robolectric has no working native PdfRenderer. Validate real downloaded
            // bytes with an independent PDF parser here; native rendering is an AndroidTest.
            val verifier=object:TeamPdfVerifier {
                override fun verify(file:File,pages:Int) {
                    val script="from pypdf import PdfReader; import sys; p=PdfReader(sys.argv[1],strict=True); assert not p.is_encrypted; assert len(p.pages)==int(sys.argv[2]); assert all(float(x.mediabox.width)>0 and float(x.mediabox.height)>0 for x in p.pages)"
                    val validation=ProcessBuilder(python,"-c",script,file.path,pages.toString())
                        .redirectOutput(File(directory,"pdf-validation.log")).redirectError(File(directory,"pdf-validation-error.log")).start()
                    try { check(validation.waitFor(20,TimeUnit.SECONDS) && validation.exitValue()==0){"Independent PDF parsing failed"} }
                    finally { if(validation.isAlive) validation.destroyForcibly() }
                }
            }
            val sync = TeamLibrarySync(scope, client, verifier)
            sync.activate(session)
            val current = scope.active.value!!; collection = current
            assertEquals(1, sync.sync(session) { _, _ -> })
            val catalog = client.catalog(session)
            val a = catalog.artifacts.single()
            val repository = RoomScoreRepository(local, scope)
            val score = (repository.resolvePiece(current.member, a.piece) as DomainResult.Success).value
            assertEquals("001", current.database.resolutionDao().pieces().single().organizerSequence)
            assertEquals(a.hash, TeamLibrarySync.hash(File(score.segments.single().managedFilePath)))
            assertTrue(local.resolutionDao().pieces().isEmpty())
            val programId = UUID.randomUUID().toString()
            api.request(server, "/v1/admin/programs", device, session, JSONObject().put("id", programId).put("title", "合成节目单")
                .put("pieceKeys", org.json.JSONArray().put(a.pieceKey)).put("accountIds", JSONObject.NULL).put("archived", false).put("expectedRevision", 0))
            assertEquals(1, sync.sync(session) { _, _ -> })
            val program = client.catalog(session).programs.single()
            val resolved = ScoreResolver(current.database.resolutionDao().snapshot()).resolveConcert(current.member, program.concert)
            assertTrue(resolved is DomainResult.Success)
            assertEquals(a.piece, (resolved as DomainResult.Success).value.items.single().pieceId)
            api.request(server, "/v1/me/backup-consent", device, session, JSONObject().put("accepted", true))
            current.session = session.copy(principal = session.principal.copy(backupConsent = true))
            val cloud = TeamInkSync(scope, TeamInkClient(gateway)).also { it.automatic = false }
            val ink = com.ensemble.scorelibrary.annotation.AnnotationRepository(local, scope, cloud)
            val inkScope = com.ensemble.scorelibrary.annotation.AnnotationScope(current.member, a.piece, a.part, a.version, 0)
            val mark = com.ensemble.scorelibrary.annotation.InkMark(kind = com.ensemble.scorelibrary.annotation.MarkKind.PEN,
                points = List(2500) { com.ensemble.scorelibrary.annotation.InkPoint((it % 100) / 100f, (it / 100) / 100f) })
            ink.save(inkScope, com.ensemble.scorelibrary.annotation.AnnotationLayer.PERSONAL, listOf(mark), 0)
            cloud.sync(current)
            val personal = api.request(server, "/v1/admin/annotation-backups", device, session, JSONObject()
                .put("accountId", session.principal.accountId).put("artifactId", a.id).put("pageIndex", 0))
            assertEquals(listOf(mark), com.ensemble.scorelibrary.annotation.InkCodec.decode(personal.getJSONArray("marks").toString()))
            ink.save(inkScope, com.ensemble.scorelibrary.annotation.AnnotationLayer.SHARED, listOf(mark), 0)
            cloud.sync(current); ink.publish(inkScope)
            assertEquals(listOf(mark), ink.load(inkScope).shared)
            val request = JSONObject().put("protocolVersion", 1).put("requestId", UUID.randomUUID().toString())
                .put("artifactHash", a.hash).put("baseRevision", 0).put("marks", org.json.JSONArray())
            val conflict = TeamInkClient(gateway).request(session, "/v1/annotations/${a.id}/0/personal", request)
            assertEquals(409, conflict.status)
            assertEquals(listOf(mark), com.ensemble.scorelibrary.annotation.InkCodec.decode(conflict.body.getJSONObject("current").getJSONArray("marks").toString()))
            api.request(server, "/v1/admin/grants", device, session, JSONObject().put("accountId", session.principal.accountId)
                .put("artifactId", a.id).put("allowed", false))
            assertEquals(0, sync.sync(session) { _, _ -> })
            assertTrue(repository.resolvePiece(current.member, a.piece) is DomainResult.Failure)
            assertTrue(scope.project(current, current.database.resolutionDao().snapshot()).pieces.isEmpty())
            assertTrue(current.database.resolutionDao().concerts().all { it.isArchived })
            val backup = JSONObject(cloud.personalBackup(current))
            assertEquals(1, backup.getJSONArray("pages").length())
            assertEquals(1, backup.getJSONArray("localSavedPages").length())
            assertEquals(404, (runCatching { client.download(session, a, File(current.directory, "denied.pending")) }.exceptionOrNull() as AccountRejected).status)
            val updater=com.ensemble.scorelibrary.update.AppUpdateClient(server)
            val release=com.ensemble.scorelibrary.update.AppRelease.parse(updater.check(),"com.ensemble.scorelibrary",14)!!
            val updateFile=File(directory,"download.apk")
            updater.download(release,updateFile)
            assertEquals(release.hash,TeamLibrarySync.hash(updateFile))
            assertEquals(release.size,updateFile.length())
        } finally {
            scope.deactivate(); local.close()
            collection?.let {
                it.database.close()
                val target = it.directory.parentFile!!.canonicalFile
                check(target.parentFile == File(context.noBackupFilesDir, "team-libraries").canonicalFile)
                target.deleteRecursively()
            }
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
            check(directory.canonicalFile.parentFile == File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
            directory.deleteRecursively()
        }
    }
}
