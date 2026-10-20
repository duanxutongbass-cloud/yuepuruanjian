package com.ensemble.scorelibrary.annotation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationSettingsTest {
    @get:Rule val compose=createComposeRule()
    private val store=ViewModelStore()
    private val scope=AnnotationScope("m","p","part","v",0)
    private val model=AnnotationViewModel(object:AnnotationStore {
        override suspend fun load(scope:AnnotationScope)=AnnotationData(emptyList(),emptyList(),emptyList(),0,0,true,null,false)
        override suspend fun save(scope:AnnotationScope,layer:AnnotationLayer,marks:List<InkMark>,generation:Long)=Unit
        override suspend fun publish(scope:AnnotationScope)=Unit
        override suspend fun freeze(scope:AnnotationScope)=Unit
    })
    @After fun close(){store.clear()}
    private fun open() {
        store.put("ink",model)
        compose.setContent { val state by model.state.collectAsState();MaterialTheme { AnnotationToolbar(state,model) } }
        compose.runOnIdle { model.bind(scope) };compose.waitUntil { !model.state.value.busy }
    }
    @Test fun penSizeCanBeTappedAndDraggedWithoutScrollingTheToolbar() {
        open();compose.runOnIdle { model.inputMode(InkInputMode.PEN) }
        compose.onNodeWithTag("pen-width-settings").performClick()
        compose.onNodeWithTag("pen-width-粗").performClick()
        compose.runOnIdle { assertEquals(.006f,model.state.value.width) }
        compose.onNodeWithTag("pen-width-plus").performClick()
        compose.runOnIdle { assertEquals(.0065f,model.state.value.width,.00001f) }
        compose.onNodeWithTag("pen-width").performTouchInput { swipe(Offset(width*.2f,centerY),Offset(width*.8f,centerY),400) }
        compose.runOnIdle { assertTrue(model.state.value.width>.008f) }
        compose.onNodeWithTag("pen-width-preview").assertIsDisplayed()
        capture("annotation-0116-pen-width.png")
    }
    @Test fun stylusSettingsAreExplicitPersistAcrossPagesAndCannotChangeDuringGesture() {
        open();compose.onNodeWithTag("stylus-settings").performScrollTo().performClick()
        compose.onNodeWithTag("stylus-pressure").assertIsOff().performClick()
        compose.onNodeWithTag("stylus-side-PRIMARY").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(model.state.value.pressureEnabled);assertEquals(InkSideButton.PRIMARY,model.state.value.sideButton) }
        capture("0127-stylus-settings.png")
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { model.bind(scope.copy(page=1)) };compose.waitUntil { !model.state.value.busy }
        compose.runOnIdle {
            assertTrue(model.state.value.pressureEnabled);assertEquals(InkSideButton.PRIMARY,model.state.value.sideButton)
            model.gesture(model.state.value.scope,true);model.pressure(false);model.sideButton(InkSideButton.OFF)
            assertTrue(model.state.value.pressureEnabled);assertEquals(InkSideButton.PRIMARY,model.state.value.sideButton)
            model.gesture(model.state.value.scope,false)
        }
    }
    @Test fun hairpinControlsSeparateLengthFromOpeningAndDoNotAlterThePenWidth() {
        open();compose.runOnIdle { model.symbol("<") }
        compose.onNodeWithTag("stamp-settings").performClick()
        compose.onNodeWithTag("hairpin-length").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.onNodeWithTag("stamp-size").performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.runOnIdle {
            assertEquals(.4f,model.state.value.hairpinLength);assertEquals(1.5f,model.state.value.symbolScale)
            assertEquals(.003f,model.state.value.width)
        }
        compose.onNodeWithTag("stamp-settings-preview").assertIsDisplayed()
        capture("annotation-0116-hairpin.png")
    }
    private fun capture(name:String) {
        compose.waitForIdle();lateinit var bitmap:Bitmap
        compose.runOnUiThread {
            val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        }
        val colors=mutableSetOf<Int>()
        for(y in 0 until bitmap.height step 4)for(x in 0 until bitmap.width step 4)colors+=bitmap.getPixel(x,y)
        assertTrue(colors.size>3)
        File("build/reports/screenshots/$name").also { it.parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        };bitmap.recycle()
    }
}
