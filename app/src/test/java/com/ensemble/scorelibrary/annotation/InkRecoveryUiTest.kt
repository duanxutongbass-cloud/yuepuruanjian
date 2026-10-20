package com.ensemble.scorelibrary.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers="w800dp-h1200dp-mdpi")
class InkRecoveryUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val source=RecoverySource("local","a".repeat(64))
    private val conflict=InkRecoveryConflict(listOf(InkMark(kind=MarkKind.SYMBOL,points=listOf(InkPoint(.2f,.3f)),symbol="fermata",symbolScale=1.5f)),
        listOf(InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.6f),InkPoint(.8f,.6f)))),1,source)
    private var selected:Boolean?=null
    private fun open() {
        compose.setContent { MaterialTheme { InkRecoveryPanel(conflict,true) { selected=it } } }
        compose.onNodeWithTag("ink-recovery-compare").performClick()
    }
    @Test fun localChoiceNeedsConfirmationAndRealDialogRenders() {
        open()
        compose.onNodeWithText("比较两份笔迹").assertExists()
        compose.waitForIdle()
        lateinit var bitmap:Bitmap
        compose.runOnUiThread {
            val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        }
        val colors=mutableSetOf<Int>()
        for(y in 0 until bitmap.height step 4)for(x in 0 until bitmap.width step 4)colors+=bitmap.getPixel(x,y)
        assertTrue(colors.size>3)
        File("build/reports/screenshots/ink-recovery-choice.png").also { it.parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        }
        compose.onNodeWithTag("ink-recovery-local").performClick();assertNull(selected)
        compose.onNodeWithTag("ink-recovery-confirm").performClick();assertEquals(true,selected)
    }
    @Test fun savedChoiceAlsoRequiresConfirmationAndDismissKeepsBoth() {
        open();compose.onNodeWithTag("ink-recovery-saved").performClick()
        assertNull(selected);compose.onNodeWithText("继续比较").performClick();assertNull(selected)
        compose.onNodeWithTag("ink-recovery-saved").performClick()
        compose.onNodeWithTag("ink-recovery-confirm").performClick();assertEquals(false,selected)
    }
    @Test fun routineDurabilityAcknowledgementsNeverChangeToolbarBounds() {
        val data=AnnotationData(emptyList(),emptyList(),emptyList(),0,0,true,null,false)
        val state=mutableStateOf(AnnotationState(data=data))
        val model=AnnotationViewModel(object:AnnotationStore {
            override suspend fun load(scope:AnnotationScope)=data
            override suspend fun save(scope:AnnotationScope,layer:AnnotationLayer,marks:List<InkMark>,generation:Long) {}
            override suspend fun publish(scope:AnnotationScope) {}
            override suspend fun freeze(scope:AnnotationScope) {}
        })
        compose.setContent { MaterialTheme { Box(Modifier.testTag("durable-toolbar")) { AnnotationToolbar(state.value,model) } } }
        val bounds=compose.onNodeWithTag("durable-toolbar").fetchSemanticsNode().boundsInRoot
        repeat(6) { i ->
            compose.runOnIdle { state.value=state.value.copy(pending=true,saving=true,recoveryDurable=i%2==0) }
            compose.onNodeWithTag("annotation-pending").assertDoesNotExist()
            assertEquals(bounds,compose.onNodeWithTag("durable-toolbar").fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { state.value=state.value.copy(saving=false,error="保存失败",recoveryDurable=true) }
        compose.onNodeWithText("批注尚未保存，恢复副本已加密保留在本机；请重试或处理冲突").assertExists()
        compose.runOnIdle { state.value=state.value.copy(recoveryDurable=false) }
        compose.onNodeWithText("批注尚未安全保存，仅在当前进程内保留；请重试，关闭应用可能丢失").assertExists()
    }
}
