package com.ensemble.scorelibrary.account

import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.sync.TeamScoreSync
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
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MembershipViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val principal = TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "17弦乐团", "new-player", "新成员", "member", false, "unjoined", true)
    private val pendingId = UUID.randomUUID().toString()
    private val gateway = object : TeamAccountGateway {
        override val server = TEAM_SERVICE_URL
        override val device = UUID.randomUUID().toString()
        var remembered = TeamSession(server,"s".repeat(43),Long.MAX_VALUE,principal)
        var revision = 0L
        var offline = false
        var signupError: Exception? = null
        var loseApplicationAck = false
        var loseReviewAck = false
        var reviewed = false
        var codeRevision = 0L
        var codeValue = "A2b3C4"
        val sent = mutableListOf<Pair<String, JSONObject?>>()
        override fun restore() = remembered.copy(offline=offline)
        override fun verify(session:TeamSession) = remembered.copy(offline=offline)
        override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=remembered
        override fun signUp(username:String,password:String,name:String,consent:Boolean):TeamSession {
            signupError?.let { throw it }
            assertEquals("new-player", username); assertEquals("新成员",name); assertFalse(consent)
            return remembered
        }
        override fun clear() {}
        override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject {
            check(!offline)
            sent += path to body?.let { JSONObject(it.toString()) }
            return when(path) {
                "/v1/me/devices" -> JSONObject().put("devices",JSONArray())
                "/v1/me/membership" -> {
                    if(body!=null) {
                        if(!loseApplicationAck || remembered.principal.membership=="unjoined") {
                            assertEquals(0L,body.getLong("baseRevision"));revision=1
                            remembered=remembered.copy(principal=remembered.principal.copy(membership="pending"))
                        }
                        if(loseApplicationAck) { loseApplicationAck=false;throw IOException("lost acknowledgement") }
                    }
                    JSONObject().put("state",remembered.principal.membership).put("revision",revision)
                }
                "/v1/admin/members" -> JSONObject().put("members",JSONArray())
                "/v1/admin/join-code" -> {
                    if(body!=null) { assertEquals(codeRevision,body.getLong("baseRevision"));codeRevision++ }
                    JSONObject().put("configured",codeRevision>0).put("revision",codeRevision).also { if(body!=null)it.put("code",codeValue) }
                }
                "/v1/admin/join-requests" -> JSONObject().put("total",if(reviewed)0 else 1).put("requests",JSONArray().also { if(!reviewed)it.put(
                    JSONObject().put("accountId",pendingId).put("username","new-player").put("displayName","新成员").put("revision",1)) })
                "/v1/admin/join-requests/review" -> {
                    reviewed=true
                    if(loseReviewAck) { loseReviewAck=false;throw IOException("lost acknowledgement") }
                    JSONObject().put("state","joined").put("revision",2)
                }
                else -> JSONObject().put("ok",true)
            }
        }
    }
    private val scores=object:TeamScoreSync {
        var activations=0;var syncs=0;var deactivations=0
        override suspend fun activate(session:TeamSession):String { check(session.principal.joined);activations++;return "isolated-member" }
        override suspend fun sync(session:TeamSession,progress:(Int,Int)->Unit):Int { check(session.principal.joined);syncs++;return 0 }
        override fun deactivate() { deactivations++ }
    }
    @Before fun start(){Dispatchers.setMain(dispatcher)}
    @After fun close(){store.clear();Dispatchers.resetMain()}
    private fun model()=TeamAccountViewModel(gateway,scores).also {store.put("account",it)}
    private suspend fun idle(model:TeamAccountViewModel){model.state.first {!it.busy}}
    @Test fun unjoinedRestoreDoesNotActivateOrRequestTeamContent()=runTest(dispatcher) {
        val vm=model();vm.open();idle(vm)
        assertEquals(0,scores.activations);assertEquals(0,scores.syncs)
        assertNull(vm.state.value.libraryMember);assertTrue(vm.state.value.libraryBlocked)
        assertEquals(TeamMembership("unjoined",0),vm.state.value.membership)
        assertEquals(setOf("/v1/me/devices","/v1/me/membership"),gateway.sent.map{it.first}.toSet())
    }
    @Test fun publicSignupDoesNotRequireInvitationOrBackupConsentAndNeverActivates()=runTest(dispatcher) {
        val vm=model();vm.signup("new-player","synthetic password","新成员",false);idle(vm)
        assertEquals("unjoined",vm.state.value.principal?.membership)
        assertEquals(0,scores.activations);assertTrue(vm.state.value.libraryBlocked)
    }
    @Test fun oldServerCannotProducePretendSignupSuccess()=runTest(dispatcher) {
        gateway.signupError=AccountRejected(404,"registration_unavailable")
        val vm=model();vm.signup("new-player","synthetic password","新成员",false);idle(vm)
        assertNull(vm.state.value.principal);assertEquals(0,scores.activations)
        assertTrue(vm.state.value.message.contains("尚未开放"));assertTrue(vm.state.value.libraryBlocked)
    }
    @Test fun interruptedSignupShowsHowToRecoverWithoutRepeatingRegistration()=runTest(dispatcher) {
        gateway.signupError=java.net.SocketTimeoutException("Synthetic lost registration response")
        val vm=model();vm.signup("new-player","synthetic password","新成员",false);idle(vm)
        assertNull(vm.state.value.principal);assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.message.contains("结果尚未确认"))
        assertTrue(vm.state.value.message.contains("登录"));assertFalse(vm.state.value.message.contains("synthetic password"))
    }
    @Test fun applicationLostAckRetryKeepsRequestIdentityThenRefreshesVerifiedPrincipal()=runTest(dispatcher) {
        val vm=model();vm.open();idle(vm);gateway.loseApplicationAck=true
        vm.applyToTeam("synthetic-code");idle(vm)
        assertEquals("unjoined",vm.state.value.principal?.membership)
        vm.applyToTeam("synthetic-code");idle(vm)
        val sent=gateway.sent.filter{it.first=="/v1/me/membership" && it.second!=null}.map{it.second!!}
        assertEquals(2,sent.size);assertEquals(sent[0].getString("requestId"),sent[1].getString("requestId"))
        assertEquals("pending",vm.state.value.principal?.membership);assertEquals(0,scores.activations)
        assertEquals(TeamMembership("pending",1),vm.state.value.membership)
    }
    @Test fun approvalOnForegroundVerificationActivatesOnlyAfterJoinedAndNeedsFileAssignments()=runTest(dispatcher) {
        val vm=model();vm.open();idle(vm)
        gateway.remembered=gateway.remembered.copy(principal=principal.copy(membership="joined"));gateway.revision=2
        vm.sync();idle(vm)
        assertEquals(1,scores.activations);assertEquals(1,scores.syncs)
        assertEquals("isolated-member",vm.state.value.libraryMember)
        assertTrue(vm.state.value.syncMessage.contains("暂无分配"))
        vm.sync();idle(vm);assertEquals(1,scores.activations)
    }
    @Test fun rejectedOrPendingReverificationClosesOldCollectionImmediately()=runTest(dispatcher) {
        gateway.remembered=gateway.remembered.copy(principal=principal.copy(membership="joined"))
        val vm=model();vm.open();idle(vm);assertEquals(1,scores.syncs)
        gateway.remembered=gateway.remembered.copy(principal=principal.copy(membership="rejected"));gateway.revision=2
        vm.refresh();idle(vm)
        assertNull(vm.state.value.libraryMember);assertTrue(vm.state.value.libraryBlocked);assertEquals(1,scores.syncs)
        assertTrue(vm.state.value.members.isEmpty());assertTrue(scores.deactivations>0)
        assertEquals(TeamMembership("rejected",2),vm.state.value.membership)
    }
    @Test fun offlinePendingHasNoNetworkOrLibraryAndLogoutDoesNotKeepOldSelection()=runTest(dispatcher) {
        gateway.remembered=gateway.remembered.copy(principal=principal.copy(membership="pending"));gateway.offline=true
        val vm=model();vm.open();idle(vm)
        assertTrue(gateway.sent.isEmpty());assertNull(vm.state.value.libraryMember);assertTrue(vm.state.value.libraryBlocked)
        vm.logout();idle(vm)
        assertNull(vm.state.value.principal);assertTrue(vm.state.value.libraryBlocked)
    }
    @Test fun adminCodeAndLostReviewAckRetryAreBoundToRevisionAndRequest()=runTest(dispatcher) {
        gateway.remembered=gateway.remembered.copy(principal=principal.copy(role="admin",membership="joined"))
        val vm=model();vm.open();idle(vm)
        assertEquals(1,vm.state.value.joinRequestTotal)
        vm.rotateJoinCode(0);idle(vm);assertEquals("A2b3C4",vm.state.value.joinCode)
        assertFalse(vm.state.value.toString().contains("A2b3C4"))
        vm.hideInvitation();assertNull(vm.state.value.joinCode)
        val request=vm.state.value.joinRequests.single();gateway.loseReviewAck=true
        vm.reviewJoin(request,true);idle(vm);vm.reviewJoin(request,true);idle(vm)
        val sent=gateway.sent.filter{it.first.endsWith("/review")}.map{it.second!!}
        assertEquals(2,sent.size);assertEquals(sent[0].getString("requestId"),sent[1].getString("requestId"))
        assertTrue(vm.state.value.joinRequests.isEmpty());assertTrue(vm.state.value.message.contains("分配声部"))
    }
    @Test fun legacyLongCodeStillDisplaysWhileInvalidCodeDoesNotBecomeConfigured()=runTest(dispatcher) {
        gateway.remembered=gateway.remembered.copy(principal=principal.copy(role="admin",membership="joined"))
        val vm=model();vm.open();idle(vm)
        gateway.codeValue="c".repeat(32)
        vm.rotateJoinCode(0);idle(vm);assertEquals(gateway.codeValue,vm.state.value.joinCode)
        assertEquals(1L,vm.state.value.joinCodeRevision)
        vm.hideInvitation();gateway.codeValue="bad code"
        vm.rotateJoinCode(1);idle(vm)
        assertNull(vm.state.value.joinCode);assertEquals(1L,vm.state.value.joinCodeRevision)
    }
}
