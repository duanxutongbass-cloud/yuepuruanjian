package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import com.ensemble.scorelibrary.domain.VisiblePart
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real tablet/native PDF; isolated fixture and ink, without touching account or library data. */
@RunWith(AndroidJUnit4::class)
class Release0118DeviceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val renders = AtomicInteger()
    private val writes = AtomicInteger()
    private val records = ConcurrentHashMap<AnnotationScope, AnnotationData>()
    private val repository = object : AnnotationStore {
        override suspend fun load(scope: AnnotationScope) = records[scope] ?: AnnotationData(emptyList(), emptyList(), emptyList(), 0, 0, false, null, false)
        override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) {
            val before = load(scope)
            records[scope] = before.copy(personal = marks, personalGeneration = generation + 1)
            writes.incrementAndGet()
        }
        override suspend fun publish(scope: AnnotationScope) { error("Fixture never publishes") }
        override suspend fun freeze(scope: AnnotationScope) { error("Fixture never freezes a performance") }
    }
    private val ink = AnnotationViewModel(repository)
    private lateinit var pdf: PdfReaderViewModel
    private lateinit var file: File
    @Volatile private var renderDelay = 0L

    private fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.filesDir, "qa-0118-${UUID.randomUUID()}.pdf")
        val hash = PdfFixture.write(file)
        val engine = NativePdfEngine(context)
        val counted = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>) = engine.inspect(segments)
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
                renders.incrementAndGet(); delay(renderDelay)
                return engine.render(page, width, height, mode, scroll)
            }
            override suspend fun renderView(page: PdfPageRef, width: Int, height: Int, mode: FitMode, view: ReaderView): PdfFrame {
                renders.incrementAndGet(); delay(renderDelay)
                return engine.renderView(page, width, height, mode, view)
            }
        }
        pdf = PdfReaderViewModel(counted, SavedStateHandle(), PerformancePreparation(counted, repository))
        store.put("pdf", pdf); store.put("ink", ink)
        val score = ResolvedConcertItem("qa-0118", "qa-piece", "0.10.18 native tablet acceptance",
            VisiblePart("qa-part", "violin", "Violin I"), listOf(PdfFixture.segment(file, hash)))
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAnnotations provides ink) {
            PdfReaderScreen("qa-isolated-member", score, pdf)
        } } }
        settled()
        assertEquals(hash, PdfFixture.hash(file))
    }

    private fun settled() {
        compose.waitUntil(20_000) { (pdf.state.value as? PdfState.Ready)?.rendering == false && ink.state.value.data != null && !ink.state.value.busy }
        compose.waitForIdle()
    }

    private fun editing() {
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        settled()
        compose.onNodeWithTag("reader-controls").assertExists()
    }

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(compose.activity.filesDir, "qa-0118-results").also { it.mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After fun close() {
        compose.runOnUiThread { store.clear() }
        if (::file.isInitialized) file.delete()
    }

    @Test fun nativeStampPaletteRepeatedOpeningKeepsPageAndRenderCountStable() {
        open(); editing()
        val page = compose.onNodeWithTag("pdf-page").fetchSemanticsNode().boundsInRoot
        val count = renders.get()
        repeat(8) { index ->
            compose.onNodeWithTag("music-symbol-picker").performClick()
            compose.onNodeWithTag("music-symbol-palette").assertIsDisplayed()
            assertEquals(page, compose.onNodeWithTag("pdf-page").fetchSemanticsNode().boundsInRoot)
            Thread.sleep(150)
            assertEquals("Opening symbol palette must not rerender native PDF", count, renders.get())
            if (index == 0) screenshot("stamp-palette")
            compose.onNodeWithText("收起符号").performClick()
            compose.onNodeWithTag("music-symbol-palette").assertDoesNotExist()
        }
        assertEquals(count, renders.get())
        assertTrue(ink.state.value.marks.isEmpty())
    }

    @Test fun delayedNativePageTurnKeepsVisiblePixelsUntilReplacement() {
        open()
        repeat(4) { index ->
            renderDelay = 650
            compose.runOnUiThread { if (index % 2 == 0) pdf.nextPage() else pdf.previousPage() }
            compose.waitUntil(5_000) { (pdf.state.value as? PdfState.Ready)?.rendering == true }
            val previous = (pdf.state.value as PdfState.Ready).frame
            repeat(5) {
                val state = pdf.state.value
                assertTrue("Turn must retain a visible page", state is PdfState.Ready)
                if ((state as PdfState.Ready).rendering) assertSame(previous, state.frame)
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                assertNotNull(image)
                var light = 0; var total = 0
                for (y in image.height / 3 until image.height * 2 / 3 step 12) {
                    for (x in image.width / 3 until image.width * 2 / 3 step 12) {
                        val pixel = image.getPixel(x, y)
                        if (Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180) light++
                        total++
                    }
                }
                image.recycle()
                assertTrue("Screen center must not become black while rendering", light > total * .6)
                Thread.sleep(35)
            }
            settled()
            assertEquals(if (index % 2 == 0) 3 else 2, (pdf.state.value as PdfState.Ready).sourcePage)
        }
        screenshot("page-after-turns")
    }

    @Test fun actualTouchStrokesStayOnNativePageAndPersistWithoutPdfRenders() {
        open(); editing()
        compose.runOnUiThread { ink.tool(AnnotationTool.PEN); ink.inputMode(InkInputMode.TOUCH) }
        compose.waitForIdle()
        val count = renders.get()
        val durations = mutableListOf<Long>()
        repeat(20) { index ->
            val started = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithTag("annotation-canvas").performTouchInput {
                val y = height * (.25f + (index % 10) * .04f)
                swipe(Offset(width * .3f, y), Offset(width * .65f, y + height * .01f), durationMillis = 160)
            }
            compose.waitUntil(5_000) { ink.state.value.marks.size == index + 1 }
            durations += android.os.SystemClock.elapsedRealtime() - started
        }
        compose.waitUntil(10_000) { !ink.state.value.pending && !ink.state.value.busy }
        assertEquals(20, ink.state.value.marks.size)
        assertTrue(ink.state.value.marks.all { it.points.size > 1 })
        assertEquals(20, records.values.single().personal.size)
        assertTrue(writes.get() > 0)
        assertEquals("Drawing ink must not rerender the underlying native PDF", count, renders.get())
        screenshot("twenty-touch-strokes")
        val folder = File(compose.activity.filesDir, "qa-0118-results").also { it.mkdirs() }
        File(folder, "touch-observations.json").writeText(org.json.JSONObject()
            .put("touchStrokes", 20).put("droppedStrokes", 0).put("nativePdfRendersDuringStrokes", renders.get() - count)
            .put("gestureHarnessDurationsMs", org.json.JSONArray(durations)).put("physicalStylusTested", false).toString())
    }
}
