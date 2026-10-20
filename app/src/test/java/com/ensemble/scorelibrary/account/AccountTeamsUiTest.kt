package com.ensemble.scorelibrary.account

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountTeamsUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val a=UUID.randomUUID().toString();private val b=UUID.randomUUID().toString();private val aid=UUID.randomUUID().toString()
    private val p=TeamPrincipal(aid,aid,a,"团队甲","director","测试账号","admin",true,multiTeamProtocol=1)
    private val rows=listOf(AccountTeam(a,"团队甲","admin","joined",true,0),AccountTeam(b,"团队乙","member","joined",true,0))
    private fun capture(name:String)=ui.runOnUiThread {
        val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView)
        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap));val file=java.io.File("build/reports/screenshots/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun switchRevalidatesTeamAndCreateRequiresExplicitConsent() {
        var state by mutableStateOf(TeamAccountState(principal=p,teams=rows));var switched:String?=null;var created:String?=null
        ui.setContent { MaterialTheme { Dialog({},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface {TeamPickerContent(state,{switched=it},{created=it},{},{},{})}
        } } }
        ui.onNodeWithTag("account-team-current-$a",useUnmergedTree=true).assertTextEquals("当前团队")
        capture("0129-account-teams")
        ui.onNodeWithTag("account-team-switch-$b").performClick()
        ui.runOnIdle {state=state.copy(teams=listOf(rows[0],rows[1].copy(active=false)))}
        ui.onNodeWithTag("account-team-switch-confirm").assertIsNotEnabled();assertNull(switched)
        ui.onNodeWithText("取消").performClick()
        ui.runOnIdle {state=state.copy(teams=rows)}
        ui.onNodeWithTag("account-team-name").performScrollTo().performTextInput("我的新团队")
        ui.onNodeWithTag("account-team-create").performScrollTo().performClick()
        ui.onNodeWithTag("account-team-create-confirm").assertIsNotEnabled()
        ui.onNodeWithTag("account-team-create-consent").performClick()
        capture("0129-team-create-confirmation")
        ui.onNodeWithTag("account-team-create-confirm").performClick();assertEquals("我的新团队",created)
    }
    @Test fun offlineCannotSwitchCreateOrApply() {
        val state=TeamAccountState(principal=p,teams=rows,offline=true)
        ui.setContent {MaterialTheme {TeamPickerContent(state,{fail()},{fail()},{fail()},{},{})}}
        ui.onNodeWithTag("account-team-switch-$b").assertIsNotEnabled()
        ui.onNodeWithTag("account-team-apply").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("account-team-create").performScrollTo().assertIsNotEnabled()
    }
}
