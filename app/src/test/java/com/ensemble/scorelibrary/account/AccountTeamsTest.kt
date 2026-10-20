package com.ensemble.scorelibrary.account

import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.sync.TeamScoreSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AccountTeamsTest {
    private val dispatcher=StandardTestDispatcher();private val models=ViewModelStore()
    private val aid=UUID.randomUUID().toString();private val a=UUID.randomUUID().toString();private val b=UUID.randomUUID().toString()
    private val c=UUID.randomUUID().toString()
    private val principal=TeamPrincipal(aid,aid,a,"团队甲","director","测试账号","admin",true,multiTeamProtocol=1,membershipSupported=true)
    private val initial=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal)
    private val events=mutableListOf<String>()
    private val gateway=object:TeamAccountGateway {
        override val server=TEAM_SERVICE_URL;override val device=UUID.randomUUID().toString()
        var current=initial;var offline=false;var wrongOwner=false
        var loseCreateAck=false;var created=false;var polls=0;val creates=mutableListOf<JSONObject>()
        override fun restore()=current
        override fun verify(session:TeamSession)=current.copy(offline=offline)
        override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=current
        override fun clear() {events.add("clear")}
        override fun switchTeam(session:TeamSession,teamId:String):TeamSession {
            events.add("switch")
            assertTrue(events.indexOf("deactivate")<events.indexOf("switch"))
            return initial.copy(token="n".repeat(43),principal=principal.copy(teamId=teamId,teamName="团队乙",role="member",
                accountId=if(wrongOwner)UUID.randomUUID().toString() else aid)).also {current=it}
        }
        override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject=when(path) {
            "/v1/me/devices"->JSONObject().put("devices",JSONArray())
            "/v1/admin/members"->JSONObject().put("members",JSONArray())
            "/v1/admin/join-code"->JSONObject().put("revision",0).put("configured",false)
            "/v1/admin/join-requests"->JSONObject().put("requests",JSONArray()).put("total",0)
            "/v1/me/membership"->JSONObject().put("state","joined").put("revision",0)
            "/v1/me/teams"->teams(session).also {if(created)it.getJSONArray("teams").put(JSONObject().put("teamId",c).put("teamName","团队丙")
                .put("role","admin").put("membership","joined").put("active",true).put("revision",0))}
            "/v1/me/teams/create"->{creates+=JSONObject(checkNotNull(body).toString())
                if(loseCreateAck){loseCreateAck=false;throw java.io.IOException("Synthetic lost acknowledgement")}
                JSONObject().put("pending",true).put("requestId",body.getString("requestId"))}
            else->if(path.startsWith("/v1/me/team-operations/")) {
                val request=creates.last().getString("requestId");assertTrue(path.endsWith(request));polls++
                if(polls==1)JSONObject().put("pending",true).put("requestId",request)
                else {created=true;JSONObject().put("teamId",c).put("teamName","团队丙").put("created",true).put("requestId",request)}
            } else error(path)
        }
    }
    private val scores=object:TeamScoreSync {
        override suspend fun activate(session:TeamSession):String { events.add("activate/${session.principal.teamId}");return session.principal.teamId }
        override suspend fun sync(session:TeamSession,progress:(Int,Int)->Unit)=0
        override fun deactivate(){events.add("deactivate")}
    }
    private fun teams(session:TeamSession)=JSONObject().put("protocolVersion",1).put("accountId",aid)
        .put("currentTeamId",session.principal.teamId).put("teams",JSONArray(listOf(
            JSONObject().put("teamId",a).put("teamName","团队甲").put("role","admin").put("membership","joined").put("active",true).put("revision",0),
            JSONObject().put("teamId",b).put("teamName","团队乙").put("role","member").put("membership","joined").put("active",true).put("revision",2))))
    @Before fun start(){Dispatchers.setMain(dispatcher)}
    @After fun close(){models.clear();Dispatchers.resetMain()}
    private suspend fun idle(vm:TeamAccountViewModel){vm.state.first {!it.busy}}
    private fun model()=TeamAccountViewModel(gateway,scores).also {models.put("account",it)}

    @Test fun parserRejectsForeignAccountCurrentTeamAndDuplicateIds() {
        assertEquals(2,parseAccountTeams(teams(initial),initial).size)
        assertTrue(runCatching {parseAccountTeams(teams(initial).put("accountId",UUID.randomUUID().toString()),initial)}.isFailure)
        assertTrue(runCatching {parseAccountTeams(teams(initial).put("currentTeamId",b),initial)}.isFailure)
        val json=teams(initial);json.getJSONArray("teams").getJSONObject(1).put("teamId",a)
        assertTrue(runCatching {parseAccountTeams(json,initial)}.isFailure)
    }
    @Test fun multiProtocolIsPersistedAndUnknownVersionRefused() {
        assertEquals(principal,TeamPrincipal.parse(principal.json()))
        assertTrue(runCatching {TeamPrincipal.parse(principal.json().put("multiTeamProtocol",2))}.isFailure)
        assertEquals(0,TeamPrincipal.parse(principal.copy(multiTeamProtocol=0).json()).multiTeamProtocol)
    }
    @Test fun switchClosesOldLibraryBeforeRequestAndActivatesTargetRole()=runTest(dispatcher) {
        val vm=model();vm.open();idle(vm);events.clear()
        vm.switchTeam(b);idle(vm)
        assertEquals(b,vm.state.value.principal?.teamId);assertEquals("member",vm.state.value.principal?.role)
        assertEquals(b,vm.state.value.libraryMember);assertTrue(vm.state.value.members.isEmpty())
        assertNull(vm.state.value.joinCode);assertEquals(listOf("deactivate","switch","activate/$b"),events)
    }
    @Test fun offlineOrForeignTargetDoesNotSwitchOrExposeAnotherLibrary()=runTest(dispatcher) {
        val vm=model();vm.open();idle(vm);events.clear();gateway.offline=true
        vm.switchTeam(b);idle(vm);assertFalse("switch" in events);assertEquals(a,vm.state.value.principal?.teamId)
        gateway.offline=false;gateway.wrongOwner=true;events.clear();vm.switchTeam(b);idle(vm)
        assertEquals(a,vm.state.value.principal?.teamId);assertNull(vm.state.value.libraryMember)
        assertFalse("activate/$b" in events)
    }
    @Test fun creationPollsDurableRequestAndLostAckRetryDoesNotSwitchOrCreateAnotherId()=runTest(dispatcher) {
        val vm=model();vm.open();idle(vm);events.clear();gateway.loseCreateAck=true
        vm.createTeam("团队丙");idle(vm);assertEquals(a,vm.state.value.principal?.teamId)
        vm.createTeam("团队丙");idle(vm)
        assertEquals(2,gateway.creates.size);assertEquals(gateway.creates[0].getString("requestId"),gateway.creates[1].getString("requestId"))
        assertEquals(2,gateway.polls);assertEquals(a,vm.state.value.principal?.teamId)
        assertTrue(vm.state.value.teams.any {it.id==c && it.role=="admin"});assertTrue(events.isEmpty())
    }
}
