package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.domain.LibrarySnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class ManagedPieceSearchTest {
    @get:Rule val compose = createComposeRule()
    private val data = LibrarySnapshot(
        pieces = listOf(PieceEntity(id="p1",title="同名曲目",organizerSequence="001",categoryName="电影",composer="Composer"),
            PieceEntity(id="p2",title="同名曲目",organizerSequence="002",categoryName="古典",arranger="Arranger")),
        definitions = listOf(PartDefinitionEntity(id="d1",name="Violin"),PartDefinitionEntity(id="d2",name="Cello",family="Strings")),
        parts = listOf(PiecePartEntity(id="a",pieceId="p1",partDefinitionId="d1"), PiecePartEntity(id="b",pieceId="p1",partDefinitionId="d2"),
            PiecePartEntity(id="c",pieceId="p2",partDefinitionId="d2")))
    @Test fun searchCombinesWordsAndMatchesNumberCategoryInstrumentAndComposer() {
        val rows=managedPieceRows(data)
        assertEquals(2,rows.size)
        assertEquals(listOf("p2"),filterManagedPieces(rows,"002 cello 古典").map{it.piece.id})
        assertEquals(listOf("p1"),filterManagedPieces(rows,"composer").map{it.piece.id})
        assertEquals(listOf("p2"),filterManagedPieces(rows,"ARRANGER").map{it.piece.id})
        assertEquals(2,filterManagedPieces(rows,"Strings").size)
        assertEquals(2,filterManagedPieces(rows,"同名曲目").size) // Names never silently merge identities.
        assertEquals(listOf("p1"),filterManagedPieces(rows,"",definition="d1",category="电影",pdfState="missing").map{it.piece.id})
        assertTrue(filterManagedPieces(rows,"",pdfState="ready").isEmpty())
    }
    @Test fun programSelectionShowsEveryPartAndSearchSelectsOneWholePiece() {
        var selected:String?=null
        compose.setContent{MaterialTheme{ConcertPiecePicker(data,null,true){selected=it}}}
        compose.onNodeWithTag("choice-曲目").performClick()
        compose.onNodeWithText("电影 · 2 个声部 · Violin、Cello").assertExists()
        compose.onNodeWithTag("concert-piece-search").performTextInput("002 Cello")
        compose.onNodeWithTag("concert-pick-p1").assertDoesNotExist()
        compose.onNodeWithTag("concert-pick-p2").performClick()
        assertEquals("p2",selected)
        compose.onNodeWithTag("concert-piece-search").assertDoesNotExist()
    }
    @Test fun hundredsOfScoresRemainSearchableWithoutScrollingEveryEntry() {
        val library=data.copy(pieces=(1..600).map{PieceEntity(id="p$it",title="练习曲 $it",organizerSequence=it.toString().padStart(3,'0'))})
        var selected:String?=null
        compose.setContent{MaterialTheme{ConcertPiecePicker(library,null,true){selected=it}}}
        compose.onNodeWithTag("choice-曲目").performClick()
        compose.onNodeWithTag("concert-piece-search").performTextInput("599")
        compose.onNodeWithTag("concert-pick-p599").performClick()
        assertEquals("p599",selected)
    }
}
