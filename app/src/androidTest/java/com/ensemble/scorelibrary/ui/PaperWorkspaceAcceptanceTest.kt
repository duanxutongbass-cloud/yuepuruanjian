package com.ensemble.scorelibrary.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Synthetic projection + real PdfRenderer. No production identity or user content. */
@RunWith(AndroidJUnit4::class)
class PaperWorkspaceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private var fixture: File? = null
    @After fun close() { store.clear(); fixture?.delete() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.filesDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    }
    private fun runSlice(width: Int, height: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("wm size ${width}x${height}", "wm density 160").forEach { cmd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "paper-synthetic.pdf").also { fixture = it }
        val hash = PdfFixture.write(file)
        val part = VisiblePart("part", "d", "合成声部 I")
        val item = ResolvedConcertItem("item", "p1", "合成练习曲 1", part, listOf(PdfFixture.segment(file, hash)))
        val concert = ResolvedConcert("c", "合成排练节目单", listOf(item))
        val dashboard = Dashboard(listOf(MemberChoice("m", "合成成员")), Profile("m", "合成成员", part.name, false),
            (1..8).map { PieceSummary("p$it", "合成练习曲 $it", "测试作曲 $it") }, listOf(ConcertSummary("c", concert.title)))
        val repository = object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = flowOf(DomainResult.Success(Presentation(dashboard,
                DomainResult.Success(when (request) {
                    ScreenRequest.Home -> ScreenContent.Home
                    is ScreenRequest.Piece -> ScreenContent.Piece("p1", item.title, listOf(part))
                    is ScreenRequest.Concert -> ScreenContent.Concert(concert)
                    is ScreenRequest.Viewer -> ScreenContent.Viewer(item, if (request.concertId == null) null else concert)
                }))))
        }
        val device = object : DeviceAvailability {
            override val online = flowOf(false)
            override suspend fun filesAvailable(segments: List<ResolvedScoreSegment>) = true
        }
        val vm = LibraryViewModel(repository, SavedStateHandle(mapOf("memberId" to "m")), device).also { store.put("library", it) }
        val engine = NativePdfEngine(context)
        val pdf = PdfReaderViewModel(engine, SavedStateHandle(), PerformancePreparation(engine, UnusedPreparationAnnotations())).also { store.put("pdf", it) }
        compose.setContent { ScoreTheme { ScoreLibraryApp(vm, pdf) } }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("piece-p1").fetchSemanticsNodes().isNotEmpty() }
        capture("paper-library-$width")
        compose.onNodeWithTag("library-search").performTextInput("练习曲 1")
        compose.onNodeWithTag("piece-p2").assertDoesNotExist()
        compose.onNodeWithTag("piece-p1").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.waitUntil(60_000) { pdf.state.value is PdfState.Ready || pdf.state.value is PdfState.Failure }
        Assert.assertTrue(pdf.state.value is PdfState.Ready)
        compose.onNodeWithTag("side-navigation").assertDoesNotExist()
        capture("paper-reader-$width")
        compose.onNodeWithTag("reader-fullscreen").performClick()
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("part-part").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("concert-c").assertExists()
        capture("paper-concerts-$width")
        compose.onNodeWithTag("concert-c").performClick()
        compose.onNodeWithTag("concert-item-item").performClick()
        compose.waitUntil(60_000) { pdf.state.value is PdfState.Ready }
        compose.onNodeWithTag("play-mode").assertIsDisplayed()
    }
    @Test fun portraitTablet() = runSlice(800, 1200)
    @Test fun largePortraitTablet() = runSlice(1280, 1800)
}
