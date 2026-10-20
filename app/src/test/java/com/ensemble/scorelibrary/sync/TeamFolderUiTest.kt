package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.ui.LibraryFolder
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamFolderUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val preview=FolderComparison("member",listOf(LibraryFolder(id="f",name="本机本周排练",pieces=setOf("p"),coverPiece="p")),
        listOf(LibraryFolder(id="f",name="云端下周演出",pieces=setOf("p","q"))),"local",2,"remote")
    @Test fun actions() {
        var status by mutableStateOf(FolderSyncState("member",pending=true,conflict=true,message="等待选择"));var sync=0;var show=0
        ui.setContent { MaterialTheme { FolderSyncActions(status,false,"",{sync++},{show++}) } }
        ui.onNodeWithText("只有本人账号能在其他设备获取",substring=true).assertIsDisplayed()
        ui.onNodeWithTag("folder-sync-now").performClick();ui.onNodeWithTag("folder-sync-preview").performClick()
        assertEquals(1,sync);assertEquals(1,show)
        ui.runOnIdle { status=status.copy(busy=true) }
        ui.onNodeWithTag("folder-sync-now").assertIsNotEnabled();ui.onNodeWithTag("folder-sync-preview").assertIsNotEnabled()
    }
    @Test fun explicitChoices() {
        var local=0;var remote=0;var dismiss=0
        ui.setContent { MaterialTheme { FolderConflictDialog(preview,true,"",{local++},{remote++},{dismiss++}) } }
        assertEquals(0,local);assertEquals(0,remote)
        ui.onNodeWithText("本机本周排练").assertIsDisplayed();ui.onNodeWithText("云端下周演出").assertIsDisplayed()
        ui.onNodeWithText("本次选择整套文件夹",substring=true).assertIsDisplayed()
        screenshot()
        ui.onNodeWithTag("folder-conflict-local").performClick();assertEquals(1,local)
        ui.onNodeWithTag("folder-conflict-remote").performClick();assertEquals(1,remote)
        ui.onNodeWithText("暂不处理",useUnmergedTree=true).performClick();assertEquals(1,dismiss)
    }
    @Test fun stalePreviewMessage() {
        ui.setContent { MaterialTheme { FolderConflictDialog(preview,false,"记录已变化，请重新查看",{fail("Busy")},{fail("Busy")},{}) } }
        ui.onNodeWithTag("folder-conflict-local").assertIsNotEnabled();ui.onNodeWithTag("folder-conflict-remote").assertIsNotEnabled()
        ui.onNodeWithText("记录已变化，请重新查看").assertIsDisplayed()
    }
    private fun screenshot() {
        ui.waitForIdle();lateinit var bitmap:android.graphics.Bitmap
        ui.runOnIdle {
            val decor=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            bitmap=android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
        }
        val file=java.io.File("build/reports/screenshots/folder-sync-conflict.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
