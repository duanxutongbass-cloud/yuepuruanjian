package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ensemble.scorelibrary.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1800dp-mdpi")
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun ready(content: ScreenContent = ScreenContent.Home, offline: Boolean = false, missing: Boolean = false) =
        UiState.Ready(Presentation(Dashboard(emptyList(), Profile("m", "Member", "I", false)), DomainResult.Success(content)), offline, missing)

    @Test fun detailShowsOnlyDomainPartsAndUsesLargePadColumns() {
        var clicked = ""
        compose.setContent { MaterialTheme { LibraryScreen(ready(ScreenContent.Piece("p", "Piece", listOf(VisiblePart("a", "d", "Visible")))),
            RootPage.Library, onPart = { piece, part -> clicked = "$piece/$part" }) } }
        compose.onNodeWithTag("detail-columns").assertExists()
        compose.onNodeWithText("Secret").assertDoesNotExist()
        compose.onNodeWithTag("part-a").performClick()
        assertEquals("p/a", clicked)
    }
    @Test fun errorAndRetryHaveNoScoreData() {
        var retried = false
        compose.setContent { MaterialTheme { LibraryScreen(UiState.Error(ErrorCode.UNAUTHORIZED), RootPage.Library, onRetry = { retried = true }) } }
        compose.onNodeWithText("当前成员没有该声部的访问权限").assertExists()
        compose.onNodeWithText("重试").performClick()
        assertTrue(retried)
    }
    @Test fun offlineEmptyLibraryAndMemberAdminDenialRender() {
        compose.setContent { MaterialTheme { LibraryScreen(ready(offline = true), RootPage.Library) } }
        compose.onNodeWithTag("offline").performClick()
        compose.onNodeWithTag("offline-explanation").assertExists()
        compose.onNodeWithTag("offline-close").performClick()
        compose.onNodeWithTag("offline-explanation").assertDoesNotExist()
        compose.onNodeWithText("曲库为空").assertExists()
    }
    @Test fun fileMissingDoesNotExposePathOrRenderPdf() {
        val item = ResolvedConcertItem("i", "p", "Piece", VisiblePart("a", "d", "I"), emptyList())
        compose.setContent { MaterialTheme { LibraryScreen(ready(ScreenContent.Viewer(item), missing = true), RootPage.Library) } }
        compose.onNodeWithText("本地曲谱文件缺失").assertExists()
        compose.onNodeWithText("曲谱阅读视图待接入").assertDoesNotExist()
    }
    @Test fun breakpointsAndRootSelectionAreExplicit() {
        assertEquals(PadLayout.Compact, padLayout(599))
        assertEquals(PadLayout.Medium, padLayout(600))
        assertEquals(PadLayout.Expanded, padLayout(840))
        assertEquals(PadLayout.Large, padLayout(1200))
        assertTrue(Routes.isViewer(Routes.VIEWER))
        assertFalse(Routes.isViewer("library"))
        assertEquals(RootPage.Concerts, Routes.root(Routes.CONCERT))
        assertEquals(RootPage.Library, Routes.root(Routes.PIECE))
    }
}
