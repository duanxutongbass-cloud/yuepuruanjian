package com.ensemble.scorelibrary.account

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignupFeedbackUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private fun capture(name:String)=ui.runOnUiThread {
        val view=ui.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap));val file=File("build/reports/screenshots/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun validationAndServerResultStayAtSubmit() {
        var state by mutableStateOf(TeamAccountState());var submitted=0
        ui.setContent {MaterialTheme {Surface {
            TeamAccountScreen(state,{_,_,_,_,_->fail()},{},{},{_,_->},{},{},{_,_->},{},
                onSignup={_,_,_,_->submitted++;state=state.copy(busy=true)})
        }}}
        ui.onNodeWithText("注册账号").performClick()
        ui.onNodeWithTag("team-username").performTextInput("fresh-manager")
        ui.onNodeWithTag("team-password").performTextInput("short123")
        ui.onNodeWithTag("team-password-repeat").performTextInput("short123")
        ui.onNodeWithTag("team-name").performTextInput("个人管理者")
        ui.onNodeWithTag("team-submit").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("team-account-status").assertTextEquals("密码须为12–128个字符；当前已输入8个。")
        assertEquals(0,submitted)
        ui.onNodeWithTag("team-password").performScrollTo().performTextReplacement("synthetic password")
        ui.onNodeWithTag("team-password-repeat").performScrollTo().performTextReplacement("different password")
        ui.onNodeWithTag("team-submit").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("team-account-status").assertTextEquals("两次输入的密码不一致，请核对。")
        ui.onNodeWithTag("team-password-repeat").performScrollTo().performTextReplacement("synthetic password")
        ui.onNodeWithTag("team-submit").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1,submitted)
        ui.onNodeWithTag("team-submit").assertIsNotEnabled()
        ui.onNodeWithTag("team-account-status").assertTextContains("正在注册并关联当前设备",substring=true)
        ui.runOnIdle {state=state.copy(busy=false,message="账号名已被使用，请登录已有账号")}
        ui.onNodeWithTag("team-account-status").performScrollTo().assertIsDisplayed().assertTextEquals("账号名已被使用，请登录已有账号")
        capture("0130-signup-feedback")
        assertEquals(1,submitted)
    }
    @Test fun freshAccountCanCreateWithoutSeeingAnUnjoinedTeam() {
        val aid=UUID.randomUUID().toString();val tid=UUID.randomUUID().toString()
        val p=TeamPrincipal(aid,aid,tid,"不应显示的旧团队","fresh-manager","个人管理者","member",false,"unjoined",true,multiTeamProtocol=1)
        var created:String?=null
        ui.setContent {MaterialTheme {Surface {
            TeamPickerContent(TeamAccountState(principal=p,teams=listOf(AccountTeam(tid,p.teamName,"member","unjoined",true,0))),
                {fail()},{created=it},{},{},{})
        }}}
        ui.onNodeWithText("不应显示的旧团队").assertDoesNotExist()
        ui.onNodeWithTag("account-team-switch-$tid").assertDoesNotExist()
        ui.onNodeWithTag("account-team-name").performScrollTo().performTextInput("自己的团队")
        ui.onNodeWithTag("account-team-create").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("account-team-create-consent").performClick()
        ui.onNodeWithTag("account-team-create-confirm").performClick()
        assertEquals("自己的团队",created)
    }
    @Test fun personalManagerDoesNotBecomeAnotherTeamsAdministrator() {
        val aid=UUID.randomUUID().toString();val p=TeamPrincipal(aid,aid,UUID.randomUUID().toString(),"受邀团队","fresh-manager","个人管理者","member",false,multiTeamProtocol=1)
        ui.setContent {MaterialTheme {Surface {
            TeamAccountScreen(TeamAccountState(principal=p),{_,_,_,_,_->fail()},{},{},{_,_->},{},{fail()},{_,_->fail()},{})
        }}}
        ui.onNodeWithTag("account-personal-manager").assertIsDisplayed().assertTextEquals("个人空间管理员")
        ui.onNodeWithText("当前团队：受邀团队 · 成员").assertIsDisplayed()
        ui.onNodeWithTag("team-create-invite").assertDoesNotExist()
        ui.onNodeWithTag("team-code-rotate").assertDoesNotExist()
        capture("0130-personal-manager")
    }
}
