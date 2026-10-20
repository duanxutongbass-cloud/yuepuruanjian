package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamDownloadUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val rows=listOf(
        TeamDownload("violin","合成同名乐曲","01 第一小提琴","0101001",80000,80000,DownloadPhase.STAGED,"01 久石让"),
        TeamDownload("cello","合成同名乐曲","02 大提琴","0201001",80000,16000,DownloadPhase.WAITING,"01 久石让"))
    @Test fun pauseResumeAndStageLabels() {
        var value by mutableStateOf(TeamDownloadState("synthetic",rows,active=true))
        var paused=0;var resumed=0
        ui.setContent { MaterialTheme { Surface { TeamDownloadPanel(value,false,false,{paused++},{resumed++}) } } }
        ui.onNodeWithTag("download-summary").assertTextEquals("已入库 0 / 2 份")
        ui.onNodeWithText("已接收，等待入库",substring=true).assertIsDisplayed()
        ui.onNodeWithText("0101001",substring=true).assertIsDisplayed();ui.onNodeWithText("0201001",substring=true).assertIsDisplayed()
        ui.onNodeWithTag("download-pause").performClick();assertEquals(1,paused)
        ui.runOnIdle { value=value.copy(paused=true,active=false,message="下载已暂停；继续时重新核验权限") }
        ui.onNodeWithTag("download-resume").assertIsEnabled()
        ui.waitForIdle()
        lateinit var bitmap:android.graphics.Bitmap
        ui.runOnUiThread { val view=ui.activity.window.decorView
            bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap)) }
        val file=java.io.File("build/reports/screenshots/team-downloads.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        ui.onNodeWithTag("download-resume").performClick();assertEquals(1,resumed)
    }
    @Test fun searchNumberAndPart() {
        val many=(1..400).map { n -> TeamDownload("$n","合成同名乐曲",if(n==399)"02 大提琴" else "01 小提琴",
            "0101%03d".format(n),80000,category=if(n==399)"02 迪士尼" else "01 久石让") }
        ui.setContent { MaterialTheme { Surface { TeamDownloadPanel(TeamDownloadState("synthetic",many),false,false,{},{}) } } }
        ui.onNodeWithTag("download-search").performTextInput("399")
        ui.onNodeWithTag("download-row-399").assertIsDisplayed()
        ui.onNodeWithText("02 大提琴",substring=true).assertIsDisplayed()
        ui.onNodeWithTag("download-search").performTextClearance();ui.onNodeWithTag("download-search").performTextInput("迪士尼")
        ui.onNodeWithTag("download-row-399").assertIsDisplayed()
        ui.onNodeWithTag("download-row-398").assertDoesNotExist()
    }
    @Test fun offlineAndCommitDisableControls() {
        var offline by mutableStateOf(true)
        var value by mutableStateOf(TeamDownloadState("synthetic",rows,paused=true))
        ui.setContent { MaterialTheme { Surface { TeamDownloadPanel(value,offline,false,{fail("Cannot pause commit")},{fail("Cannot resume offline/active")}) } } }
        ui.onNodeWithTag("download-resume").assertIsNotEnabled()
        ui.runOnIdle { offline=false;value=value.copy(paused=false,active=true,committing=true) }
        ui.onNodeWithTag("download-pause").assertIsNotEnabled();ui.onNodeWithTag("download-resume").assertIsNotEnabled()
    }
}
