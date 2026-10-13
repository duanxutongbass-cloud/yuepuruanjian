package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import com.ensemble.scorelibrary.domain.VisiblePart
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 31)
class PdfReaderScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private var file: File? = null
    @After fun close() { store.clear(); file?.delete() }
    private fun open(width: Int, height: Int): PdfReaderViewModel {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("wm size ${width}x${height}")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("wm density 160")).use { it.readBytes() }
        compose.activityRule.scenario.recreate()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.filesDir, "reader-ui.pdf").also { file = it }
        val hash = PdfFixture.write(fixture)
        val score = ResolvedConcertItem("i", "p", "Ensemble", VisiblePart("part", "violin", "Violin I"), listOf(PdfFixture.segment(fixture, hash)))
        val engine = NativePdfEngine(context)
        val model = PdfReaderViewModel(engine, SavedStateHandle(), PerformancePreparation(engine, UnusedPreparationAnnotations()))
        store.put("reader", model)
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { PdfReaderScreen("member", score, model) } } }
        compose.waitUntil(15_000) { model.state.value is PdfState.Ready || model.state.value is PdfState.Failure }
        assertTrue("PDF must be ready: ${model.state.value}", model.state.value is PdfState.Ready)
        return model
    }
    private fun screenshot(name: String, model: PdfReaderViewModel) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        var dark = 0
        for (y in 150 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            if (android.graphics.Color.red(bitmap.getPixel(x, y)) < 100) dark++
        }
        assertTrue("Screenshot must contain rendered PDF content", dark > 50)
        val folder = File(compose.activity.filesDir, "pdf-test-screenshots").also { it.mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw(
            "dd of=/data/local/tmp/phase4-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        val ready = model.state.value as PdfState.Ready
        assertEquals(ready.frame.transform.viewportWidth, ready.frame.bitmap.width)
    }
    @Test fun standardPadReadsAndTurnsOnlyAuthorizedPages() {
        val model = open(800, 1200)
        compose.onNodeWithTag("previous-page").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-page").assertExists()
        screenshot("pdf-standard-pad", model)
        compose.onNodeWithTag("next-page").performClick()
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.sourcePage == 3 }
        compose.onNodeWithTag("next-page").assertIsNotEnabled()
        compose.onNodeWithTag("reader-page-number").assertTextContains("PDF 第 3 页", substring = true)
    }
    @Test fun fullscreenAndSwipeAreIndependentOfPerformanceAndZoomRendersNativePixels() {
        val model = open(800, 1200)
        compose.onNodeWithTag("reader-fullscreen").performClick()
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.frame?.transform?.viewportHeight?.let { it > 1100 } == true }
        compose.onNodeWithTag("next-page").assertDoesNotExist()
        compose.onNodeWithTag("play-mode").assertDoesNotExist()
        assertFalse(model.playMode.value)
        screenshot("interaction-fullscreen", model)
        compose.onNodeWithTag("pdf-page").performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.sourcePage == 3 }
        compose.onNodeWithTag("pdf-page").performTouchInput { swipeRight(durationMillis = 400) }
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.sourcePage == 2 }
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.onNodeWithTag("reader-fullscreen").assertExists()
        compose.waitUntil(15_000) { model.state.value is PdfState.Ready }
        val old = (model.state.value as PdfState.Ready).frame.transform.scale
        compose.onNodeWithTag("reader-zoom-in").performClick()
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.frame?.transform?.scale?.let { it > old * 1.2f } == true }
        val frame = (model.state.value as PdfState.Ready).frame
        assertEquals(frame.transform.viewportWidth, frame.bitmap.width)
        assertEquals(frame.transform.viewportHeight, frame.bitmap.height)
        compose.onNodeWithTag("pdf-page").performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(2, (model.state.value as PdfState.Ready).sourcePage)
        compose.onNodeWithTag("reader-zoom-reset").performClick()
        compose.waitUntil(15_000) { model.zoom() == 1f && (model.state.value as? PdfState.Ready)?.frame?.transform?.scale?.let { kotlin.math.abs(it - old) < .01f } == true }
    }
    @Test fun largePadTwoFingerZoomKeepsPageAndFullscreenHasNoToolbar() {
        val model = open(1280, 1800)
        compose.onNodeWithTag("reader-fullscreen").performClick()
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.frame?.transform?.viewportHeight?.let { it > 1700 } == true }
        val before = (model.state.value as PdfState.Ready).frame.transform.scale
        compose.onNodeWithTag("pdf-page").performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(width * .4f, height * .5f))
            down(1, androidx.compose.ui.geometry.Offset(width * .6f, height * .5f))
            moveTo(0, androidx.compose.ui.geometry.Offset(width * .2f, height * .5f), 100)
            moveTo(1, androidx.compose.ui.geometry.Offset(width * .8f, height * .5f), 100)
            up(0); up(1)
        }
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.frame?.transform?.scale?.let { it > before * 1.5f } == true }
        assertEquals(2, (model.state.value as PdfState.Ready).sourcePage)
        compose.onNodeWithTag("reader-fullscreen").assertDoesNotExist()
        screenshot("interaction-fullscreen-large-zoom", model)
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.onNodeWithTag("reader-zoom-reset").assertExists()
    }
    @Test fun largePadRendersAndWidthModeCanPanWithoutChangingSourcePage() {
        val model = open(1280, 1800)
        screenshot("pdf-large-pad", model)
        compose.onNodeWithText("适合宽度").performClick()
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.mode == FitMode.WIDTH }
        compose.onNodeWithTag("pdf-page").performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitUntil(15_000) { (model.state.value as? PdfState.Ready)?.frame?.transform?.offsetY?.let { it < 0 } == true }
        assertEquals(2, (model.state.value as PdfState.Ready).sourcePage)
    }
}
