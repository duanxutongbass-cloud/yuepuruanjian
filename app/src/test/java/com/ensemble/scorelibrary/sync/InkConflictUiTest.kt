package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import com.ensemble.scorelibrary.account.TeamMember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.annotation.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkConflictUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun explicitChoiceRequired() {
        val local=listOf(InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.3f),InkPoint(.7f,.5f))))
        val remote=listOf(InkMark(kind=MarkKind.SYMBOL,points=listOf(InkPoint(.6f,.6f)),symbol="ff"))
        var choice:Boolean?=null
        ui.setContent {MaterialTheme {InkConflictPanel(InkConflict(local,remote,2,3,"personal"),true) {choice=it}}}
        ui.onNodeWithTag("ink-conflict-panel").assertExists();assertNull(choice)
        ui.onNodeWithTag("ink-choose-local").performClick();assertNull(choice)
        ui.onNodeWithText("继续保留双方").performClick();assertNull(choice)
        ui.onNodeWithTag("ink-choose-cloud").performClick();ui.onNodeWithText("确认选择").performClick()
        assertEquals(false,choice)
        ui.waitForIdle()
        lateinit var bitmap:android.graphics.Bitmap
        ui.runOnUiThread {
            val view=ui.activity.window.decorView
            bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val target=java.io.File("build/reports/screenshots/ink-conflict-choice.png");target.parentFile!!.mkdirs()
        target.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun adminReadOnly() {
        val score=InkBackupScore("artifact","hash","001 Synthetic · Violin",2)
        val member=TeamMember("member","Player","player",true,false)
        var request:Triple<String,String,Int>?=null
        ui.setContent { MaterialTheme {Column { AdminInkBackupPanel(InkToolsState(key="test",scores=listOf(score)),listOf(member)) {a,s,p->request=Triple(a,s.artifact,p)} }} }
        ui.onNodeWithTag("admin-read-personal-ink").assertIsNotEnabled()
        ui.onNodeWithText("选择成员").performClick();ui.onNodeWithText("Player · player").performClick()
        ui.onNodeWithText("选择已授权分谱").performClick();ui.onNodeWithText(score.title).performClick()
        ui.onNodeWithTag("admin-read-personal-ink").performClick()
        assertEquals(Triple("member","artifact",0),request)
        ui.onAllNodesWithText("保存").assertCountEquals(0)
    }
}
