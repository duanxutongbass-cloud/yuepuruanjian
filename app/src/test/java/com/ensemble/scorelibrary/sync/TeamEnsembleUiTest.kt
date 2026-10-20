package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamEnsembleUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val piece = EnsemblePiece("sequence:001", "同名合成曲", "001", "01 合成分类", false, false,
        listOf(EnsemblePart("violin", "第一小提琴", "0101001"), EnsemblePart("cello", "大提琴", "0201001")))
    @Test fun batchPurgeKeepsRecycleQueryAndRemainingRowsAfterRefresh() {
        val a=piece.copy(removed=true);val b=a.copy(key="sequence:002",number="002")
        val keep=a.copy(key="sequence:003",title="保留合成曲",number="003")
        var model by androidx.compose.runtime.mutableStateOf(EnsembleState(key="owner",pieces=listOf(a,b,keep),revision=7))
        var selected=emptySet<String>()
        val preview=PermanentPreview("owner",7,"a".repeat(64),setOf("a","b"),listOf(a.heading,b.heading),100,0,0,0,emptyList(),0,0)
        ui.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) { TeamEnsembleManager(model,emptyList(),false,{},{},
            previewSelected={selected=it;model=model.copy(permanentPreview=preview)},
            confirmPermanent={model=model.copy(permanentPreview=null,pieces=listOf(keep),changes=1,revision=8)}) } } }
        ui.onNodeWithText("查看回收站").performClick()
        ui.onNodeWithText("搜索曲名、编号、分类或乐器").performTextInput("同名")
        ui.onNodeWithTag("team-select-visible").performClick()
        ui.runOnUiThread { com.ensemble.scorelibrary.ui.saveUiProof(ui.activity,"0126-team-batch.png") }
        ui.onNodeWithTag("team-purge-selected").performClick()
        assertEquals(setOf(a.key,b.key),selected)
        ui.onNodeWithTag("confirm-team-purge").assertIsNotEnabled()
        ui.onNodeWithTag("team-purge-checked").performScrollTo().performClick()
        ui.onNodeWithTag("confirm-team-purge").performClick()
        ui.onNodeWithText("回收站").assertExists()
        ui.onNodeWithText("搜索曲名、编号、分类或乐器").assertTextContains("同名")
        ui.onNodeWithTag("team-purge-selected").assertIsNotEnabled()
        ui.onNodeWithText("搜索曲名、编号、分类或乐器").performTextClearance()
        ui.onNodeWithTag("team-select-${keep.key}").assertExists().performClick()
        ui.onNodeWithTag("team-purge-selected").assertIsEnabled()
        ui.runOnIdle { model=model.copy(key="other",pieces=listOf(a)) }
        ui.onNodeWithText("查看回收站").assertExists()
        ui.onNodeWithTag("team-purge-selected").assertDoesNotExist()
    }
    @Test fun purgeConfirmation() {
        var model by androidx.compose.runtime.mutableStateOf(EnsembleState(key="account",pieces=listOf(piece.copy(removed=true)),revision=7))
        val preview=PermanentPreview("account",7,"a".repeat(64),setOf("synthetic-artifact"),listOf("001 · 同名合成曲 · 第一小提琴"),100,2,3,4,listOf("合成场次"),1,1)
        var deleted:PermanentPreview?=null
        ui.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) { TeamEnsembleManager(model,emptyList(),false,{},{},
            previewPermanent={model=model.copy(permanentPreview=preview)},confirmPermanent={deleted=it},
            dismissPermanent={model=model.copy(permanentPreview=null)}) } } }
        ui.onNodeWithTag("team-purge-sequence:001").assertDoesNotExist()
        ui.onNodeWithText("查看回收站").performClick()
        ui.onNodeWithTag("team-purge-sequence:001").performClick()
        assertNull(deleted)
        ui.onNodeWithTag("confirm-team-purge").assertIsNotEnabled()
        ui.onNodeWithTag("team-purge-checked").performScrollTo().performClick()
        ui.onNodeWithTag("confirm-team-purge").assertIsEnabled()
        ui.onNodeWithText("取消").performClick();assertNull(deleted)
        ui.onNodeWithTag("team-purge-sequence:001").performClick()
        ui.onNodeWithTag("confirm-team-purge").assertIsNotEnabled()
        ui.onNodeWithTag("team-purge-checked").performScrollTo().performClick()
        ui.onNodeWithTag("confirm-team-purge").performClick()
        assertSame(preview,deleted)
    }
    @Test fun numberCheckShowsGapsWithoutRenumberingExistingPieces() {
        var checked=false
        ui.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) { TeamEnsembleManager(EnsembleState(pieces=listOf(piece),revision=1,
            numberInventory=TeamNumberInventory(1,setOf(1,3,999),2)),emptyList(),false,{},{},
            checkNumbers={checked=true}) } } }
        ui.onNodeWithTag("team-check-numbers").assertIsEnabled().performClick()
        ui.runOnIdle { assertTrue(checked) }
        ui.onNodeWithText("下一首新曲：002").assertIsDisplayed()
        ui.onNodeWithText("未完成上传 2 份，其编号暂时保留").assertIsDisplayed()
        assertEquals("001",piece.number)
    }
    @Test fun selectWholeMusicWithEveryVoice() {
        var saved: EnsembleProgram? = null
        val other = piece.copy(key = "sequence:002", number = "002", parts = listOf(EnsemblePart("bass", "低音提琴", "0301002")))
        ui.setContent { MaterialTheme { TeamProgramEditor(EnsembleProgram(), listOf(piece, other), emptyList(), false, "", {}, { saved = it }) } }
        ui.onNodeWithTag("program-voice-violin", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("program-voice-cello", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("team-program-title").performTextInput("合成演出")
        ui.onNodeWithTag("team-program-select-sequence:001").performClick()
        ui.onNodeWithTag("team-program-save").performClick()
        assertEquals(listOf("sequence:001"), saved!!.pieces)
        assertFalse("same title with a different number is independent", "sequence:002" in saved!!.pieces)
    }
    @Test fun removalNeedsExplicitConfirmation() {
        var removed: EnsemblePiece? = null
        ui.setContent { MaterialTheme { TeamEnsembleManager(EnsembleState(pieces = listOf(piece), revision = 1), emptyList(), false, {}, { removed = it }) } }
        ui.onNodeWithTag("team-remove-piece-sequence:001").performClick(); assertNull(removed)
        ui.onNodeWithText("取消").performClick(); assertNull(removed)
        ui.onNodeWithTag("team-remove-piece-sequence:001").performClick()
        ui.onNodeWithTag("confirm-team-score-remove").performClick()
        assertEquals(2, removed!!.parts.size)
    }
    @Test fun programRemovalAndRecycleAreSeparateFromScores() {
        val program = EnsembleProgram(title = "合成节目单", pieces = listOf(piece.key))
        var saved: EnsembleProgram? = null
        ui.setContent { MaterialTheme { TeamEnsembleManager(EnsembleState(programs = listOf(program), revision = 1), emptyList(), true, { saved = it }, { fail("programme removal must not remove PDFs") }) } }
        ui.onNodeWithTag("team-remove-program-${program.id}").performClick(); assertNull(saved)
        ui.onNodeWithTag("confirm-team-program-remove").performClick()
        assertTrue(saved!!.archived); assertEquals(program.pieces, saved!!.pieces)
    }
    @Test fun freezeNeedsConfirmation() {
        val program = EnsembleProgram(title = "合成冻结场次", pieces = listOf(piece.key), revision = 1)
        var frozen: EnsembleProgram? = null
        ui.setContent { MaterialTheme { TeamEnsembleManager(EnsembleState(programs = listOf(program), revision = 2),
            emptyList(), true, {}, {}, freeze = { frozen = it }) } }
        ui.onNodeWithTag("team-freeze-program-${program.id}").performClick()
        assertNull(frozen)
        ui.onNodeWithText("取消").performClick(); assertNull(frozen)
        ui.onNodeWithTag("team-freeze-program-${program.id}").performClick()
        ui.onNodeWithText("个人批注与共享草稿不包含在内", substring = true).assertIsDisplayed()
        ui.onNodeWithTag("confirm-team-freeze").performClick()
        assertEquals(program, frozen)
    }
    @Test fun frozenProgramCopiesToNewScene() {
        val program = EnsembleProgram(title = "合成已冻结场次", pieces = listOf(piece.key), revision = 3,
            accounts = setOf("synthetic-member"), freezeId = java.util.UUID.randomUUID().toString())
        var saved: EnsembleProgram? = null
        ui.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Column { TeamEnsembleManager(
            EnsembleState(pieces = listOf(piece), programs = listOf(program), revision = 4),
            emptyList(), true, { saved = it }, {}) } } } }
        ui.onNodeWithTag("team-freeze-program-${program.id}").assertDoesNotExist()
        ui.onNodeWithText("已冻结", substring = true).assertIsDisplayed()
        ui.waitForIdle()
        lateinit var bitmap: android.graphics.Bitmap
        ui.runOnUiThread {
            val view = ui.activity.window.decorView
            bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val target = java.io.File("build/reports/screenshots/team-frozen-program.png"); target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        ui.onNodeWithTag("team-edit-program-${program.id}").performClick()
        ui.onNodeWithTag("team-program-save").performClick()
        assertNotEquals(program.id, saved!!.id); assertEquals(0, saved!!.revision); assertNull(saved!!.freezeId)
        assertEquals(program.pieces, saved!!.pieces); assertEquals(program.accounts, saved!!.accounts)
        assertEquals(3, program.revision); assertNotNull(program.freezeId)
    }
}
