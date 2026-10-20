package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
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

@RunWith(RobolectricTestRunner::class)
class TeamPublisherTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun journalIsolated() {
        val root = Files.createTempDirectory("tp-").toFile()
        try {
            val journal = TeamPublishJournal(root)
            val key = id(); val other = id()
            val file = File(journal.directory(key), "${id()}.pdf").apply { writeText("synthetic") }
            val job = TeamPublishJob(key, file, "合成.pdf", TeamLibrarySync.hash(file), file.length(), 1,
                TeamPublishDetails("合成", "01 Violin", "0101002", "01 Category", setOf(id())))
            journal.save(job)
            assertEquals(job, journal.read(key)); assertNull(journal.read(other))
            assertFalse(File(journal.directory(key), "request.json").readText().contains("token"))
            assertTrue(runCatching { journal.forget(job) }.isFailure)
            assertTrue(runCatching { journal.save(job.copy(accountKey = other)) }.isFailure)
            journal.forget(job.copy(completed = true)); assertNull(journal.read(key)); assertFalse(file.exists())
        } finally { check(root.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile); root.deleteRecursively() }
    }
    @Test fun rejectInvalidMetadata() {
        val details = TeamPublishDetails("Test", "Violin", "0101001", "Category", setOf(id()))
        details.validate()
        listOf(details.copy(number = "0101000"), details.copy(number = "0901001"), details.copy(number = "001"),
            details.copy(title = ""), details.copy(recipients = emptySet())).forEach { assertTrue(runCatching { it.validate() }.isFailure) }
    }
    @Test fun realUploadRetryAndGrants() = kotlinx.coroutines.runBlocking {
        val source = listOf(File("../team-service/sync_interop_fixture.py"), File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val root = Files.createTempDirectory("tp-http-").toFile()
        val process = ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python", source.path, root.path)
            .redirectOutput(File(root, "out.log")).redirectError(File(root, "err.log")).start()
        try {
            val ready = File(root, "ready-port")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(ready.exists()) { File(root, "err.log").readText().take(1000) }
            val server = "http://127.0.0.1:${ready.readText().trim()}"
            val device = id(); val api = TeamAccountApi()
            val admin = api.login(server, device, "Upload test", "director", "synthetic rehearsal passphrase")
            fun member(name: String): TeamSession {
                val invitation = api.request(server, "/v1/admin/invitations", device, admin, JSONObject()).getString("invitation")
                return api.login(server, device, "Member test", name, "synthetic member passphrase", name, invitation, true)
            }
            val recipient = member("recipient"); val excluded = member("excluded")
            var loseAck = true
            val gateway = object : TeamAccountGateway {
                override val server = server; override val device = device
                override fun restore() = admin
                override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean) = admin
                override fun clear() {}
                override fun request(session: TeamSession, path: String, body: JSONObject?): JSONObject {
                    val reply = api.request(server, path, device, session, body)
                    if (path == "/v1/admin/publications" && loseAck) { loseAck = false; throw IOException("synthetic lost publication acknowledgement") }
                    return reply
                }
            }
            val client = TeamScoreClient(gateway)
            val original = client.catalog(admin).artifacts.single()
            val file = File(root, "${id()}.pdf")
            kotlinx.coroutines.runBlocking { client.download(admin, original, file) }
            val key = teamId(server + "/" + admin.principal.teamId + "/" + admin.principal.accountId)
            val details = TeamPublishDetails("手机上传合成曲", "01 第一小提琴", "0101002", "01 合成分类",
                setOf(admin.principal.accountId, recipient.principal.accountId))
            val publisher = TeamPublisher(gateway)
            val job = TeamPublishJob(key, file, "0101002手机上传合成曲.pdf", TeamLibrarySync.hash(file), file.length(), 1, details)
            val second = job.copy(file = File(root, "${id()}.pdf").apply { file.copyTo(this) }, name = "0201002手机上传合成曲.pdf",
                details = details.copy(number = "0201002", part = "02 Cello", recipients = setOf(admin.principal.accountId)), uploadRequest = id())
            var sent = 0L
            assertTrue(runCatching { publisher.publishBatch(admin, listOf(job, second)) { n, _ -> sent = n } }.exceptionOrNull() is IOException)
            assertEquals(file.length() * 2, sent)
            // Recover the same durable IDs after process loss, not a new publication.
            val journal = TeamPublishJournal(File(root, "journal"))
            val owned = File(journal.directory(key), file.name).apply { file.copyTo(this) }
            val ownedSecond = File(journal.directory(key), second.file.name).apply { second.file.copyTo(this) }
            journal.saveBatch(listOf(job.copy(file = owned), second.copy(file = ownedSecond)))
            publisher.publishBatch(admin, journal.readBatch(key)) { _, _ -> fail("Completed upload should not resend bytes") }
            assertEquals(3, client.catalog(admin).artifacts.size)
            val received = client.catalog(recipient).artifacts.single()
            assertEquals("0101002", received.number); assertEquals(details.title, received.title)
            assertEquals(job.hash, received.hash); assertTrue(client.catalog(excluded).artifacts.isEmpty())
            val downloaded = File(root, "received.pdf")
            kotlinx.coroutines.runBlocking { client.download(recipient, received, downloaded) }
            assertEquals(job.hash, TeamLibrarySync.hash(downloaded))
            assertTrue(runCatching { publisher.publish(recipient, job) { _, _ -> } }.isFailure)
            assertTrue(runCatching { publisher.publish(admin, job.copy(details = details.copy(title = "错误同号"), uploadRequest = id(), publicationRequest = id())) { _, _ -> } }.isFailure)
            // A later batch adds six other instruments to the exact same piece number, without
            // replacing the two voices already published in the previous durable operation.
            val supplements=(3..8).map { instrument -> job.copy(name="supplement-$instrument.pdf",
                details=details.copy(number="0${instrument}01002",part="0$instrument Instrument"),
                uploadRequest=id(),publicationRequest=id()) }
            publisher.checkNewBatch(admin,supplements)
            var supplementBytes=0L
            publisher.publishBatch(admin,supplements) { n,_ -> supplementBytes=n }
            assertEquals(file.length()*6,supplementBytes)
            val after=client.catalog(admin)
            assertEquals(9,after.artifacts.size)
            assertEquals(8,after.artifacts.count { it.number.takeLast(3)=="002" })
            assertEquals(8,publisher.existing(admin).count { it.number.takeLast(3)=="002" })
            val comparable = publisher.existing(admin).single { it.number == "0101002" }
            assertTrue(comparable.artifactId.isNotEmpty())
            val allowedComparison = publisher.comparisonArtifact(admin, comparable)
            assertEquals(job.hash, allowedComparison.hash)
            assertTrue(runCatching { publisher.comparisonArtifact(excluded, comparable) }.isFailure)
            assertTrue(runCatching { publisher.comparisonArtifact(admin, comparable.copy(artifactId = id())) }.isFailure)
            assertTrue(runCatching { publisher.comparisonArtifact(admin, comparable.copy(removed = true)) }.isFailure)
            val comparisonFile = File(root, "comparison.pdf")
            publisher.downloadComparison(admin, allowedComparison, comparisonFile)
            assertEquals(job.hash, TeamLibrarySync.hash(comparisonFile))
            publisher.verifyComparison(admin, allowedComparison)
            assertTrue(runCatching { publisher.verifyComparison(admin, allowedComparison.copy(hash = "0".repeat(64))) }.isFailure)

            val conflict = runCatching { publisher.checkNewBatch(admin,supplements.map { it.copy(uploadRequest=id(),publicationRequest=id()) }) }.exceptionOrNull()
            assertTrue(conflict is UploadCatalogConflict)
            assertEquals(8, (conflict as UploadCatalogConflict).latest.count { it.number.takeLast(3) == "002" })
            assertTrue(conflict.latest.any { it.artifactId == allowedComparison.id })
            val operation=com.ensemble.scorelibrary.diagnostics.OperationRecord(operation=id(),
                event=com.ensemble.scorelibrary.diagnostics.TestEvent.UPLOAD_PUBLISH,count=6)
            val logBody=JSONObject().put("events",org.json.JSONArray().put(operation.json()))
            repeat(2) {
                val receipt=gateway.request(admin,"/v1/test-operations",logBody)
                assertEquals(operation.id,receipt.getJSONArray("accepted").getString(0))
            }
            val logs=gateway.request(admin,"/v1/admin/test-operations/query",JSONObject().put("after",0).put("limit",20)).getJSONArray("events")
            assertEquals(1,logs.length())
            assertEquals(operation.id,logs.getJSONObject(0).getString("id"))
            assertEquals(admin.principal.accountId,logs.getJSONObject(0).getString("accountId"))
            assertTrue(runCatching { gateway.request(recipient,"/v1/admin/test-operations/query",JSONObject().put("after",0).put("limit",20)) }
                .exceptionOrNull() is AccountRejected)
            owned.appendText("changed")
            assertTrue(runCatching { publisher.publishBatch(admin, journal.readBatch(key)) { _, _ -> } }.isFailure)
            assertEquals(9, client.catalog(admin).artifacts.size)
            assertEquals(original.hash, TeamLibrarySync.hash(file))
        } finally {
            process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
            check(root.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile); root.deleteRecursively()
        }
    }
}
