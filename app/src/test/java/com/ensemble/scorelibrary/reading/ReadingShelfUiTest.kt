package com.ensemble.scorelibrary.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.domain.PieceSummary
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class ReadingShelfUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 15 }, "AES")
    private val storage get() = ReadingActivityStore(temporary.root) { key }
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var member = "m"
    private val activity by lazy { ReadingActivity(storage, { m -> ReadingIdentity(ReadingOwner("local", m)) {
        check(m == member)
    } }, flowOf(Unit), worker) }
    private val models = ViewModelStore()
    private val pieces = listOf(PieceSummary("p", "天空之城", null, "电影", "007", listOf("小提琴")),
        PieceSummary("q", "春节序曲", null, "中国风格", "008", listOf("大提琴")))
    private fun model() = ReadingShelfViewModel(activity).also { models.put("shelf", it) }
    @After fun cleanup() { models.clear(); worker.cancel() }
    private fun waitFor(model: ReadingShelfViewModel, condition: (ReadingShelfState) -> Boolean = { true }) {
        compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !model.state.value.busy && condition(model.state.value)
        }
    }
    @Test fun favoriteFilterSearchAndCatalogRevocationKeepOnlyCurrentlyVisibleRows() {
        runBlocking { storage.favorite(ReadingOwner("local", member), "p", true) }
        val model = model(); var visible by mutableStateOf(pieces)
        compose.setContent { CompositionLocalProvider(LocalReadingShelf provides model) {
            Workspace { PieceWorkspace(visible, member, onPiece = {}) }
        } }
        waitFor(model) { it.owner != null }
        compose.onNodeWithTag("favorite-q").performClick(); waitFor(model) { "q" in it.data.favorites }
        compose.onNodeWithTag("shelf-favorites").performClick()
        compose.onNodeWithTag("piece-p").assertExists(); compose.onNodeWithTag("piece-q").assertExists()
        compose.onNodeWithTag("library-search").performTextInput("008")
        compose.onNodeWithTag("piece-p").assertDoesNotExist(); compose.onNodeWithTag("piece-q").assertExists()
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.runOnIdle { visible = listOf(pieces.last()) }
        compose.onNodeWithTag("piece-p").assertDoesNotExist(); compose.onNodeWithTag("piece-q").assertExists()
        assertEquals(2, pieces.size)
        assertEquals(setOf("p", "q"), runBlocking { storage.read(ReadingOwner("local", member)).favorites })
        compose.onNodeWithTag("clear-favorites").performClick()
        assertEquals(2, model.state.value.data.favorites.size)
        compose.onNodeWithTag("confirm-clear-favorites").performClick()
        waitFor(model) { it.data.favorites.isEmpty() }
        assertTrue(runBlocking { storage.read(ReadingOwner("local", member)).favorites.isEmpty() })
    }
    @Test fun recentContinuationUsesStoredPartAndClearRequiresExplicitConfirmation() {
        runBlocking {
            val owner = ReadingOwner("local", member)
            storage.favorite(owner, "p", true)
            storage.record(owner, RecentReading("p", "last-cello-part", "a".repeat(64), 3))
            storage.record(owner, RecentReading("hidden-piece", "secret-part", "b".repeat(64), 0))
        }
        val model = model(); var opened: Pair<String, String>? = null
        compose.setContent { CompositionLocalProvider(LocalReadingShelf provides model) {
            Workspace { PieceWorkspace(pieces, member, onPart = { p, part -> opened = p to part }, onPiece = {}) }
        } }
        waitFor(model) { it.data.recent.isNotEmpty() }
        compose.onNodeWithTag("shelf-recent").performClick()
        compose.onNodeWithTag("piece-q").assertDoesNotExist(); compose.onNodeWithTag("piece-hidden-piece").assertDoesNotExist()
        compose.onNodeWithTag("piece-p").performClick(); compose.onNodeWithTag("continue-recent").performClick()
        assertEquals("p" to "last-cello-part", opened)
        compose.onNodeWithTag("clear-recent").performClick()
        assertEquals(2, runBlocking { storage.read(ReadingOwner("local", member)).recent.size })
        compose.onNodeWithText("保留").performClick(); assertEquals(2, model.state.value.data.recent.size)
        screenshot()
        compose.onNodeWithTag("clear-recent").performClick(); compose.onNodeWithTag("confirm-clear-recent").performClick()
        waitFor(model) { it.data.recent.isEmpty() }
        assertEquals(setOf("p"), model.state.value.data.favorites)
    }
    @Test fun differentMemberStartsWithEmptyPersonalShelfAndNormalCatalogRemains() {
        runBlocking { storage.favorite(ReadingOwner("local", member), "p", true) }
        val model = model(); var selected by mutableStateOf(member)
        compose.setContent { CompositionLocalProvider(LocalReadingShelf provides model) {
            Workspace { PieceWorkspace(pieces, selected, onPiece = {}) }
        } }
        waitFor(model) { it.data.favorites.isNotEmpty() }
        compose.onNodeWithTag("shelf-favorites").performClick(); compose.onNodeWithTag("piece-q").assertDoesNotExist()
        compose.runOnIdle { member = "other"; selected = member }
        waitFor(model) { it.member == "other" }
        compose.onNodeWithTag("piece-q").assertExists()
        compose.onNodeWithTag("shelf-favorites").performClick()
        compose.onNodeWithTag("piece-p").assertDoesNotExist(); compose.onNodeWithTag("piece-q").assertDoesNotExist()
    }
    @Composable private fun Workspace(content: @Composable () -> Unit) {
        ScoreTheme { androidx.compose.material3.Surface(color = ScorePalette.Workspace, contentColor = ScorePalette.Ink, content = content) }
    }
    private fun screenshot() {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/screenshots/reading-shelf.png").also { it.parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }
}
