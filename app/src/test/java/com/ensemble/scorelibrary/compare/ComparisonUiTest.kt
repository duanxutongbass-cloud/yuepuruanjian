package com.ensemble.scorelibrary.compare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class ComparisonUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val pieces = listOf(PieceSummary("a", "天空之城", null, "电影", "001", listOf("小提琴")),
        PieceSummary("b", "天空之城（第二版）", null, "电影", "037", listOf("小提琴")))
    private var missing: String? = null
    private val bitmapList = mutableListOf<Bitmap>()
    private val repository = object : PresentationRepository {
        override fun observe(memberId: String?, request: ScreenRequest): Flow<DomainResult<Presentation>> {
            val piece = pieces.first { it.id == when (request) { is ScreenRequest.Viewer -> request.pieceId; is ScreenRequest.Piece -> request.id; else -> error("request") } }
            val part = VisiblePart("${piece.id}-part", "violin", "小提琴")
            val content = if (request is ScreenRequest.Piece) ScreenContent.Piece(piece.id, piece.title, listOf(part)) else
                ScreenContent.Viewer(ResolvedConcertItem("", piece.id, piece.title, part, listOf(
                    ResolvedScoreSegment("bind-${piece.id}", null, piece.id, "v1", "fixture", "hash", 1, 3, CacheStatus.NOT_CACHED))))
            return flowOf(DomainResult.Success(Presentation(Dashboard(emptyList(), Profile("m", "合成测试成员", null, false), pieces), DomainResult.Success(content))))
        }
    }
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
            if (segments.any { it.scorePdfId == missing }) throw PdfFailure(ReaderError.FILE_MISSING)
            return segments.map { PageSize(400, 600) }
        }
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmapList += it }
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.DKGRAY; strokeWidth = 1.5f; textSize = 18f }
            canvas.drawText("PDF fixture ${page.segment.scorePdfId.uppercase()} / ${page.pageIndex + 1}", 20f, 30f, paint)
            repeat(7) { staff -> repeat(5) { line ->
                val y = 70f + staff * (height - 130f) / 7 + line * 6f
                canvas.drawLine(20f, y, width - 20f, y, paint)
            } }
            paint.color = if (page.segment.scorePdfId == "a") Color.BLUE else Color.RED
            canvas.drawCircle(width * .5f, height * .25f, 7f, paint)
            return PdfFrame(bitmap, PdfPageTransform.fit(PageSize(400, 600), width, height, mode))
        }
    }
    private fun model() = ScoreComparisonViewModel(repository, engine).also { models.put("compare", it) }
    @After fun close() { models.clear() }
    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
    }
    private fun choose(side: Int, id: String, query: String) {
        compose.onNodeWithTag("compare-select-$side").performClick()
        compose.onNodeWithTag("compare-search").performTextInput(query)
        compose.onNodeWithTag("compare-pick-$side-$id").performScrollTo().performClick()
        compose.onNodeWithTag("compare-part-$side-$id-part").performScrollTo().performClick()
    }
    @Test fun realUiSearchSelectsDistinctNumbersIndependentPagesAndFocus() {
        val vm = model()
        compose.setContent { ScoreTheme { ScoreComparisonContent("m", pieces, vm, {}) } }
        waitFor { vm.state.value.member == "m" }
        choose(0, "a", "001"); choose(1, "b", "037")
        waitFor { vm.state.value.panes.all { it.image != null } }
        compose.onNodeWithTag("compare-title-0").assertTextContains("001", substring = true)
        compose.onNodeWithTag("compare-title-1").assertTextContains("037", substring = true)
        compose.onNodeWithTag("compare-part-1").assertTextContains("小提琴", substring = true)
        compose.onNodeWithTag("compare-next-0").performClick()
        waitFor { vm.state.value.panes[0].sourcePage == 2 }
        compose.onNodeWithTag("compare-position-0").assertTextEquals("2/3")
        compose.onNodeWithTag("compare-position-1").assertTextEquals("1/3")
        screenshot()
        compose.onNodeWithTag("compare-focus-0").performClick()
        compose.onNodeWithTag("compare-pane-1").assertDoesNotExist()
        waitFor { vm.state.value.panes[1].image == null && vm.state.value.panes[0].image != null }
        compose.onNodeWithTag("compare-focus-1").performClick()
        compose.onNodeWithTag("compare-pane-0").assertDoesNotExist()
        waitFor { vm.state.value.panes[1].image != null }
    }
    @Test fun missingSideShowsErrorWhileOtherScoreRemainsReadable() {
        val vm = model(); missing = "a"
        compose.setContent { ScoreTheme { ScoreComparisonContent("m", pieces, vm, {}) } }
        waitFor { vm.state.value.member == "m" }; choose(0, "a", "001"); choose(1, "b", "037")
        waitFor { vm.state.value.panes[0].error != null && vm.state.value.panes[1].image != null }
        compose.onNodeWithTag("compare-error-0").assertTextEquals("本地曲谱文件缺失")
        compose.onNodeWithTag("compare-page-1").assertExists()
        missing = null; compose.onNodeWithTag("compare-retry-0").performClick()
        waitFor { vm.state.value.panes[0].image != null }
    }
    @Test fun libraryEntryOpensActualDialogAndClosingReleasesItsImages() {
        val vm = model()
        compose.setContent { ScoreTheme { Surface { CompositionLocalProvider(LocalScoreComparison provides vm) {
            PieceWorkspace(pieces, "m", onPiece = {})
        } } } }
        compose.onNodeWithTag("library-more").performClick()
        compose.onNodeWithTag("library-compare").performClick()
        waitFor { vm.state.value.member == "m" }; choose(0, "a", "001")
        waitFor { vm.state.value.panes[0].image != null }
        compose.onNodeWithTag("comparison-close").performClick()
        waitFor { vm.state.value.member == null }
        assertTrue(bitmapList.all { it.isRecycled })
        compose.onNodeWithTag("library-search").assertExists()
    }
    @Test fun memberChangeCannotDisplayOrReopenPreviousMembersFrames() {
        val vm = model(); var member by mutableStateOf("m")
        compose.setContent { ScoreTheme { ScoreComparisonContent(member, pieces, vm, {}) } }
        waitFor { vm.state.value.member == "m" }; choose(0, "a", "001")
        waitFor { vm.state.value.panes[0].image != null }
        val old = vm.state.value.panes[0].image!!
        compose.runOnIdle { member = "other" }
        waitFor { vm.state.value.member == "other" }
        compose.onNodeWithTag("compare-page-0").assertDoesNotExist(); assertFalse(old.canDisplay())
        compose.onNodeWithTag("compare-select-0").performClick()
        compose.onNodeWithTag("compare-search").performTextInput("001")
        compose.onNodeWithTag("compare-pick-0-a").performClick()
        waitFor { vm.state.value.panes[0].error != null }
        assertNull(vm.state.value.panes[0].image)
    }
    @Test fun backgroundEndsComparisonAndResumeRequiresFreshSelections() {
        val vm = model()
        compose.setContent { ScoreTheme { ScoreComparisonContent("m", pieces, vm, {}) } }
        waitFor { vm.state.value.member == "m" }; choose(0, "a", "001")
        waitFor { vm.state.value.panes[0].image != null }
        val old = vm.state.value.panes[0].image!!
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        waitFor { vm.state.value.member == null }; assertFalse(old.canDisplay())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitFor { vm.state.value.member == "m" }
        assertTrue(vm.state.value.panes.all { it.target == null && it.image == null })
        compose.onNodeWithTag("compare-page-0").assertDoesNotExist()
    }
    private fun screenshot() {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File("build/reports/screenshots/score-comparison.png").also { it.parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
    }
}
