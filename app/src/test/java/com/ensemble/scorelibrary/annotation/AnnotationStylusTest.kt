package com.ensemble.scorelibrary.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.ScoreTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w800dp-h1200dp-mdpi")
class AnnotationStylusTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val scope = AnnotationScope("m", "p", "part", "v", 0)
    private val data = AnnotationData(emptyList(), emptyList(), emptyList(), 0, 0, true, null, false)
    private val saved = SavedStateHandle()
    private val repository = object : AnnotationStore {
        override suspend fun load(scope: AnnotationScope) = data
        override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) = Unit
        override suspend fun publish(scope: AnnotationScope) = Unit
        override suspend fun freeze(scope: AnnotationScope) = Unit
    }
    private val model = AnnotationViewModel(repository, saved = saved)
    private lateinit var view: View
    private var time = 20L
    private var taps = 0
    private data class P(val id: Int, val type: Int, val x: Float = 100f, val y: Float = 100f,val pressure:Float=1f)
    private val pen = P(7, MotionEvent.TOOL_TYPE_STYLUS)
    private val palm = P(3, MotionEvent.TOOL_TYPE_FINGER, 250f, 250f)
    @After fun close() { models.clear() }
    private fun open(mode: InkInputMode = InkInputMode.PEN) {
        models.put("ink", model)
        compose.setContent {
            view = LocalView.current
            val state by model.state.collectAsState()
            ScoreTheme { Surface { Column {
                AnnotationToolbar(state, model)
                Box(Modifier.size(400.dp)) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { taps++ } })
                    AnnotationOverlay(PdfPageTransform.fit(PageSize(400, 400), 400, 400, FitMode.PAGE), state, model)
                }
            } } }
        }
        compose.runOnIdle { model.bind(scope) }
        compose.waitUntil { !model.state.value.busy }
        compose.runOnIdle { model.inputMode(mode); model.tool(AnnotationTool.PEN) }
        compose.waitForIdle()
    }
    private fun send(action: Int, vararg pointers: P, index: Int = 0, flags: Int = 0,buttons:Int=0) {
        val bounds = compose.onNodeWithTag("annotation-canvas").fetchSemanticsNode().boundsInRoot
        val event = MotionEvent.obtain(20L, time.also { time += 16 }, action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            pointers.size, pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.type } }.toTypedArray(),
            pointers.map { p -> MotionEvent.PointerCoords().apply { x = p.x + bounds.left; y = p.y + bounds.top; pressure = p.pressure; size = .1f } }.toTypedArray(),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags)
        compose.runOnUiThread { try { view.dispatchTouchEvent(event) } finally { event.recycle() } }
    }
    @Test fun nativePressurePenIsOneUndoableStrokeAndSurvivesEncoding() {
        open();compose.runOnIdle { model.pressure(true);model.width(.01f) }
        send(MotionEvent.ACTION_DOWN,pen.copy(pressure=.1f))
        send(MotionEvent.ACTION_MOVE,pen.copy(x=180f,pressure=.4f))
        send(MotionEvent.ACTION_MOVE,pen.copy(x=280f,pressure=.9f))
        send(MotionEvent.ACTION_UP,pen.copy(x=280f,pressure=0f))
        compose.waitUntil { !model.state.value.pending }
        val mark=model.state.value.marks.single()
        assertEquals(listOf(.1f,.4f,.9f),mark.pressures)
        assertEquals(mark,InkCodec.decode(InkCodec.encode(listOf(mark))).single())
        compose.runOnIdle { model.undo() };compose.waitUntil { !model.state.value.pending };assertTrue(model.state.value.marks.isEmpty())
        compose.runOnIdle { model.redo() };compose.waitUntil { !model.state.value.pending };assertEquals(mark,model.state.value.marks.single())
    }
    @Test fun nativeSideButtonErasesWithoutChangingBrushAndUndoRestoresPressure() {
        open();compose.runOnIdle { model.pressure(true);model.sideButton(InkSideButton.PRIMARY) }
        send(MotionEvent.ACTION_DOWN,pen.copy(pressure=.2f));send(MotionEvent.ACTION_UP,pen.copy(x=200f,pressure=.8f))
        compose.waitUntil { !model.state.value.pending };val mark=model.state.value.marks.single()
        send(MotionEvent.ACTION_DOWN,pen.copy(x=150f),buttons=MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_UP,pen.copy(x=150f))
        compose.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            model.state.value.marks.isEmpty() && !model.state.value.busy && !model.state.value.pending }
        assertEquals(AnnotationTool.PEN,model.state.value.tool)
        compose.runOnIdle { model.undo() };compose.waitUntil { !model.state.value.pending };assertEquals(mark,model.state.value.marks.single())
        compose.runOnIdle { model.toggleEditLock() }
        send(MotionEvent.ACTION_DOWN,pen.copy(x=150f),buttons=MotionEvent.BUTTON_STYLUS_PRIMARY);send(MotionEvent.ACTION_UP,pen.copy(x=150f))
        assertEquals(listOf(mark),model.state.value.marks)
    }
    @Test fun nativePenWithCanceledPalmWritesOnlyPenAndDoesNotNavigate() {
        open()
        send(MotionEvent.ACTION_DOWN, pen)
        send(MotionEvent.ACTION_POINTER_DOWN, pen, palm, index = 1)
        send(MotionEvent.ACTION_MOVE, pen.copy(x = 200f), palm)
        send(MotionEvent.ACTION_POINTER_UP, pen.copy(x = 200f), palm, index = 1, flags = MotionEvent.FLAG_CANCELED)
        send(MotionEvent.ACTION_UP, pen.copy(x = 300f))
        compose.waitUntil { !model.state.value.pending }
        assertEquals(listOf(.25f, .5f, .75f), model.state.value.marks.single().points.map { it.x })
        assertFalse(model.state.value.gestureActive); assertNull(model.state.value.inputNotice); assertEquals(0, taps)
    }
    @Test fun ignoredPalmCanPrecedeTwoNativePenStrokes() {
        open(); send(MotionEvent.ACTION_DOWN, palm)
        repeat(2) {
            send(MotionEvent.ACTION_POINTER_DOWN, palm, pen, index = 1)
            send(MotionEvent.ACTION_POINTER_UP, palm, pen.copy(x = 200f), index = 1)
        }
        send(MotionEvent.ACTION_UP, palm)
        compose.waitUntil { !model.state.value.pending }
        assertEquals(2, model.state.value.marks.size); assertEquals(0, taps)
    }
    @Test fun nativeCancelAndFlaggedPenReleaseCannotSaveUnfinishedInk() {
        open()
        send(MotionEvent.ACTION_DOWN, pen); send(MotionEvent.ACTION_UP, pen)
        send(MotionEvent.ACTION_DOWN, pen); send(MotionEvent.ACTION_CANCEL, pen)
        send(MotionEvent.ACTION_DOWN, pen); send(MotionEvent.ACTION_UP, pen, flags = MotionEvent.FLAG_CANCELED)
        assertEquals(1, model.state.value.marks.size); assertFalse(model.state.value.gestureActive)
        assertTrue(model.state.value.inputNotice!!.contains("取消"))
    }
    @Test fun penModeBlocksFingerInkAndExplicitReadRestoresFingerNavigation() {
        open()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { click() }
        assertTrue(model.state.value.marks.isEmpty()); assertEquals(0, taps)
        compose.runOnIdle { model.tool(AnnotationTool.READ) }
        compose.onNodeWithTag("annotation-canvas").performTouchInput { click() }
        assertEquals(1, taps)
    }
    @Test fun hardwareEraserDeletesWholeMarkUndoRestoresAndSelectedBrushDoesNotChange() {
        open()
        send(MotionEvent.ACTION_DOWN, pen); send(MotionEvent.ACTION_UP, pen.copy(x = 200f))
        compose.waitUntil { !model.state.value.pending }
        val eraser = pen.copy(type = MotionEvent.TOOL_TYPE_ERASER, x = 150f)
        send(MotionEvent.ACTION_DOWN, eraser)
        assertTrue("Eraser down: ${model.state.value}", model.state.value.gestureActive)
        send(MotionEvent.ACTION_UP, eraser)
        try { compose.waitUntil(timeoutMillis = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            model.state.value.marks.isEmpty() && !model.state.value.busy && !model.state.value.pending
        } }
        catch (e: ComposeTimeoutException) { throw AssertionError("Eraser result: ${model.state.value}", e) }
        assertEquals(AnnotationTool.PEN, model.state.value.tool)
        compose.runOnIdle { model.undo() }
        compose.waitUntil { !model.state.value.pending }
        assertEquals(1, model.state.value.marks.size)
    }
    @Test fun modeSelectionIsVisibleKeepsPageSizeAndDoesNotSwitchDuringStroke() {
        open(InkInputMode.TOUCH)
        val bounds = compose.onNodeWithTag("annotation-canvas").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("ink-input-mode").performScrollTo().performClick()
        compose.onNodeWithTag("ink-input-PEN").performClick()
        assertEquals(InkInputMode.PEN, model.state.value.inputMode)
        assertEquals(bounds, compose.onNodeWithTag("annotation-canvas").fetchSemanticsNode().boundsInRoot)
        send(MotionEvent.ACTION_DOWN, pen)
        compose.onNodeWithTag("ink-input-mode").assertIsNotEnabled()
        compose.runOnIdle { model.inputMode(InkInputMode.TOUCH) }
        assertEquals(InkInputMode.PEN, model.state.value.inputMode)
        send(MotionEvent.ACTION_UP, pen)
        compose.waitUntil { !model.state.value.pending }
        compose.onNodeWithTag("ink-input-mode").performClick()
        screenshot()
    }
    @Test fun modeSurvivesPageAccountBindingAndSavedStateRecreation() {
        open()
        compose.runOnIdle { model.bind(scope.copy(page = 1)) }
        compose.waitUntil { !model.state.value.busy }
        assertEquals(InkInputMode.PEN, model.state.value.inputMode)
        compose.runOnIdle { model.bind(scope.copy(member = "other")) }
        compose.waitUntil { !model.state.value.busy }
        assertEquals(InkInputMode.PEN, model.state.value.inputMode)
        val recreated = AnnotationViewModel(repository, saved = SavedStateHandle(mapOf("ink-input-mode" to saved.get<String>("ink-input-mode"))))
        models.put("recreated", recreated)
        assertEquals(InkInputMode.PEN, recreated.state.value.inputMode)
    }
    @Test fun eraserCannotBypassReadOrEditLock() {
        open()
        send(MotionEvent.ACTION_DOWN, pen); send(MotionEvent.ACTION_UP, pen)
        compose.waitUntil { !model.state.value.pending }
        compose.runOnIdle {
            model.tool(AnnotationTool.READ); model.stroke(listOf(InkPoint(.25f,.25f)), hardwareEraser = true)
            model.toggleEditLock(); model.tool(AnnotationTool.PEN)
            model.stroke(listOf(InkPoint(.25f,.25f)), hardwareEraser = true)
        }
        assertEquals(1, model.state.value.marks.size)
    }
    private fun screenshot() {
        compose.waitForIdle()
        compose.runOnUiThread {
            val decor = view.rootView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File("build/reports/screenshots/stylus-input.png").also { it.parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }
}
