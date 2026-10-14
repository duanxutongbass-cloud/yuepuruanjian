package com.ensemble.scorelibrary.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.ensemble.scorelibrary.domain.PieceSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class LibraryWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val pieces = listOf(PieceSummary("a", "合成练习 A", "Alpha"), PieceSummary("b", "合成练习 B", "Beta"))
    @Test fun searchComposerClearAndLayoutRetainSelection() {
        var opened = ""
        compose.setContent { ScoreTheme { PieceWorkspace(pieces, "m") { opened = it } } }
        compose.onNodeWithTag("library-search").performTextInput(" beta ")
        compose.onNodeWithTag("piece-a").assertDoesNotExist()
        compose.onNodeWithTag("piece-b").performClick()
        assertEquals("b", opened)
        compose.onNodeWithTag("library-layout").performClick()
        compose.onNodeWithTag("library-list").assertExists()
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.onNodeWithTag("piece-a").assertExists()
    }
    @Test fun queryRestoresButMemberSwitchClearsItAndCannotExposeRemovedRows() {
        var member by mutableStateOf("m")
        var available by mutableStateOf(pieces)
        val restore = StateRestorationTester(compose)
        restore.setContent { ScoreTheme { PieceWorkspace(available, member) {} } }
        compose.onNodeWithTag("library-search").performTextInput("Alpha")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("piece-b").assertDoesNotExist()
        compose.runOnIdle { member = "n"; available = listOf(pieces.last()) }
        compose.onNodeWithTag("piece-b").assertExists()
        compose.onNodeWithTag("piece-a").assertDoesNotExist()
    }
    @Test fun noMatchOffersClearAndPreservesOriginalProjection() {
        compose.setContent { ScoreTheme { PieceWorkspace(pieces, "m") {} } }
        compose.onNodeWithTag("library-search").performTextInput("missing")
        compose.onNodeWithText("没有匹配的曲目").assertExists()
        compose.onNodeWithText("清除搜索").performClick()
        compose.onNodeWithTag("piece-a").assertExists()
        assertEquals(listOf("a", "b"), pieces.map { it.id })
    }
    @Test fun titleSortHasTwoDistinctStableOrdersWithoutMutatingCatalog() {
        assertEquals(listOf("a", "b"), filterPieces(pieces.reversed(), "", false).map { it.id })
        assertEquals(listOf("b", "a"), filterPieces(pieces, "", true).map { it.id })
        assertEquals(listOf("b"), filterPieces(pieces, "BETA", true).map { it.id })
        assertEquals(listOf("a", "b"), pieces.map { it.id })
    }

}
