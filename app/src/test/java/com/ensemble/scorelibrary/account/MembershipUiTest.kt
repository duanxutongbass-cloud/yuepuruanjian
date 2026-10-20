package com.ensemble.scorelibrary.account

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.sync.*
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MembershipUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val store=ViewModelStore()
    private val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
        "17弦乐团","new-player","新成员","member",false,"unjoined",true)
    @After fun close(){store.clear()}
    @Test fun signedInAccountCanExitWhileLibraryWorkIsBusy() {
        var logout=false
        compose.setContent { MaterialTheme {
            TeamAccountScreen(TeamAccountState(principal=principal,busy=true),{_,_,_,_,_->},{logout=true},{},{_,_->},{},{},{_,_->},{})
        } }
        compose.onNodeWithTag("team-logout").performScrollTo().assertIsEnabled().performClick()
        assertTrue(logout)
        capture("account-busy-exit-enabled.png")
    }
    @Test fun signupForm() {
        var submitted=false
        compose.setContent { MaterialTheme {
            TeamAccountScreen(TeamAccountState(),{_,_,_,_,_->fail("Public signup must not consume a legacy invitation")},{},{},{_,_->},{},{},{_,_->},{},
                onSignup={user,password,name,consent->assertEquals("new-player",user);assertEquals("synthetic password",password)
                    assertEquals("新成员",name);assertFalse(consent);submitted=true})
        } }
        compose.onNodeWithText("注册账号").performClick()
        compose.onNodeWithTag("team-invitation").assertDoesNotExist()
        compose.onNodeWithTag("team-username").performTextInput("new-player")
        compose.onNodeWithTag("team-password").performTextInput("synthetic password")
        compose.onNodeWithTag("team-name").performTextInput("新成员")
        compose.onNodeWithTag("team-submit").assertIsNotEnabled()
        compose.onNodeWithTag("team-password-repeat").performTextInput("synthetic password")
        compose.onNodeWithTag("team-submit").performScrollTo().assertIsEnabled()
        capture("membership-signup.png")
        compose.onNodeWithTag("team-submit").performClick();assertTrue(submitted)
        compose.onNodeWithTag("team-password").assertTextEquals("密码（12–128 个字符）","")
    }
    @Test fun applicantStates() {
        var state by mutableStateOf(TeamAccountState(principal=principal,membership=TeamMembership("unjoined",0)))
        var sent=""
        compose.setContent { MaterialTheme {
            TeamAccountScreen(state,{_,_,_,_,_->},{},{},{_,_->},{},{},{_,_->},{},onApply={sent=it})
        } }
        compose.onNodeWithTag("team-sync").assertDoesNotExist()
        compose.onNodeWithTag("team-create-invite").assertDoesNotExist()
        compose.onNodeWithTag("team-join-apply").assertIsNotEnabled()
        compose.onNodeWithTag("team-join-code").performTextInput("synthetic-team-code")
        compose.onNodeWithTag("team-join-apply").assertIsEnabled().performClick();assertEquals("synthetic-team-code",sent)
        compose.runOnIdle{state=state.copy(principal=principal.copy(membership="pending"),membership=TeamMembership("pending",1))}
        compose.onNodeWithTag("team-membership-status").assertTextEquals("等待管理员审核")
        compose.onNodeWithTag("team-join-code").assertDoesNotExist();capture("membership-pending.png")
        compose.runOnIdle{state=state.copy(principal=principal.copy(membership="rejected"),membership=TeamMembership("rejected",2),offline=true)}
        compose.onNodeWithTag("team-join-code").assertIsNotEnabled()
        compose.onNodeWithTag("team-join-apply").assertIsNotEnabled()
    }
    @Test fun administratorConfirmsDecisions() {
        val request=TeamJoinRequest(UUID.randomUUID().toString(),"violinist","小提琴成员",1)
        var reviewed:Pair<TeamJoinRequest,Boolean>?=null;var rotated:Long?=null
        compose.setContent { MaterialTheme {
            TeamAccountScreen(TeamAccountState(principal=principal.copy(role="admin",membership="joined"),
                joinRequests=listOf(request),joinRequestTotal=1,joinCodeRevision=2,joinCodeConfigured=true),
                {_,_,_,_,_->},{},{},{_,_->},{},{},{_,_->},{},onRotateCode={rotated=it},onReview={r,a->reviewed=r to a})
        } }
        compose.onNodeWithTag("team-join-approve-${request.id}").performScrollTo().performClick();assertNull(reviewed)
        compose.onNodeWithText("批准入团").assertExists()
        compose.onNodeWithTag("team-review-confirm").performClick();assertEquals(request to true,reviewed)
        compose.onNodeWithTag("team-join-reject-${request.id}").performScrollTo().performClick()
        compose.onNodeWithTag("team-review-confirm").performClick();assertEquals(request to false,reviewed)
        compose.onNodeWithTag("team-code-rotate").performScrollTo().performClick();assertNull(rotated)
        compose.onNodeWithTag("team-code-confirm").performClick();assertEquals(2L,rotated)
    }
    private class Gateway(var session:TeamSession):TeamAccountGateway {
        override val server=TEAM_SERVICE_URL;override val device=UUID.randomUUID().toString()
        var restoreFailure=false
        var signedOut=false
        var createdTeam:String?=null
        val ownTeam=UUID.randomUUID().toString()
        override fun restore():TeamSession? {if(restoreFailure)throw IOException("offline");return session.takeUnless{signedOut}}
        override fun verify(session:TeamSession)=this.session
        override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
        override fun signUp(username:String,password:String,name:String,consent:Boolean):TeamSession {signedOut=false;return session}
        override fun request(session:TeamSession,path:String,body:JSONObject?)=when(path){
            "/v1/me/devices"->JSONObject().put("devices",JSONArray())
            "/v1/me/teams"->JSONObject().put("protocolVersion",1).put("accountId",session.principal.accountId)
                .put("currentTeamId",session.principal.teamId).put("teams",JSONArray().also { rows -> createdTeam?.let { title ->
                    rows.put(JSONObject().put("teamId",ownTeam).put("teamName",title).put("role","admin")
                        .put("membership","joined").put("active",true).put("revision",0))
                } })
            "/v1/me/teams/create"->JSONObject().put("teamId",ownTeam).put("teamName",checkNotNull(body).getString("name").also {createdTeam=it})
                .put("created",true).put("requestId",body.getString("requestId"))
            "/v1/me/membership"->JSONObject().put("state",this.session.principal.membership).put("revision",1)
            else->JSONObject().put("ok",true)
        }
        override fun clear(){}
    }
    private fun gate(gateway:Gateway,accountEntry:Boolean=false,activationFailure:()->Boolean={false}):Pair<TeamAccountViewModel,LibraryViewModel> {
        val library=LibraryViewModel(object:PresentationRepository {
            override fun observe(memberId:String?,request:ScreenRequest)=flowOf<DomainResult<Presentation>>(
                DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"")))
        },SavedStateHandle(mapOf("memberId" to "old-owner")),object:DeviceAvailability {
            override val online=flowOf(true)
            override suspend fun filesAvailable(segments:List<ResolvedScoreSegment>)=false
        }).also{store.put("library",it)}
        val vm=TeamAccountViewModel(gateway,object:TeamScoreSync {
            override suspend fun activate(session:TeamSession):String {check(session.principal.joined);if(activationFailure())throw IOException("Synthetic activation failure");return "approved-team-member"}
            override suspend fun sync(session:TeamSession,progress:(Int,Int)->Unit)=0
            override fun deactivate(){}
        }).also{store.put("account",it)}
        compose.setContent { ScoreTheme {
            val selected by library.selectedMember.collectAsState()
            TeamLibraryGate(vm,library) {
            if(accountEntry)Column {Text("曲库 $selected",Modifier.testTag("library-content"));TeamAccountEntry(vm)}
            else Text("曲库 $selected",Modifier.testTag("library-content"))
        } } }
        return vm to library
    }
    private fun waitFor(condition:()->Boolean){compose.waitUntil(15_000){Shadows.shadowOf(Looper.getMainLooper()).idle();condition()}}
    @Test fun signupThroughDialogCanOpenTeamsCreateAndReturnToPersonalLibrary() {
        val p=principal.copy(multiTeamProtocol=1)
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,p)).also{it.signedOut=true}
        val (vm,library)=gate(gateway,accountEntry=true)
        waitFor{!vm.state.value.busy && vm.state.value.principal==null}
        compose.onNodeWithTag("team-account-entry").performClick()
        waitFor{!vm.state.value.busy}
        compose.onNodeWithText("注册账号").performClick()
        compose.onNodeWithTag("team-username").performTextInput("new-player")
        compose.onNodeWithTag("team-password").performTextInput("synthetic password")
        compose.onNodeWithTag("team-password-repeat").performTextInput("synthetic password")
        compose.onNodeWithTag("team-name").performTextInput("新成员")
        compose.onNodeWithTag("team-submit").performScrollTo().performClick()
        waitFor{!vm.state.value.busy && vm.state.value.principal!=null}
        compose.onNodeWithTag("team-account-back").assertDoesNotExist()
        compose.onNodeWithTag("personal-import").assertIsEnabled()
        compose.onNodeWithTag("nav-profile").assertIsDisplayed().performClick()
        compose.onNodeWithTag("personal-account-profile").assertIsDisplayed()
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("personal-concerts-empty").assertIsDisplayed()
        compose.onNodeWithTag("library-content").assertDoesNotExist()
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithTag("personal-import").assertIsEnabled()
        compose.onNodeWithTag("account-teams-entry").performClick()
        compose.onNodeWithTag("account-team-name").performScrollTo().performTextInput("新账号自己的团队")
        compose.onNodeWithTag("account-team-create").performScrollTo().performClick()
        compose.onNodeWithTag("account-team-create-consent").performClick()
        compose.onNodeWithTag("account-team-create-confirm").performClick()
        waitFor{!vm.state.value.busy && gateway.createdTeam!=null}
        compose.onNodeWithTag("account-team-switch-${gateway.ownTeam}").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("account-teams-close").performClick()
        compose.onNodeWithTag("personal-import").assertIsEnabled()
        assertNull(library.selectedMember.value);assertEquals("member",vm.state.value.principal?.role)
        assertEquals("新账号自己的团队",gateway.createdTeam)
        capture("0132-signup-interaction-personal.png")
    }
    @Test fun unjoinedNavigationRemainsAvailableWhilePendingRejectedOrOffline() {
        val p=principal.copy(multiTeamProtocol=1)
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,p))
        val (vm,library)=gate(gateway)
        waitFor{!vm.state.value.busy && vm.state.value.principal!=null}
        for(membership in listOf("unjoined","pending","rejected")) {
            compose.runOnIdle {gateway.session=gateway.session.copy(principal=p.copy(membership=membership));vm.refresh()}
            waitFor{!vm.state.value.busy}
            compose.onNodeWithTag("nav-profile").performClick()
            compose.onNodeWithTag("personal-account-profile").assertIsDisplayed()
            compose.onNodeWithTag("personal-profile-library").performClick()
            compose.onNodeWithTag("personal-import").assertIsEnabled()
            compose.onNodeWithTag("library-space-team").assertDoesNotExist()
            compose.onNodeWithTag("library-content").assertDoesNotExist()
            assertNull(library.selectedMember.value)
        }
        compose.runOnIdle {gateway.session=gateway.session.copy(offline=true);vm.refresh()}
        waitFor{!vm.state.value.busy}
        compose.onNodeWithTag("nav-profile").performClick()
        compose.onNodeWithTag("personal-account-profile").assertIsDisplayed()
        capture("0132-new-account-profile.png")
    }
    @Test fun signupFeedback() {
        val p=principal.copy(multiTeamProtocol=1)
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,p)).also{it.signedOut=true}
        val (vm,library)=gate(gateway);waitFor{!vm.state.value.busy && vm.state.value.principal==null}
        compose.runOnIdle{vm.signup("new-player","synthetic password","新成员",false)}
        waitFor{!vm.state.value.busy && vm.state.value.principal!=null}
        compose.onNodeWithTag("personal-account-feedback").assertIsDisplayed().assertTextEquals("注册成功，已登录。个人空间由你管理，可以创建团队或申请加入其他团队。")
        compose.onNodeWithTag("team-signed-in").assertTextContains("个人空间管理员",substring=true)
        compose.onNodeWithTag("personal-import").assertIsEnabled()
        compose.onNodeWithTag("account-teams-entry").assertIsDisplayed()
        compose.onNodeWithTag("library-space-team").assertDoesNotExist()
        assertNull(library.selectedMember.value);assertEquals("member",vm.state.value.principal?.role)
        capture("0130-signup-personal-success.png")
    }
    @Test fun membershipGate() {
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal.copy(membership="pending")))
        val (vm,library)=gate(gateway)
        waitFor{!vm.state.value.busy && vm.state.value.principal!=null && library.selectedMember.value==null}
        compose.onNodeWithTag("library-content").assertDoesNotExist();compose.onNodeWithTag("team-membership-gate").assertExists()
        compose.onNodeWithTag("personal-import").assertIsEnabled()
        compose.onNodeWithTag("library-space-personal").assertExists()
        compose.onNodeWithTag("library-space-team").assertDoesNotExist()
        compose.runOnIdle{gateway.session=gateway.session.copy(principal=principal.copy(membership="joined"));vm.sync()}
        waitFor{!vm.state.value.busy && library.selectedMember.value=="approved-team-member"}
        compose.onNodeWithTag("library-content").assertTextEquals("曲库 approved-team-member")
        compose.runOnIdle{gateway.session=gateway.session.copy(principal=principal.copy(membership="rejected"));vm.refresh()}
        waitFor{!vm.state.value.busy && library.selectedMember.value==null}
        compose.onNodeWithTag("library-content").assertDoesNotExist()
        compose.runOnIdle{vm.logout()};waitFor{!vm.state.value.busy && vm.state.value.principal==null}
        compose.onNodeWithTag("library-content").assertDoesNotExist();compose.onNodeWithTag("team-username").assertExists()
    }
    @Test fun unresolvedIdentityGate() {
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal)).also{it.restoreFailure=true}
        val (vm,_)=gate(gateway);waitFor{!vm.state.value.busy && vm.state.value.message.isNotBlank()}
        compose.onNodeWithTag("library-content").assertDoesNotExist()
        compose.onNodeWithTag("team-membership-gate").assertExists()
    }
    @Test fun pendingAccountDetailsCanReturnWithoutLoggingOutOrExposingOldOwner() {
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal.copy(membership="pending")))
        val (vm,library)=gate(gateway)
        waitFor{!vm.state.value.busy && vm.state.value.principal!=null}
        compose.onNodeWithTag("team-account-entry").performClick()
        compose.onNodeWithTag("team-account-back").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("team-signed-in").assertIsDisplayed()
        compose.onNodeWithTag("team-account-back").assertDoesNotExist()
        assertNotNull(vm.state.value.principal)
        compose.onNodeWithTag("library-content").assertDoesNotExist()
        assertNull(library.selectedMember.value)
    }
    @Test fun activationFailureKeepsLoginDetailsBackPreservesSessionAndRetryEntersTeamLibrary() {
        var failing=true
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal.copy(membership="joined")))
        val (vm,library)=gate(gateway){failing}
        waitFor{!vm.state.value.busy && vm.state.value.principal!=null}
        compose.onNodeWithTag("team-signed-in").assertIsDisplayed()
        compose.onNodeWithTag("team-library-retry").assertIsDisplayed()
        compose.onNodeWithTag("team-account-entry").performClick()
        compose.onNodeWithTag("team-account-back").performClick()
        waitFor{!vm.state.value.busy}
        assertNotNull(vm.state.value.principal)
        assertNull(library.selectedMember.value)
        compose.runOnIdle { failing=false }
        compose.onNodeWithTag("team-library-retry").performClick()
        waitFor{!vm.state.value.busy && library.selectedMember.value=="approved-team-member"}
        assertNotNull(vm.state.value.principal)
        compose.onNodeWithTag("library-content").assertTextEquals("曲库 approved-team-member")
        compose.onNodeWithTag("team-membership-gate").assertDoesNotExist()
    }
    @Test fun failedRestoreCanReturnToUnselectedLocalLibrary() {
        val gateway=Gateway(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal)).also{it.restoreFailure=true}
        val (vm,library)=gate(gateway)
        waitFor{!vm.state.value.busy && vm.state.value.message.isNotBlank()}
        compose.onNodeWithTag("team-gate-back").assertIsDisplayed().performClick()
        waitFor{!vm.state.value.busy && !vm.state.value.libraryBlocked}
        compose.onNodeWithTag("library-content").assertTextEquals("曲库 null")
        assertNull(library.selectedMember.value)
    }
    @Test @Config(qualifiers="w640dp-h360dp-mdpi")
    fun busyAccountExitRemainsVisibleAboveScrollableContentOnShortScreen() {
        var left=false
        compose.setContent { MaterialTheme {
            val state=TeamAccountState(principal=principal.copy(membership="pending"),busy=true)
            TeamAccountGateContent({left=true}) {
                TeamAccountScreen(state,{_,_,_,_,_->},{},{},{_,_->},{},{},{_,_->},{})
            }
        } }
        compose.onNodeWithTag("team-gate-back").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(left);capture("hotfix-0120-account-back-short.png")
        left=false
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(left)
    }
    private fun capture(name:String) {
        compose.waitForIdle();lateinit var bitmap:Bitmap
        compose.runOnUiThread{val view=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))}
        val file=File("build/reports/screenshots/$name");check(file.parentFile!!.isDirectory||file.parentFile!!.mkdirs())
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
