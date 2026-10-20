package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TeamPublishQueueViewModelTest {
    private fun id() = UUID.randomUUID().toString()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = TeamLibraryScope(context)
    private val principal = TeamPrincipal(id(), id(), id(), "Synthetic", "admin", "Admin", "admin", true)
    private val session = TeamSession("https://example.invalid", "synthetic-token", System.currentTimeMillis()/1000 + 3600, principal)
    private val collection by lazy { scope.activate(session) }
    private val journal = TeamPublishJournal(File(context.noBackupFilesDir, "team-publishing"))
    private val jobs by lazy { (1..51).map { n ->
        val file = File(journal.directory(collection.key), "${id()}.pdf").apply { writeText("cached synthetic PDF $n") }
        TeamPublishJob(collection.key, file, "$n.pdf", TeamLibrarySync.hash(file), file.length(), 1,
            TeamPublishDetails("曲$n", "01 Violin", "0101${n.toString().padStart(3, '0')}", "01 Category", setOf(principal.accountId)))
    } }
    private var loseSecond = false
    private val requests = mutableListOf<Pair<String, JSONObject?>>()
    private val receipts = mutableMapOf<String, JSONObject>()
    private val gateway = object : TeamAccountGateway {
        override val server = session.server; override val device = id()
        override fun restore() = session
        override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean) = session
        override fun clear() {}
        override fun request(session: TeamSession, path: String, body: JSONObject?): JSONObject {
            requests += path to body
            return when(path) {
                "/v1/admin/uploads" -> JSONObject().put("uploadId", body!!.getString("requestId")).put("complete", true)
                "/v1/admin/artifacts" -> JSONObject().put("artifacts", JSONArray())
                "/v1/admin/publication-cancellations" -> JSONObject().put("cancelled", true).put("published", false)
                "/v1/admin/publications" -> {
                    val request = body!!.getString("requestId")
                    val reply = receipts.getOrPut(request) { JSONObject().put("ok", true).put("publicationId", request)
                        .put("artifactIds", JSONArray((0 until body.getJSONArray("assignments").length()).map {
                            body.getJSONArray("assignments").getJSONObject(it).getString("artifactId") })) }
                    if (loseSecond && receipts.size == 2) { loseSecond = false; throw IOException("synthetic second receipt lost") }
                    reply
                }
                else -> error("Unexpected request $path")
            }
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() {
        models.clear(); scope.deactivate(); collection.database.close()
        val directory = journal.directory(collection.key)
        check(directory.canonicalFile.parentFile == File(context.noBackupFilesDir, "team-publishing").canonicalFile)
        directory.deleteRecursively()
        Dispatchers.resetMain()
    }
    private fun model() = TeamPublishViewModel(context, scope, TeamPublisher(gateway)).also { models.put("upload", it) }

    @Test fun restoresFirstPendingJobAfterCompletedLeadingBatch() = runTest(dispatcher) {
        val saved = jobs.mapIndexed { n, job -> if(n < 50) job.copy(completed = true) else job }
        journal.saveBatch(saved)
        val vm = model()
        val state = vm.state.first { it.batch.size == 51 }
        assertEquals(jobs.last().uploadRequest, state.job!!.uploadRequest)
        assertFalse(state.job.finished)
        assertEquals(saved, state.batch)
        assertTrue(state.message.contains("已确认入库 50"))
        assertTrue(requests.isEmpty())
    }
    @Test fun resumedPublishOnlyConfirmsRemainingChunkAndKeepsOldProgress() = runTest(dispatcher) {
        journal.saveBatch(jobs.mapIndexed { n, job -> if(n < 50) job.copy(completed = true) else job })
        val vm = model(); vm.state.first { it.batch.size == 51 }
        vm.publish(emptyList())
        val state = vm.state.first { !it.busy && it.batch.all { row -> row.finished } }
        assertEquals(51, state.batch.count { it.completed })
        assertEquals(1, requests.count { it.first == "/v1/admin/uploads" })
        assertEquals(1, requests.count { it.first == "/v1/admin/publications" })
        assertEquals(state.batch, journal.readBatch(collection.key))
        assertTrue(state.message.contains("管理员总库"))
    }
    @Test fun partialFailurePersistsStatusAndRetryDoesNotRepublishLeadingBatch() = runTest(dispatcher) {
        journal.saveBatch(jobs); loseSecond = true
        val vm = model(); vm.state.first { it.batch.size == 51 }
        vm.publish(emptyList())
        val failed = vm.state.first { !it.busy && it.message.contains("已确认入库 50") }
        assertEquals(50, failed.batch.count { it.completed })
        assertFalse(failed.job!!.finished)
        assertEquals(failed.batch, journal.readBatch(collection.key))
        vm.publish(emptyList())
        val done = vm.state.first { !it.busy && it.batch.all { row -> row.finished } }
        assertEquals(51, done.batch.count { it.completed })
        assertEquals(2, receipts.size)
        assertEquals(listOf(50, 1, 1), requests.filter { it.first == "/v1/admin/publications" }
            .map { it.second!!.getJSONArray("assignments").length() })
    }
    @Test fun cancelRemainingKeepsCompletedServerPartsAndClearsOnlyPrivateQueueCache() = runTest(dispatcher) {
        journal.saveBatch(jobs.mapIndexed { n, job -> if(n < 50) job.copy(completed = true) else job })
        val vm = model(); vm.state.first { it.batch.size == 51 }
        vm.cancel()
        val state = vm.state.first { !it.busy && it.job == null && it.message.contains("已取消 1") }
        assertTrue(state.message.contains("已确认入库 50"))
        assertEquals(1, requests.size)
        assertEquals(1, requests.single().second!!.getJSONArray("uploadRequests").length())
        assertTrue(journal.readBatch(collection.key).isEmpty())
        assertTrue(jobs.none { it.file.exists() })
    }
}
