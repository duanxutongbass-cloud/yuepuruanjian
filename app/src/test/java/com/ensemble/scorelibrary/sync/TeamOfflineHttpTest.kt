package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class TeamOfflineHttpTest {
    @Test fun restartRevoke() = runBlocking {
        val source = listOf(File("../team-service/sync_interop_fixture.py"), File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val directory = Files.createTempDirectory("offline-http-").toFile()
        val python = System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python"
        val process = ProcessBuilder(python, source.path, directory.path)
            .redirectOutput(File(directory, "stdout.log")).redirectError(File(directory, "stderr.log"))
            .apply { environment()["PYTHONDONTWRITEBYTECODE"] = "1" }.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databases = mutableListOf<com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase>()
        var accountDirectory: File? = null
        try {
            val ready = File(directory, "ready-port")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(ready.exists() && process.isAlive) { "Fixture failed: " + File(directory, "stderr.log").readText().take(1000) }
            val server = "http://127.0.0.1:${ready.readText().trim().toInt()}"
            val api = TeamAccountApi(); val device = UUID.randomUUID().toString()
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val vault = AccountVault(File(directory, "session.enc")) { key }
            val memory = VerifiedSessionMemory(vault, server, device)
            var session = memory.confirm(api.login(server, device, "Synthetic offline tablet", "director", "synthetic rehearsal passphrase")).also(memory::save)
            var offline = false; var networkCalls = 0
            val gateway = object : TeamAccountGateway {
                override val server = server
                override val device = device
                override fun restore() = memory.read()
                override fun clear() { vault.clear() }
                override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean) = error("No second login")
                override fun request(session: TeamSession, path: String, body: JSONObject?): JSONObject {
                    networkCalls++
                    if (offline) throw IOException("disconnected")
                    return api.request(server, path, device, session, body)
                }
            }
            val verifier = object : TeamPdfVerifier {
                override fun verify(file: File, pages: Int) {
                    val validation = ProcessBuilder(python, "-c", "from pypdf import PdfReader; import sys; p=PdfReader(sys.argv[1],strict=True); assert not p.is_encrypted; assert len(p.pages)==int(sys.argv[2])", file.path, pages.toString())
                        .redirectOutput(File(directory, "validation.log")).redirectError(File(directory, "validation-error.log")).start()
                    try { check(validation.waitFor(20, TimeUnit.SECONDS) && validation.exitValue() == 0) }
                    finally { if (validation.isAlive) validation.destroyForcibly() }
                }
            }
            val storage = TeamOfflineStore(context.noBackupFilesDir, { key })
            fun connection(): Pair<TeamLibraryScope, TeamLibrarySync> {
                val scope = TeamLibraryScope(context)
                return scope to TeamLibrarySync(scope, TeamScoreClient(gateway), verifier, offline = storage)
            }
            val (firstScope, firstSync) = connection()
            firstSync.activate(session); firstSync.sync(session) { _, _ -> }
            val first = firstScope.active.value!!.also { databases += it.database; accountDirectory = it.directory.parentFile }
            val artifact = TeamScoreClient(gateway).catalog(session).artifacts.single()
            firstSync.deactivate(); first.database.close()
            offline = true
            session = memory.verify(memory.read()!!) { gateway.request(it, "/v1/me") }
            assertTrue(session.offline)
            val calls = networkCalls
            val (secondScope, secondSync) = connection()
            secondSync.activate(session)
            val second = secondScope.active.value!!.also { databases += it.database }
            assertEquals(1, secondSync.sync(session) { _, _ -> })
            assertEquals(calls, networkCalls)
            assertTrue(ScoreResolver(second.database.resolutionDao().snapshot()).resolvePiece(second.member, artifact.piece) is DomainResult.Success)
            // Admin withdraws this PDF while the client is offline; reconnect checks identity first.
            api.request(server, "/v1/admin/grants", device, session.copy(offline = false), JSONObject()
                .put("accountId", session.principal.accountId).put("artifactId", artifact.id).put("allowed", false))
            offline = false
            session = memory.verify(session) { gateway.request(it, "/v1/me") }
            assertFalse(session.offline)
            assertEquals(session, memory.read())
            assertEquals(0, secondSync.sync(session) { _, _ -> })
            secondSync.deactivate(); second.database.close()
            val (thirdScope, thirdSync) = connection()
            offline = true
            session = memory.verify(memory.read()!!) { gateway.request(it, "/v1/me") }
            thirdSync.activate(session)
            val third = thirdScope.active.value!!.also { databases += it.database }
            assertTrue(thirdScope.project(third, third.database.resolutionDao().snapshot()).pieces.isEmpty())
            assertEquals(0, thirdSync.sync(session) { _, _ -> })
            thirdSync.deactivate()
        } finally {
            databases.distinct().forEach { if (it.isOpen) it.close() }
            accountDirectory?.canonicalFile?.let { target ->
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
