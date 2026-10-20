package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import org.json.JSONArray
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
class TeamPublicationQueueTest {
    private fun id() = UUID.randomUUID().toString()
    private fun jobs(count: Int, recipients: Set<String> = setOf(id())) = (1..count).map { n ->
        TeamPublishJob(id(), File("synthetic-$n.pdf"), "$n.pdf", "hash", 100, 1,
            TeamPublishDetails("曲$n", "01 Violin", "0101${n.toString().padStart(3, '0')}", "01 Category", recipients))
    }
    @Test fun splits101AndPreservesEveryJobExactlyOnce() {
        val jobs = jobs(101)
        val chunks = teamPublicationChunks(jobs)
        assertEquals(listOf(50, 50, 1), chunks.map { it.jobs.size })
        assertEquals(jobs, chunks.flatMap { it.jobs })
        assertEquals(3, chunks.map { it.requestId }.distinct().size)
    }
    @Test fun partialProgressDoesNotRegroupOrChangeDurableIds() {
        val jobs = jobs(101)
        val before = teamPublicationChunks(jobs)
        val after = teamPublicationChunks(jobs.mapIndexed { n, job -> when {
            n < 50 -> job.copy(completed = true)
            n == 100 -> job.copy(cancelled = true)
            else -> job
        } })
        assertEquals(before.map { it.requestId }, after.map { it.requestId })
        assertEquals(before.map { c -> c.jobs.map { it.uploadRequest } }, after.map { c -> c.jobs.map { it.uploadRequest } })
    }
    @Test fun oldSingleBatchKeepsItsExistingPublicationId() {
        val jobs = jobs(50)
        assertEquals(jobs.first().publicationRequest, teamPublicationChunks(jobs).single().requestId)
    }
    @Test fun largeRecipientListsSplitByJsonBytes() {
        val jobs = jobs(20, (1..100).map { id() }.toSet())
        val chunks = teamPublicationChunks(jobs)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.jobs.size < 50)
            val body = JSONObject().put("requestId", chunk.requestId).put("assignments", JSONArray(chunk.jobs.map {
                JSONObject().put("artifactId", it.uploadRequest).put("accountIds", JSONArray(it.details.recipients.sorted()))
            }))
            assertTrue(body.toString().toByteArray(Charsets.UTF_8).size <= 16384)
        }
        assertEquals(jobs, chunks.flatMap { it.jobs })
    }
    @Test fun real101PrivateUploadPartialCancelRetryAndLaterExplicitDistribution() = kotlinx.coroutines.runBlocking {
        val source = listOf(File("../team-service/sync_interop_fixture.py"), File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val root = Files.createTempDirectory("queue-http-").toFile()
        val process = ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python", source.path, root.path)
            .redirectOutput(File(root, "out.log")).redirectError(File(root, "err.log")).start()
        try {
            val ready = File(root, "ready-port")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(ready.exists()) { File(root, "err.log").readText().take(1000) }
            val server = "http://127.0.0.1:${ready.readText().trim()}"
            val device = id(); val api = TeamAccountApi()
            val admin = api.login(server, device, "Queue test", "director", "synthetic rehearsal passphrase")
            val invitation = api.request(server, "/v1/admin/invitations", device, admin, JSONObject()).getString("invitation")
            val member = api.login(server, device, "Member test", "queue-member", "synthetic member passphrase", "Member", invitation, true)
            var publications = 0
            val gateway = object : TeamAccountGateway {
                override val server = server; override val device = device
                override fun restore() = admin
                override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean) = admin
                override fun clear() {}
                override fun request(session: TeamSession, path: String, body: JSONObject?): JSONObject {
                    body?.let { assertTrue(it.toString().toByteArray(Charsets.UTF_8).size <= 16384) }
                    if (path == "/v1/admin/publications") assertTrue(body!!.getJSONArray("assignments").length() <= 50)
                    if (path == "/v1/admin/publication-cancellations") assertTrue(body!!.getJSONArray("uploadRequests").length() <= 50)
                    val reply = api.request(server, path, device, session, body)
                    if (path == "/v1/admin/publications" && ++publications == 2) throw IOException("synthetic second receipt lost")
                    return reply
                }
            }
            val client = TeamScoreClient(gateway)
            val original = client.catalog(admin).artifacts.single()
            val pdf = File(root, "original.pdf")
            client.download(admin, original, pdf)
            val key = teamId(server + "/" + admin.principal.teamId + "/" + admin.principal.accountId)
            val journal = TeamPublishJournal(File(root, "cache"))
            val jobs = (1..101).map { n ->
                val file = File(journal.directory(key), "${id()}.pdf").also { pdf.copyTo(it) }
                TeamPublishJob(key, file, "$n.pdf", original.hash, file.length(), original.pages,
                    TeamPublishDetails("队列曲$n", "01 Violin", "0101${(n + 1).toString().padStart(3, '0')}", "01 Category", setOf(admin.principal.accountId)))
            }
            val publisher = TeamPublisher(gateway)
            assertTrue(runCatching { publisher.validateBatch(admin, jobs.dropLast(1) + jobs.last().copy(details = jobs.first().details)) }.isFailure)
            assertEquals(0, publications)
            publisher.checkNewBatch(admin, jobs)
            var state = jobs
            journal.saveBatch(state)
            var sent = 0L
            assertTrue(runCatching { publisher.publishBatch(admin, state, committed = { state = it; journal.saveBatch(it) }) { n, _ -> sent = n } }
                .exceptionOrNull() is IOException)
            assertEquals(pdf.length() * 100, sent)
            assertEquals(50, state.count { it.completed })
            assertEquals(state, journal.readBatch(key))
            assertEquals(101, client.catalog(admin).artifacts.size) // original + 100 committed, one not attempted
            assertTrue(client.catalog(member).artifacts.isEmpty())
            assertFalse(publisher.cancel(admin, state, cancelled = { state = it; journal.saveBatch(it) }))
            assertEquals(1, state.count { it.cancelled })
            assertEquals(50, state.count { !it.finished })
            val first = client.catalog(admin).artifacts.first { it.number == "0101002" }
            api.request(server, "/v1/admin/grants", device, admin, JSONObject().put("artifactId", first.id)
                .put("accountId", admin.principal.accountId).put("allowed", false))
            state = TeamPublishJournal(File(root, "cache")).readBatch(key)
            publisher.publishBatch(admin, state, committed = { state = it; journal.saveBatch(it) }) { _, _ -> fail("Completed content must not be sent again") }
            assertEquals(100, state.count { it.completed }); assertEquals(1, state.count { it.cancelled })
            assertTrue(state.all { it.finished })
            assertEquals(100, client.catalog(admin).artifacts.size) // prior explicit revocation was not re-granted
            assertTrue(client.catalog(member).artifacts.isEmpty())
            api.request(server, "/v1/admin/grants", device, admin, JSONObject().put("artifactId", first.id)
                .put("accountId", member.principal.accountId).put("allowed", true))
            assertEquals(first.id, client.catalog(member).artifacts.single().id)
            val cancelled = jobs.last()
            assertTrue(runCatching { publisher.publish(admin, cancelled) { _, _ -> } }.exceptionOrNull() is AccountRejected)
            assertEquals(state, journal.readBatch(key))
            journal.forgetBatch(state)
            assertTrue(journal.readBatch(key).isEmpty())
            assertTrue(pdf.isFile && TeamLibrarySync.hash(pdf) == original.hash)
        } finally {
            process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
            check(root.canonicalFile.parentFile == File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
            root.deleteRecursively()
        }
    }
}
