package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class NavigationTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var restoration: StateRestorationTester
    @After fun close() { store.clear() }
    private var preview: ScorePreviewViewModel? = null
    private var ink: com.ensemble.scorelibrary.annotation.AnnotationViewModel? = null
    private fun open(withReader: Boolean = false, withInk: Boolean = false) {
        val part = VisiblePart("part", "d", "Violin I")
        val item = ResolvedConcertItem("item", "piece", "Music", part, if (withReader) listOf(
            ResolvedScoreSegment("binding", null, "pdf", "v", "fixture", "hash", 1, 2,
                com.ensemble.scorelibrary.core.model.CacheStatus.NOT_CACHED)) else emptyList())
        val dashboard = Dashboard(listOf(MemberChoice("member", "Member")), Profile("member", "Member", "Violin I", false),
            listOf(PieceSummary("piece", "Music", null)), listOf(ConcertSummary("concert", "Concert")))
        val repository = object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = flowOf(DomainResult.Success(
                Presentation(dashboard, DomainResult.Success(when (request) {
                    is ScreenRequest.Preview -> ScreenContent.Viewer(item)
                    ScreenRequest.Home -> ScreenContent.Home
                    is ScreenRequest.Piece -> ScreenContent.Piece("piece", "Music", listOf(part))
                    is ScreenRequest.Concert -> ScreenContent.Concert(ResolvedConcert("concert", "Concert", listOf(item)))
                    is ScreenRequest.Viewer -> ScreenContent.Viewer(item)
                }))))
        }
        val device = object : DeviceAvailability {
            override val online = flowOf(true)
            override suspend fun filesAvailable(segments: List<ResolvedScoreSegment>) = true
        }
        val vm = LibraryViewModel(repository, SavedStateHandle(mapOf("memberId" to "member")), device)
        store.put("vm", vm)
        val pdf = if (withReader) {
            val engine = object : com.ensemble.scorelibrary.pdf.PdfEngine {
                override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(com.ensemble.scorelibrary.pdf.PageSize(600, 900))
                override suspend fun render(page: com.ensemble.scorelibrary.pdf.PdfPageRef, width: Int, height: Int,
                    mode: com.ensemble.scorelibrary.pdf.FitMode, scroll: Float) = com.ensemble.scorelibrary.pdf.PdfFrame(
                    android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888),
                    com.ensemble.scorelibrary.pdf.PdfPageTransform.fit(com.ensemble.scorelibrary.pdf.PageSize(600, 900), width, height, mode, scroll))
            }
            preview = ScorePreviewViewModel(repository, engine).also { store.put("preview", it) }
            com.ensemble.scorelibrary.pdf.PdfReaderViewModel(engine, SavedStateHandle(),
                com.ensemble.scorelibrary.pdf.PerformancePreparation(engine,
                    com.ensemble.scorelibrary.pdf.TestPerformanceAnnotations())).also { store.put("pdf", it) }
        } else null
        if (withInk) ink = com.ensemble.scorelibrary.annotation.AnnotationViewModel(object : com.ensemble.scorelibrary.annotation.AnnotationStore {
            override suspend fun load(scope: com.ensemble.scorelibrary.annotation.AnnotationScope) = com.ensemble.scorelibrary.annotation.AnnotationData(
                emptyList(), emptyList(), emptyList(), 0, 0, false, null, false)
            override suspend fun save(scope: com.ensemble.scorelibrary.annotation.AnnotationScope, layer: com.ensemble.scorelibrary.annotation.AnnotationLayer,
                marks: List<com.ensemble.scorelibrary.annotation.InkMark>, generation: Long) { error("模拟保存失败") }
            override suspend fun publish(scope: com.ensemble.scorelibrary.annotation.AnnotationScope) = Unit
            override suspend fun freeze(scope: com.ensemble.scorelibrary.annotation.AnnotationScope) = Unit
        }).also { store.put("ink", it) }
        restoration = StateRestorationTester(compose)
        restoration.setContent { androidx.compose.runtime.CompositionLocalProvider(
            com.ensemble.scorelibrary.annotation.LocalAnnotations provides ink) { MaterialTheme { ScoreLibraryApp(vm, pdf, preview = preview) } } }
    }
    @Test fun pendingInkBlocksReaderTransitionsAndBackOffersMemoryOnlyExit() {
        open(withReader = true, withInk = true)
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("preview-parts").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.waitUntil { ink!!.state.value.data != null }
        compose.runOnIdle {
            ink!!.tool(com.ensemble.scorelibrary.annotation.AnnotationTool.PEN)
            ink!!.stroke(listOf(com.ensemble.scorelibrary.annotation.InkPoint(.2f, .3f)))
        }
        compose.waitUntil { ink!!.state.value.error != null }
        compose.onNodeWithTag("next-page").assertIsNotEnabled()
        compose.onNodeWithTag("play-mode").assertIsNotEnabled()
        compose.onNodeWithText("适合整页").assertIsNotEnabled()
        compose.onNodeWithContentDescription("重新打开").assertIsNotEnabled()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("批注尚未完成保存").assertExists()
        compose.onNodeWithText("留下处理").performClick()
        compose.onNodeWithTag("annotation-pending").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("保留在内存并离开").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.waitUntil { ink!!.state.value.data != null }
        org.junit.Assert.assertEquals(1, ink!!.state.value.marks.size)
        compose.onNodeWithTag("annotation-重试保存").assertExists()
    }
    @Test fun libraryDetailReaderBackAndSingleTopTabsWork() {
        open()
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("preview-parts").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.onNodeWithTag("side-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("part-part").assertExists()
        compose.onNodeWithTag("side-navigation").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("piece-piece").assertExists()
        compose.onNodeWithTag("nav-profile").performClick()
        compose.onNodeWithText("退出当前成员").assertExists()
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithTag("piece-piece").assertExists()
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithTag("back").assertDoesNotExist()
    }
    @Test fun concertUsesPersonalResultAndMemberCannotManage() {
        open()
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("concert-concert").performClick()
        compose.onNodeWithTag("concert-item-item").performClick()
        compose.onNodeWithTag("side-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("concert-item-item").assertExists()
        compose.onNodeWithTag("nav-admin").assertDoesNotExist()
        compose.onNodeWithTag("nav-profile").performClick()
        compose.onNodeWithTag("profile-management").assertDoesNotExist()
        compose.onNodeWithTag("profile-part-grants").assertDoesNotExist()
    }
    @Test fun savedNavigationRestoresReaderAndItsBackStack() {
        open()
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("preview-parts").performClick()
        compose.onNodeWithTag("part-part").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("side-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("part-part").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("piece-piece").assertExists()
    }
    @Test fun fullscreenHidesAppChromeAndRestoresWithItsReaderRoute() {
        open(withReader = true)
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("preview-parts").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.onNodeWithTag("reader-fullscreen").performClick()
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithTag("side-navigation").assertDoesNotExist()
        compose.onNodeWithTag("reader-page-number").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.onNodeWithTag("back").assertExists()
        compose.onNodeWithTag("reader-page-number").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("part-part").assertExists()
    }
    @Test @Config(qualifiers = "w400dp-h800dp-mdpi")
    fun compactWindowKeepsBottomNavigationAndReaderUsesFullWidth() {
        open()
        compose.onNodeWithTag("bottom-navigation").assertExists()
        compose.onNodeWithTag("side-navigation").assertDoesNotExist()
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("preview-parts").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("bottom-navigation").assertExists()
    }

    @Test fun selectedPreviewOpensSamePartAndReturnsWithQueryAcrossLoadingAndRestore() {
        open(withReader = true)
        compose.onNodeWithTag("library-search").performTextInput("Music")
        compose.onNodeWithTag("piece-piece").performClick()
        compose.waitUntil { preview!!.state.value is ScorePreviewState.Ready }
        compose.onNodeWithTag("preview-page").assertExists()
        compose.onNodeWithTag("preview-open-reader").performClick()
        compose.onNodeWithTag("reader-part-name").assertTextEquals("Violin I")
        compose.runOnIdle { org.junit.Assert.assertEquals(ScorePreviewState.Empty, preview!!.state.value) }
        compose.onNodeWithTag("back").performClick()
        compose.waitUntil { preview!!.state.value is ScorePreviewState.Ready }
        compose.onNodeWithTag("library-search").assertTextContains("Music")
        compose.onNodeWithTag("piece-piece").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("piece-piece").assertIsSelected()
        compose.onNodeWithTag("library-search").assertTextContains("Music")
        compose.onNodeWithTag("nav-profile").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(ScorePreviewState.Empty, preview!!.state.value) }
        compose.onNodeWithTag("nav-library").performClick()
        compose.waitUntil { preview!!.state.value is ScorePreviewState.Ready }
        compose.onNodeWithTag("piece-piece").assertIsSelected()
    }

}
