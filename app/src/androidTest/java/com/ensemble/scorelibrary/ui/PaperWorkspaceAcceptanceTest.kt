package com.ensemble.scorelibrary.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.MainActivity
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.di.AcceptanceDependencies
import com.ensemble.scorelibrary.pdf.PdfFixture
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real MainActivity + Hilt singleton Room + Domain + native PDF. Disposable synthetic AVD only. */
@RunWith(AndroidJUnit4::class)
class PaperWorkspaceAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun await(tag: String) = compose.waitUntil(180_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        bitmap.recycle()
    }
    @Test fun actualAppAuthorizedPreviewReaderReturnAndRevocation() = runBlocking {
        Assume.assumeTrue("Never seed a personal installation", InstrumentationRegistry.getArguments().getString("isolatedTestDevice") == "true")
        Assume.assumeTrue(android.os.Build.FINGERPRINT.startsWith("Android/sdk") && android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EntryPointAccessors.fromApplication(context, AcceptanceDependencies::class.java).database()
        val dao = db.libraryDao()
        val id = "ui-${UUID.randomUUID()}"
        val label = id.takeLast(6)
        val file = File(context.filesDir, "$id.pdf")
        val hash = PdfFixture.write(file)
        val piece = PieceEntity(id = "$id-p", title = "合成练习曲 · UI", composer = "Synthetic Composer")
        val definition = PartDefinitionEntity(id = "$id-d", name = "合成声部 I · $label")
        val member = MemberEntity(id = "$id-m", displayName = "UI 合成成员 A · $label", defaultPartDefinitionId = definition.id)
        val other = MemberEntity(id = "$id-n", displayName = "UI 合成成员 B · $label", defaultPartDefinitionId = definition.id)
        val permission = MemberPartPermissionEntity(memberId = member.id, partDefinitionId = definition.id)
        dao.insertPiece(piece); dao.insertPartDefinition(definition)
        dao.insertPiecePart(PiecePartEntity(id = "$id-part", pieceId = piece.id, partDefinitionId = definition.id))
        dao.insertMember(member); dao.insertMember(other); dao.insertPermission(permission)
        val pdf = ScorePdfEntity(id = "$id-pdf", title = "Synthetic UI PDF")
        dao.insertScorePdf(pdf)
        dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "$id-v", scorePdfId = pdf.id, versionNumber = 1,
            managedFilePath = file.path, originalUri = "content://synthetic-ui", fileHash = hash,
            fileSizeBytes = file.length(), pageCount = 4))
        dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = "$id-part", scorePdfId = pdf.id, startPage = 2, endPage = 3))
        dao.updateScorePdf(pdf.copy(activeVersionId = "$id-v"))
        val concert = ConcertEntity(id = "$id-c", title = "合成排练节目单")
        dao.insertConcert(concert)
        dao.insertConcertItem(ConcertItemEntity(id = "$id-item", concertId = concert.id, pieceId = piece.id, sortOrder = 0))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var preview: ScorePreviewViewModel
        scenario.onActivity { preview = ViewModelProvider(it)[ScorePreviewViewModel::class.java] }
        fun choose(name: String) {
            compose.onNodeWithTag("nav-profile").performClick()
            compose.onNodeWithText("选择成员").performClick()
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithTag("nav-library").performClick()
            await("piece-${piece.id}")
        }
        try {
            await("nav-profile")
            choose(member.displayName)
            compose.onNodeWithTag("library-search").performTextInput("Synthetic Composer")
            compose.onNodeWithTag("piece-${piece.id}").performClick()
            await("preview-page")
            val initial = preview.state.value as ScorePreviewState.Ready
            assertEquals("$id-part", initial.score.part.id)
            assertEquals(2, initial.score.segments.first().startPage)
            capture("app-home-preview")
            compose.onNodeWithTag("preview-open-reader").performClick()
            await("pdf-page")
            assertEquals(ScorePreviewState.Empty, preview.state.value)
            compose.onNodeWithTag("reader-part-name").assertTextEquals(definition.name)
            compose.onNodeWithTag("side-navigation").assertDoesNotExist()
            compose.onNodeWithTag("play-mode").assertExists()
            capture("app-reader")
            compose.onNodeWithTag("back").performClick()
            await("preview-page")
            compose.onNodeWithTag("piece-${piece.id}").assertIsSelected()
            compose.onNodeWithTag("library-search").assertTextContains("Synthetic Composer")
            capture("app-return-selection")
            // Pause hides the live page immediately; resume reauthorizes and renders afresh.
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(ScorePreviewState.Empty, preview.state.value)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("preview-page")
            // A real Room invalidation, not a mocked result, removes the displayed page.
            dao.updatePermission(permission.copy(isArchived = true))
            await("preview-error")
            compose.onNodeWithTag("preview-page").assertDoesNotExist()
            compose.onNodeWithTag("preview-open-reader").assertIsNotEnabled()
            capture("app-revoked")
            dao.updatePermission(permission)
            await("preview-page")
            // Moving away and returning must not reuse a now-missing local file.
            compose.onNodeWithTag("nav-profile").performClick()
            assertEquals(ScorePreviewState.Empty, preview.state.value)
            assertTrue(file.delete())
            compose.onNodeWithTag("nav-library").performClick()
            await("preview-error")
            compose.onNodeWithTag("preview-error").assertTextEquals("本地曲谱文件缺失")
            PdfFixture.write(file)
            compose.onNodeWithTag("preview-retry").performClick()
            await("preview-page")
            compose.onNodeWithTag("nav-concerts").performClick()
            compose.onNodeWithTag("nav-concerts").performClick()
            await("concert-${concert.id}")
            capture("app-concerts")
            compose.onNodeWithTag("concert-${concert.id}").performClick()
            await("concert-item-$id-item")
            compose.onNodeWithTag("concert-item-$id-item").performClick()
            await("pdf-page")
            compose.onNodeWithTag("reader-page-number").assertTextContains("曲目 1 / 1", substring = true)
            compose.onNodeWithTag("play-mode").assertExists()
            capture("app-concert-reader")
            compose.onNodeWithTag("back").performClick()
            choose(other.displayName)
            compose.onNodeWithTag("preview-page").assertDoesNotExist()
            compose.onNodeWithTag("piece-${piece.id}").performClick()
            await("preview-error")
            compose.onNodeWithTag("preview-open-reader").assertIsNotEnabled()
            capture("app-member-without-grant")
        } finally {
            scenario.close()
            dao.updateMember(member.copy(isArchived = true)); dao.updateMember(other.copy(isArchived = true))
            dao.updatePartDefinition(definition.copy(isArchived = true))
            dao.updateConcert(concert.copy(isArchived = true))
            dao.updatePiece(piece.copy(isArchived = true)); dao.updatePermission(permission.copy(isArchived = true))
            file.delete()
        }
    }
}
