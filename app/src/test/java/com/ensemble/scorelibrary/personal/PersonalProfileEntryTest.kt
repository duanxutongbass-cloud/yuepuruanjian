package com.ensemble.scorelibrary.personal

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.ui.CompactProfilePage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
class PersonalProfileEntryTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore()
    @After fun close() { models.clear() }
    @Test fun loggedInOrdinaryMemberGetsOwnFilesEntryAndLogoutRemovesIt() {
        val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Test team","member","普通成员","member",true)
        val session=TeamSession(TEAM_SERVICE_URL,"synthetic-never-sent",Long.MAX_VALUE,principal)
        val requests=mutableListOf<String>()
        val gateway=object:TeamAccountGateway {
            override val server=TEAM_SERVICE_URL
            override val device=UUID.randomUUID().toString()
            override fun restore()=session
            override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
            override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject {
                synchronized(requests) { requests+=path }
                return JSONObject().put("devices",JSONArray())
            }
            override fun clear() {}
        }
        val account=TeamAccountViewModel(gateway).also { models.put("account",it) }
        val dashboard=Dashboard(listOf(MemberChoice("m","普通成员")),Profile("m","普通成员",null,false))
        compose.setContent { CompositionLocalProvider(LocalTeamAccount provides account) { MaterialTheme { CompactProfilePage(dashboard,{}, {_,_,_->fail("Must not enter team administration")}) } } }
        compose.onNodeWithTag("profile-personal").assertDoesNotExist()
        compose.runOnIdle { account.open() }
        compose.waitUntil(15_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();account.state.value.principal!=null && !account.state.value.busy }
        compose.onNodeWithTag("profile-personal").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("profile-management").assertDoesNotExist()
        compose.onNodeWithTag("profile-part-grants").assertDoesNotExist()
        assertEquals("member",account.state.value.principal!!.role)
        assertTrue(synchronized(requests) { requests.none { it.startsWith("/v1/admin/") } })
        compose.runOnIdle { account.logout() }
        compose.waitUntil(15_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();account.state.value.principal==null && !account.state.value.busy }
        compose.onNodeWithTag("profile-personal").assertDoesNotExist()
    }
}
