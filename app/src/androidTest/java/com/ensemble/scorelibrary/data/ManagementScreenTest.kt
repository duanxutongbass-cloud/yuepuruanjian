package com.ensemble.scorelibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.NativePdfEngine
import com.ensemble.scorelibrary.ui.ManagementConsole
import com.ensemble.scorelibrary.ui.ManagementViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 31)
class ManagementScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var db: ScoreLibraryDatabase
    private lateinit var service: LibraryManagement
    private lateinit var model: ManagementViewModel
    private val tab = mutableIntStateOf(1)
    private val restoration by lazy { StateRestorationTester(compose) }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    @After fun cleanup() { store.clear(); if (::db.isInitialized) db.close(); shell("wm size reset"); shell("wm density reset") }
    private fun open(width: Int, height: Int) {
        shell("wm size ${width}x${height}"); shell("wm density 160"); compose.activityRule.scenario.recreate()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        runBlocking {
            val dao = db.libraryDao()
            dao.insertMember(MemberEntity(id = "admin", displayName = "管理员", role = MemberRole.ADMIN))
            dao.insertPartDefinition(PartDefinitionEntity(id = "d1", name = "小提琴 I"))
            dao.insertPartDefinition(PartDefinitionEntity(id = "d2", name = "小提琴 II"))
            dao.insertMember(MemberEntity(id = "member", displayName = "演奏员", defaultPartDefinitionId = "d1"))
            for (n in 1..2) dao.insertPiece(PieceEntity(id = "p$n", title = if (n == 1) "天空之城" else "小夜曲", composer = "测试作曲者"))
            dao.insertPiecePart(PiecePartEntity(id = "part1", pieceId = "p1", partDefinitionId = "d1"))
            dao.insertPiecePart(PiecePartEntity(id = "part2", pieceId = "p1", partDefinitionId = "d2"))
            dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "小提琴总谱"))
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v1", scorePdfId = "pdf", versionNumber = 1,
                managedFilePath = "fixture", originalUri = "content://fixture", fileHash = "hash", fileSizeBytes = 1, pageCount = 4))
            dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "小提琴总谱", activeVersionId = "v1"))
            for (n in 1..2) dao.insertPartScoreBinding(PartScoreBindingEntity(id = "b$n", piecePartId = "part$n", scorePdfId = "pdf", startPage = n, endPage = n))
            dao.insertPermission(MemberPartPermissionEntity(memberId = "member", partDefinitionId = "d1"))
            dao.insertConcert(ConcertEntity(id = "concert", title = "秋季音乐会"))
            for (n in 1..2) dao.insertConcertItem(ConcertItemEntity(id = "i$n", concertId = "concert", pieceId = "p$n", sortOrder = n - 1))
        }
        service = LibraryManagement(context, db, NativePdfEngine(context))
        model = ManagementViewModel(service); store.put("management", model)
        compose.runOnIdle { model.bind("admin") }
        restoration.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ManagementConsole("admin", tab.intValue, model) } } }
        compose.waitUntil(15_000) { model.state.value.catalog != null }
    }
    private fun settled(revision: Long) { compose.waitUntil(15_000) { model.state.value.revision > revision && !model.state.value.busy }; compose.waitForIdle() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        var dark = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4)
            if (android.graphics.Color.red(bitmap.getPixel(x, y)) < 100) dark++
        assertTrue("Management surface must not be blank", dark > 100)
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase8-$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    }
    @Test fun standardPadCreatesAndEditsRealRoomPiece() {
        open(800, 1200)
        compose.onNodeWithTag("manage-new").performClick()
        compose.onNodeWithTag("editor-name").performTextInput("弦乐四重奏")
        val revision = model.state.value.revision
        compose.onNodeWithTag("manage-save").performClick(); settled(revision)
        val id = runBlocking { db.resolutionDao().pieces().first { it.title == "弦乐四重奏" }.id }
        compose.onNodeWithTag("managed-$id").performClick()
        compose.onNodeWithTag("management-list").assertDoesNotExist()
        compose.onNodeWithTag("management-detail").assertIsDisplayed()
        screenshot("management-standard-pad")
        compose.onNodeWithTag("manage-detail-edit").performClick()
        compose.onNodeWithTag("editor-name").performTextReplacement("弦乐四重奏修订")
        val next = model.state.value.revision
        compose.onNodeWithTag("manage-save").performClick(); settled(next)
        assertEquals("弦乐四重奏修订", runBlocking { db.resolutionDao().pieces().first { it.id == id }.title })
        compose.onNodeWithTag("manage-list-back").performClick()
        compose.onNodeWithTag("management-list").assertIsDisplayed()
    }
    @Test fun largePadUsesTwoPanesAndStaleEditorCannotOverwrite() {
        open(1280, 1800)
        compose.onNodeWithTag("managed-p1").performClick()
        compose.onNodeWithTag("management-list").assertIsDisplayed()
        compose.onNodeWithTag("management-detail").assertIsDisplayed()
        screenshot("management-large-pad")
        compose.onNodeWithTag("manage-detail-edit").performClick()
        compose.onNodeWithTag("editor-name").performTextReplacement("旧表单")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("editor-name").assertTextContains("旧表单")
        runBlocking { service.edit("admin", ManagementCommand.Piece(db.resolutionDao().pieces().first { it.id == "p1" }.copy(title = "并发更新"))) }
        compose.waitUntil(15_000) { model.state.value.catalog?.data?.pieces?.first { it.id == "p1" }?.title == "并发更新" }
        compose.onNodeWithTag("manage-save").assertIsNotEnabled()
        assertEquals("并发更新", runBlocking { db.resolutionDao().pieces().first { it.id == "p1" }.title })
    }
    @Test fun permissionsAndReorderWriteThroughDomainAndTransactions() {
        open(1280, 1800)
        compose.runOnIdle { tab.intValue = 2 }
        compose.onNodeWithTag("managed-member").performClick()
        val revision = model.state.value.revision
        compose.onNodeWithTag("global-d2").performClick(); settled(revision)
        runBlocking {
            assertEquals(setOf("part1", "part2"), (ScoreResolver(db.resolutionDao().snapshot()).visibleParts("member", "p1") as DomainResult.Success).value.map { it.id }.toSet())
            assertTrue((ScoreResolver(db.resolutionDao().snapshot()).visibleParts("admin", "p1") as DomainResult.Success).value.isEmpty())
        }
        compose.runOnIdle { tab.intValue = 4 }
        compose.onNodeWithTag("managed-concert").performClick()
        val next = model.state.value.revision
        compose.onNodeWithTag("manage-up-i2").performClick(); settled(next)
        assertEquals(listOf("i2", "i1"), runBlocking { db.resolutionDao().items().sortedBy { it.sortOrder }.map { it.id } })
        compose.runOnIdle { model.bind("member") }
        compose.onNodeWithTag("management-list").assertDoesNotExist()
        assertNull(model.state.value.catalog)
    }
}
