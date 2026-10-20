package com.ensemble.scorelibrary.sync

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers="w800dp-h1200dp-mdpi")
class TeamManagementHostTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore()
    private val scope=TeamLibraryScope(ApplicationProvider.getApplicationContext<Context>())
    private val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
        "测试乐团","admin","管理员","admin",true)
    private val session=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal)
    private val gateway=object:TeamAccountGateway {
        override val server=TEAM_SERVICE_URL
        override val device=UUID.randomUUID().toString()
        override fun restore()=session
        override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
        override fun request(session:TeamSession,path:String,body:JSONObject?)=when(path) {
            "/v1/admin/ensemble"->JSONObject().put("revision",1).put("groups",JSONArray()).put("programs",JSONArray())
            "/v1/me/devices"->JSONObject().put("devices",JSONArray())
            "/v1/admin/members"->JSONObject().put("members",JSONArray())
            else->JSONObject().put("ok",true)
        }
        override fun clear() {}
    }
    private var collection:TeamCollection?=null
    @After fun close() { ui.runOnUiThread { models.clear();scope.deactivate() };collection?.database?.close() }
    private fun waitFor(condition:()->Boolean)=ui.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle();condition() }
    @Test fun removalOfLibraryEntryDuringLoadingDoesNotCloseRecycleDialogButLogoutDoes() {
        lateinit var ensemble:TeamEnsembleViewModel
        lateinit var account:TeamAccountViewModel
        var ready by mutableStateOf(true)
        ui.runOnUiThread {
            collection=scope.activate(session)
            ensemble=TeamEnsembleViewModel(scope,gateway).also { models.put("ensemble",it) }
            account=TeamAccountViewModel(gateway).also { models.put("account",it);it.open() }
        }
        waitFor { account.state.value.principal!=null && !account.state.value.busy && ensemble.state.value.key==collection!!.key }
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalTeamEnsemble provides ensemble) {
            Column { if(ready)TeamEnsembleEntry(account) else Text("目录正在刷新") }
            TeamEnsembleDialogHost(account)
        } } }
        ui.onNodeWithTag("team-manage-library").performClick()
        waitFor { !ensemble.state.value.busy && ensemble.state.value.revision==1L }
        ui.onNodeWithText("查看回收站").performClick()
        ui.onNodeWithText("搜索曲名、编号、分类或乐器").performTextInput("继续删除")
        ui.runOnIdle { ready=false }
        ui.onNodeWithTag("team-management-back").assertExists()
        ui.onNodeWithText("回收站").assertExists()
        ui.onNodeWithText("搜索曲名、编号、分类或乐器").assertTextContains("继续删除")
        ui.runOnIdle { ready=true }
        ui.onNodeWithTag("team-management-back").assertExists()
        ui.runOnIdle { account.logout() }
        waitFor { account.state.value.principal==null && ensemble.management.value==null }
        ui.onNodeWithTag("team-management-back").assertDoesNotExist()
    }
}
