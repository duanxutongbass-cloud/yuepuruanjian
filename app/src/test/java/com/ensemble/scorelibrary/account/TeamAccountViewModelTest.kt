package com.ensemble.scorelibrary.account

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import com.ensemble.scorelibrary.sync.TeamDownloadState
import com.ensemble.scorelibrary.sync.TeamDownload
import com.ensemble.scorelibrary.sync.teamId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TeamAccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val principal = TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "Synthetic ensemble", "violinist", "Violinist", "member", true)
    private val session = TeamSession(TEAM_SERVICE_URL, "synthetic-session-never-displayed", Long.MAX_VALUE, principal)
    private val gateway = object : TeamAccountGateway {
        override val server = session.server
        override val device = UUID.randomUUID().toString()
        var cleared = 0
        var restores: Exception? = null
        var networkFailure = false
        var unauthorized = false
        var loginServer: String? = null
        var remembered = session
        var verificationFailure: Exception? = null
        var verifications = 0
        val requests = mutableListOf<String>()
        override fun restore(): TeamSession { restores?.let { throw it }; return remembered }
        override fun verify(session: TeamSession): TeamSession {
            verifications++; verificationFailure?.let { throw it }
            return session.copy(offline = networkFailure)
        }
        override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean): TeamSession {
            loginServer = server
            return session
        }
        override fun clear() { cleared++ }
        override fun request(session: TeamSession, path: String, body: JSONObject?): JSONObject {
            requests += path
            if (networkFailure) throw IOException("offline")
            if (unauthorized) throw AccountRejected(401, "session_invalid")
            return when (path) {
                "/v1/me/devices" -> JSONObject().put("devices", JSONArray().put(JSONObject().put("id", device).put("name", "Tablet").put("current", true).put("revoked", false)))
                else -> JSONObject().put("ok", true)
            }
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { viewModels.clear(); Dispatchers.resetMain() }
    private fun model(scores: com.ensemble.scorelibrary.sync.TeamScoreSync? = null) = TeamAccountViewModel(gateway,scores).also { viewModels.put("account", it) }
    private class ScoreSync : com.ensemble.scorelibrary.sync.TeamScoreSync {
        override val downloads=MutableStateFlow(TeamDownloadState())
        var pauses=0;var resumes=0;var barrier:CompletableDeferred<Unit>?=null
        override var offlineDeadline: Long? = null
        var activations=0;var synchronizations=0;var deactivations=0
        var failure:Exception?=null
        var activationFailure:Exception?=null
        override suspend fun activate(session:TeamSession):String {
            activations++;activationFailure?.let { throw it };downloads.value=TeamDownloadState(teamId("${session.server}/${session.principal.teamId}/${session.principal.accountId}"));return "private-member"
        }
        override suspend fun sync(session:TeamSession,progress:(Int,Int)->Unit):Int {
            synchronizations++;barrier?.await();failure?.let{throw it};progress(1,1);return 1
        }
        override fun deactivate(){deactivations++;downloads.value=TeamDownloadState()}
        override fun pauseDownloads(expectedKey:String?) { check(expectedKey==downloads.value.key);pauses++;downloads.value=downloads.value.copy(paused=true) }
        override fun resumeDownloads(expectedKey:String?) { check(expectedKey==downloads.value.key);resumes++;downloads.value=downloads.value.copy(paused=false) }
    }
    @Test fun loginAutomaticallyReceivesScores() = runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores)
        model.login("admin","synthetic password");model.state.first{!it.busy}
        assertEquals(1,scores.activations);assertEquals(1,scores.synchronizations)
        assertEquals("private-member",model.state.value.libraryMember)
        assertTrue(model.state.value.syncMessage.contains("已同步 1"))
    }
    @Test fun logoutCanCancelAnInProgressLibrarySync() = runTest(dispatcher) {
        val scores=ScoreSync().apply { barrier=CompletableDeferred() };val model=model(scores)
        model.login("admin","synthetic password")
        model.state.first { it.busy && it.libraryMember!=null }
        model.logout()
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { model.state.first { !it.busy && it.principal==null } }
        }
        assertNull(model.state.value.libraryMember)
        assertTrue(model.state.value.libraryBlocked)
        assertEquals(1,gateway.cleared)
        scores.barrier!!.complete(Unit);runCurrent()
        assertNull(model.state.value.principal)
    }
    @Test fun explicitBackCancelsWaitingSyncAndCannotReactivateOnLateCompletion() = runTest(dispatcher) {
        val waiting = CompletableDeferred<Unit>()
        val scores = ScoreSync().apply { barrier = waiting }
        val model = model(scores)
        model.login("admin", "synthetic password")
        model.state.first { it.busy && it.libraryMember != null }
        model.leaveAccount()
        model.state.first { !it.busy && !it.libraryBlocked }
        assertNull(model.state.value.principal)
        assertNull(model.state.value.libraryMember)
        assertEquals(1, gateway.cleared)
        assertEquals(1, scores.deactivations)
        waiting.complete(Unit); runCurrent()
        assertNull(model.state.value.principal)
        assertNull(model.state.value.libraryMember)
        assertFalse(model.state.value.libraryBlocked)
    }
    @Test fun explicitBackWorksAfterActivationFailureAndOfflineLogout() = runTest(dispatcher) {
        val scores = object : com.ensemble.scorelibrary.sync.TeamScoreSync {
            override suspend fun activate(session: TeamSession): String = throw IOException("unavailable")
            override suspend fun sync(session: TeamSession, progress: (Int, Int) -> Unit) = 0
            override fun deactivate() {}
        }
        val model = model(scores)
        model.login("admin", "synthetic password"); model.state.first { !it.busy }
        assertTrue(model.state.value.libraryBlocked)
        assertNotNull(model.state.value.principal)
        gateway.networkFailure = true
        model.leaveAccount(); model.state.first { !it.busy && !it.libraryBlocked }
        assertNull(model.state.value.principal)
        assertNull(model.state.value.libraryMember)
        assertTrue(model.state.value.message.contains("远端会话"))
    }
    @Test fun verifiedOfflineStartupSkipsNetworkDetailsAndReconnectVerifiesBeforeSync() = runTest(dispatcher) {
        gateway.remembered = session.copy(offline = true, verifiedAt = System.currentTimeMillis() / 1000, deviceId = gateway.device)
        gateway.networkFailure = true
        val scores = ScoreSync(); val model = model(scores)
        model.open(); model.state.first { !it.busy }
        assertTrue(model.state.value.offline)
        assertEquals("private-member", model.state.value.libraryMember)
        assertTrue(model.state.value.syncMessage.contains("离线可读 1"))
        assertTrue(gateway.requests.isEmpty())
        gateway.networkFailure = false
        model.sync(); model.state.first { !it.busy }
        assertEquals(1, gateway.verifications)
        assertFalse(model.state.value.offline)
        assertEquals(2, scores.synchronizations)
    }
    @Test fun reconnectRevocationStopsBeforeAnyScoreTransferAndDropsCollection() = runTest(dispatcher) {
        val scores = ScoreSync(); val model = model(scores)
        model.open(); model.state.first { !it.busy }
        assertEquals(1, scores.synchronizations)
        gateway.verificationFailure = AccountRejected(403, "device_revoked")
        model.sync(); model.state.first { !it.busy }
        assertEquals(1, scores.synchronizations)
        assertEquals(1, scores.deactivations)
        assertNull(model.state.value.principal)
        assertNull(model.state.value.libraryMember)
        assertEquals(1, gateway.cleared)
    }
    @Test fun deadlineClosesReadingWithoutWaitingForNextNetworkRequest() = runTest(dispatcher) {
        gateway.remembered = session.copy(offline = true)
        val scores = ScoreSync().apply { offlineDeadline = System.currentTimeMillis() / 1000 + 2 }
        val model = model(scores)
        model.open(); model.state.first { !it.busy }
        assertEquals("private-member", model.state.value.libraryMember)
        advanceTimeBy(2100); runCurrent()
        assertNull(model.state.value.libraryMember)
        assertEquals(1, scores.deactivations)
        assertTrue(model.state.value.syncMessage.contains("核验期限已到"))
    }
    @Test fun restoreSynchronizesAndOfflineLogoutClosesTeamLibrary() = runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores)
        model.open();model.state.first{!it.busy}
        assertEquals(1,scores.synchronizations)
        gateway.networkFailure=true
        model.logout();model.state.first{!it.busy}
        assertNull(model.state.value.libraryMember);assertEquals(1,scores.deactivations)
    }
    @Test fun failedDownloadKeepsVerifiedAccountAndAllowsRetry() = runTest(dispatcher) {
        val scores=ScoreSync().apply{failure=IOException("offline")};val model=model(scores)
        model.open();model.state.first{!it.busy}
        assertNotNull(model.state.value.principal)
        assertTrue(model.state.value.syncMessage.contains("同步未完成"))
        scores.failure=null
        model.sync();model.state.first{!it.busy}
        assertEquals(1,scores.activations);assertEquals(2,scores.synchronizations)
        assertTrue(model.state.value.syncMessage.contains("已同步 1"))
    }
    @Test fun cleanupActivationFailureKeepsLoginAndExplainsLocalRetry()=runTest(dispatcher) {
        val scores=ScoreSync().apply { activationFailure=com.ensemble.scorelibrary.sync.TeamCleanupFailure(IllegalStateException("private cleanup")) }
        val model=model(scores);model.open();model.state.first { !it.busy }
        assertEquals(principal,model.state.value.principal);assertEquals(0,gateway.cleared)
        assertNull(model.state.value.libraryMember)
        assertTrue(model.state.value.message.contains("本机"));assertTrue(model.state.value.message.contains("清理"))
        assertFalse(model.state.value.message.contains("无法连接"))
        scores.activationFailure=null;model.sync();model.state.first { !it.busy }
        assertEquals("private-member",model.state.value.libraryMember);assertEquals(2,scores.activations)
    }
    @Test fun cleanupSyncFailureBlocksReadingWithoutClearingLoginThenReactivatesOnRetry()=runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores);model.open();model.state.first { !it.busy }
        scores.failure=com.ensemble.scorelibrary.sync.TeamCleanupFailure(IllegalStateException("cleanup failed"))
        model.sync();model.state.first { !it.busy }
        assertEquals(principal,model.state.value.principal);assertEquals(0,gateway.cleared)
        assertNull(model.state.value.libraryMember);assertTrue(model.state.value.libraryBlocked)
        assertTrue(model.state.value.message.contains("本机"));assertTrue(model.state.value.syncMessage.contains("清理"))
        scores.failure=null;model.sync();model.state.first { !it.busy }
        assertEquals("private-member",model.state.value.libraryMember);assertEquals(2,scores.activations)
    }
    @Test fun syncRejectsExpiredIdentityAndClosesCollection() = runTest(dispatcher) {
        val scores=ScoreSync().apply{failure=AccountRejected(401,"session_invalid")};val model=model(scores)
        model.open();model.state.first{!it.busy}
        assertNull(model.state.value.principal);assertNull(model.state.value.libraryMember)
        assertEquals(1,scores.deactivations);assertEquals(1,gateway.cleared)
    }

    @Test fun loginUsesBuiltInServiceWithoutUserSuppliedAddress() = runTest(dispatcher) {
        val model = model()
        model.login("admin", "synthetic password")
        model.state.first { !it.busy }
        assertEquals("https://124355.xyz:18443", gateway.loginServer)
        assertEquals(principal, model.state.value.principal)
        assertEquals(TEAM_SERVICE_URL, model.state.value.server)
    }

    @Test fun offlineStartupDoesNotTreatRememberedCredentialsAsVerifiedIdentity() = runTest(dispatcher) {
        gateway.restores = IOException("offline")
        val model = model(); model.open()
        model.state.first { !it.busy }
        assertNull(model.state.value.principal)
        assertEquals(0, gateway.cleared)
        assertTrue(model.state.value.message.contains("无法连接"))
    }
    @Test fun expiredServerSessionClearsCredentialsAndPreviouslyShownIdentity() = runTest(dispatcher) {
        val model = model(); model.open(); model.state.first { !it.busy }
        assertNotNull(model.state.value.principal)
        gateway.unauthorized = true
        model.refresh(); model.state.first { !it.busy }
        assertNull(model.state.value.principal)
        assertTrue(model.state.value.devices.isEmpty())
        assertEquals(1, gateway.cleared)
    }
    @Test fun offlineLogoutStillClearsLocalSessionAndWarnsAboutPendingRemoteRevocation() = runTest(dispatcher) {
        val model = model(); model.open(); model.state.first { !it.busy }
        gateway.networkFailure = true
        model.logout(); model.state.first { !it.busy }
        assertNull(model.state.value.principal)
        assertEquals(1, gateway.cleared)
        assertTrue(model.state.value.message.contains("远端会话"))
    }
    @Test fun passwordChangeSignsOutAndNeverRequestsAdministratorDataForMember() = runTest(dispatcher) {
        val model = model(); model.open(); model.state.first { !it.busy }
        assertFalse(gateway.requests.any { it.startsWith("/v1/admin/") })
        model.changePassword("old synthetic password", "new synthetic password")
        model.state.first { !it.busy }
        assertNull(model.state.value.principal)
        assertEquals(1, gateway.cleared)
        assertTrue(model.state.value.message.contains("所有设备"))
    }
    @Test fun resumeRechecksRevokedIdentity()=runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores);model.open();model.state.first { !it.busy }
        model.pauseDownloads();scores.downloads.first { it.paused }
        gateway.verificationFailure=AccountRejected(401,"session_invalid")
        model.resumeDownloads();model.state.first { !it.busy }
        assertEquals(0,scores.resumes);assertNull(model.state.value.principal)
    }
    @Test fun resumeWaitsForOnlineVerification()=runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores);model.open();model.state.first { !it.busy }
        model.pauseDownloads();scores.downloads.first { it.paused }
        gateway.networkFailure=true;model.resumeDownloads();model.state.first { !it.busy }
        assertEquals(0,scores.resumes);assertTrue(scores.downloads.value.paused)
        gateway.networkFailure=false;model.resumeDownloads();model.state.first { !it.busy }
        assertEquals(1,scores.resumes);assertEquals(2,scores.synchronizations);assertTrue(gateway.verifications>=2)
    }
    @Test fun staleDownloadControlsAreIgnored()=runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores);model.open();model.state.first { !it.busy }
        model.pauseDownloads("other-account");model.resumeDownloads("other-account");runCurrent()
        assertEquals(0,scores.pauses);assertEquals(0,scores.resumes)
    }
    @Test fun pauseWhileSyncIsBusy()=runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores);model.open();model.state.first { !it.busy }
        scores.barrier=CompletableDeferred();model.sync();runCurrent();assertTrue(model.state.value.busy)
        model.pauseDownloads();scores.downloads.first { it.paused };assertEquals(1,scores.pauses)
        scores.barrier!!.complete(Unit);model.state.first { !it.busy }
    }
    @Test fun byteProgressDoesNotRewriteAccountState()=runTest(dispatcher) {
        val scores=ScoreSync();val model=model(scores);model.open();model.state.first { !it.busy }
        val before=model.state.value
        scores.downloads.value=scores.downloads.value.copy(rows=listOf(TeamDownload("synthetic","Test","Violin","0101001",1000,500)))
        runCurrent();assertSame(before,model.state.value);assertEquals(500L,model.downloads.value.rows.single().received)
    }
}
