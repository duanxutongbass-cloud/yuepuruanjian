package com.ensemble.scorelibrary.annotation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class AnnotationGestureTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val release = CompletableDeferred<Unit>()
    private var fail = true
    private var persisted = emptyList<InkMark>()
    private val scope = AnnotationScope("m", "p", "part", "v", 0)
    private val model = AnnotationViewModel(object : AnnotationStore {
        override suspend fun load(scope: AnnotationScope) = AnnotationData(emptyList(), emptyList(), emptyList(), 0, 0, true, null, false)
        override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) {
            release.await(); check(!fail) { "保存失败" }; persisted = marks
        }
        override suspend fun publish(scope: AnnotationScope) = Unit
        override suspend fun freeze(scope: AnnotationScope) = Unit
    })
    private val transform = mutableStateOf(PdfPageTransform.fit(PageSize(400, 400), 400, 400, FitMode.PAGE))
    @After fun close() { store.clear() }
    private fun open() {
        store.put("ink", model)
        compose.setContent {
            val state by model.state.collectAsState()
            MaterialTheme { Box(Modifier.size(400.dp)) { AnnotationOverlay(transform.value, state, model) } }
        }
        compose.runOnIdle { model.bind(scope) }
        compose.waitUntil { !model.state.value.busy }
        compose.runOnIdle { model.tool(AnnotationTool.PEN) }
    }
    @Test fun saveFailureDuringSecondPointerGestureKeepsBothCompletedStrokes() {
        open()
        val canvas = compose.onNodeWithTag("annotation-canvas")
        canvas.performTouchInput { swipe(Offset(80f, 100f), Offset(300f, 100f)) }
        compose.waitUntil { model.state.value.saving }
        canvas.performTouchInput { down(Offset(80f, 200f)); moveTo(Offset(180f, 200f)) }
        compose.runOnIdle { assertTrue(model.state.value.gestureActive); release.complete(Unit) }
        compose.waitUntil { model.state.value.error != null }
        canvas.performTouchInput { moveTo(Offset(300f, 200f)); up() }
        compose.runOnIdle {
            assertEquals(2, model.state.value.marks.size)
            assertTrue(model.state.value.pending); assertFalse(model.state.value.gestureActive)
            fail = false; model.retry()
        }
        compose.waitUntil { !model.state.value.pending }
        assertEquals(2, persisted.size)
    }
    @Test fun transformCancellationExplainsUnfinishedStrokeAndRetainsCompletedInk() {
        open()
        val canvas = compose.onNodeWithTag("annotation-canvas")
        canvas.performTouchInput { swipe(Offset(80f, 100f), Offset(300f, 100f)) }
        canvas.performTouchInput { down(Offset(80f, 200f)); moveTo(Offset(180f, 200f)) }
        compose.runOnIdle { assertTrue(model.state.value.gestureActive); transform.value = PdfPageTransform.fit(PageSize(400, 400), 420, 400, FitMode.PAGE) }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull("State after transform: ${model.state.value}", model.state.value.inputNotice) }
        canvas.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, model.state.value.marks.size)
            assertFalse(model.state.value.gestureActive)
            assertTrue(model.state.value.inputNotice!!.contains("取消"))
        }
    }
    @Test fun secondPointerCancelsExplicitlyInsteadOfSavingPartialStroke() {
        open()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            down(0, Offset(80f, 100f)); moveTo(0, Offset(180f, 100f))
            down(1, Offset(90f, 200f)); moveTo(1, Offset(190f, 200f)); up(1); up(0)
        }
        compose.runOnIdle {
            assertTrue(model.state.value.marks.isEmpty())
            assertNotNull(model.state.value.inputNotice); assertFalse(model.state.value.gestureActive)
        }
    }
}
