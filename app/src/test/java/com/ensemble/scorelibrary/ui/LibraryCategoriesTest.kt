package com.ensemble.scorelibrary.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.domain.PieceSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryCategoriesTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val pieces=listOf(PieceSummary("a","天空","", "中国风格","001",listOf("小提琴"),"01"),
        PieceSummary("b","海风","", "中国风格","002",listOf("大提琴"),"01"),
        PieceSummary("c","练习曲","", "电影","003",listOf("小提琴"),"02"))
    @Test fun codeFoldersAreAutomaticAndFilterOnlyCurrentAuthorizedProjection() {
        var rows by mutableStateOf(pieces)
        ui.setContent { ScoreTheme { PieceWorkspace(rows,"member"){} } }
        ui.onNodeWithTag("category-code:01").assertIsDisplayed().performClick()
        ui.onNodeWithTag("piece-a").assertExists();ui.onNodeWithTag("piece-b").assertExists()
        ui.onNodeWithTag("piece-c").assertDoesNotExist()
        ui.onNodeWithTag("library-search").performTextInput("海风")
        ui.onNodeWithTag("piece-a").assertDoesNotExist();ui.onNodeWithTag("piece-b").assertExists()
        ui.onNodeWithContentDescription("清除搜索").performClick()
        ui.runOnUiThread {
            val view=ui.activity.window.decorView;val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b));val f=File("build/reports/screenshots/0130-category-folders.png");f.parentFile!!.mkdirs()
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        }
        ui.runOnIdle {rows=listOf(pieces.last())}
        ui.onNodeWithTag("category-code:01").assertDoesNotExist()
        ui.onNodeWithTag("piece-a").assertDoesNotExist();ui.onNodeWithTag("piece-c").assertExists()
        assertEquals(listOf("a","b"),libraryCategories(pieces).first { it.key=="code:01" }.pieces.toList())
    }
    @Test fun sameNumberCategoryIsOneFolderAndUnclassifiedRemainsReachable() {
        val rows=pieces+pieces[0].copy(id="d",categoryName="另一名称")+pieces[1].copy(id="e",categoryName=null,categoryCode=null)
        val categories=libraryCategories(rows)
        assertEquals(setOf("a","b","d"),categories.first {it.key=="code:01"}.pieces)
        assertEquals("01 · 分类",categories.first {it.key=="code:01"}.label)
        assertEquals(setOf("e"),categories.first {it.key=="unclassified"}.pieces)
        assertEquals(5,categories.sumOf {it.pieces.size})
    }
}
