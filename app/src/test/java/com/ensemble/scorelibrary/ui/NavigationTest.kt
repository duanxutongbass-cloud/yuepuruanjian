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
    private fun open(withReader: Boolean = false) {
        val part = VisiblePart("part", "d", "Violin I")
        val item = ResolvedConcertItem("item", "piece", "Music", part, if (withReader) listOf(
            ResolvedScoreSegment("binding", null, "pdf", "v", "fixture", "hash", 1, 2,
                com.ensemble.scorelibrary.core.model.CacheStatus.NOT_CACHED)) else emptyList())
        val dashboard = Dashboard(listOf(MemberChoice("member", "Member")), Profile("member", "Member", "Violin I", false),
            listOf(PieceSummary("piece", "Music", null)), listOf(ConcertSummary("concert", "Concert")))
        val repository = object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = flowOf(DomainResult.Success(
                Presentation(dashboard, DomainResult.Success(when (request) {
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
            com.ensemble.scorelibrary.pdf.PdfReaderViewModel(engine, SavedStateHandle(),
                com.ensemble.scorelibrary.pdf.PerformancePreparation(engine,
                    com.ensemble.scorelibrary.pdf.TestPerformanceAnnotations())).also { store.put("pdf", it) }
        } else null
        restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ScoreLibraryApp(vm, pdf) } }
    }
    @Test fun libraryDetailReaderBackAndSingleTopTabsWork() {
        open()
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("part-part").assertExists()
        compose.onNodeWithTag("bottom-navigation").assertExists()
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
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
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
        compose.onNodeWithTag("part-part").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("part-part").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("piece-piece").assertExists()
    }
    @Test fun fullscreenHidesAppChromeAndRestoresWithItsReaderRoute() {
        open(withReader = true)
        compose.onNodeWithTag("piece-piece").performClick()
        compose.onNodeWithTag("part-part").performClick()
        compose.onNodeWithTag("reader-fullscreen").performClick()
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithTag("reader-page-number").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.onNodeWithTag("back").assertExists()
        compose.onNodeWithTag("reader-page-number").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("part-part").assertExists()
    }
}
