package com.ensemble.scorelibrary.annotation

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.DomainResult
import com.ensemble.scorelibrary.data.RoomScoreRepository
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 31)
class AnnotationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ViewModelStore()
    private var db: ScoreLibraryDatabase? = null
    private var file: File? = null
    private val currentScore = androidx.compose.runtime.mutableStateOf<com.ensemble.scorelibrary.domain.ResolvedConcertItem?>(null)
    private val currentConcert = androidx.compose.runtime.mutableStateOf<com.ensemble.scorelibrary.domain.ResolvedConcert?>(null)
    private val contentWidth = androidx.compose.runtime.mutableStateOf(2000.dp)
    private val canManage = androidx.compose.runtime.mutableStateOf(true)
    private val renderCount = java.util.concurrent.atomic.AtomicInteger()
    @After fun close() { store.clear(); db?.close(); file?.delete() }
    private fun open(width: Int, height: Int): Pair<AnnotationViewModel, PdfReaderViewModel> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("wm size ${width}x${height}")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("wm density 160")).use { it.readBytes() }
        compose.activityRule.scenario.recreate()
        val database = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build().also { db = it }
        val fixture = File(context.filesDir, "annotation-ui.pdf").also { file = it }
        val hash = PdfFixture.write(fixture)
        runBlocking {
            val dao = database.libraryDao()
            dao.insertPiece(PieceEntity(id = "p", title = "Annotated score"))
            dao.insertPartDefinition(PartDefinitionEntity(id = "d", name = "Violin I"))
            dao.insertPiecePart(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
            dao.insertMember(MemberEntity(id = "a", displayName = "Admin", role = MemberRole.ADMIN, defaultPartDefinitionId = "d"))
            dao.insertPermission(MemberPartPermissionEntity(memberId = "a", partDefinitionId = "d"))
            dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "Score"))
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
                managedFilePath = fixture.path, originalUri = "content://fixture", fileHash = hash, fileSizeBytes = fixture.length(), pageCount = 4))
            dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = "v"))
            dao.insertPartScoreBinding(PartScoreBindingEntity(id = "b", piecePartId = "part", scorePdfId = "pdf", startPage = 2, endPage = 3))
        }
        val score = runBlocking { (RoomScoreRepository(database).resolvePiece("a", "p") as DomainResult.Success).value }
        currentScore.value = score
        val annotations = AnnotationViewModel(AnnotationRepository(database)); store.put("annotations", annotations)
        val native = NativePdfEngine(context)
        val engine = object : PdfEngine {
            override suspend fun inspect(segments: List<com.ensemble.scorelibrary.domain.ResolvedScoreSegment>) = native.inspect(segments)
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
                renderCount.incrementAndGet()
                return native.render(page, width, height, mode, scroll)
            }
        }
        val reader = PdfReaderViewModel(engine, SavedStateHandle(), PerformancePreparation(engine, AnnotationRepository(database))); store.put("reader", reader)
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { Surface(Modifier.widthIn(max = contentWidth.value).fillMaxSize()) {
            CompositionLocalProvider(LocalAnnotations provides annotations) { PdfReaderScreen("a", requireNotNull(currentScore.value), reader,
                canManage = canManage.value, concert = currentConcert.value) }
        } } } }
        compose.waitUntil(20_000) { reader.state.value is PdfState.Ready && annotations.state.value.data != null && !annotations.state.value.transitionBlocked }
        return annotations to reader
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase5-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        var red = 0
        for (y in 180 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val color = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 100 && android.graphics.Color.blue(color) < 100) red++
        }
        assertTrue("Real screenshot must show red annotation pixels", red > 30)
    }
    @Test fun standardPadDrawsPersistsUndoRedoAndKeepsSourcePageIsolation() {
        val (annotations, reader) = open(800, 1200)
        compose.onNodeWithTag("annotation-手写").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { swipe(Offset(width * .25f, height * .45f), Offset(width * .75f, height * .45f), 400) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.personal?.size == 1 }
        val mark = annotations.state.value.data!!.personal.single()
        assertTrue(mark.points.all { it.x in 0f..1f && it.y in 0f..1f })
        screenshot("standard-pad")
        compose.onNodeWithTag("annotation-撤销").performClick()
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.personal?.isEmpty() == true }
        compose.onNodeWithTag("annotation-重做").performClick()
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.personal?.size == 1 }
        assertEquals(listOf(mark), runBlocking { AnnotationRepository(db!!).load(annotations.state.value.scope!!).personal })
        compose.onNodeWithTag("next-page").performClick()
        compose.waitUntil(20_000) { (reader.state.value as? PdfState.Ready)?.sourcePage == 3 && annotations.state.value.scope?.page == 2 && !annotations.state.value.transitionBlocked }
        assertTrue(annotations.state.value.data!!.personal.isEmpty())
    }
    @Test fun largePadSymbolsLassoMoveAndEraseAreUndoable() {
        val (annotations, reader) = open(1280, 1800)
        compose.onNodeWithTag("annotation-音乐符号").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { click(Offset(width * .4f, height * .4f)) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.personal?.size == 1 }
        assertEquals(MarkKind.SYMBOL, annotations.state.value.data!!.personal.single().kind)
        screenshot("large-pad")
        compose.onNodeWithTag("annotation-套索").performClick()
        val transform = (reader.state.value as PdfState.Ready).frame.transform
        fun location(x: Float, y: Float): Offset = transform.normalizedToScreen(x, y).let { Offset(it.x, it.y) }
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            down(location(.1f, .1f)); moveTo(location(.9f, .1f)); moveTo(location(.9f, .9f))
            moveTo(location(.1f, .9f)); moveTo(location(.1f, .1f)); up()
        }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.selection.size == 1 }
        val original = annotations.state.value.marks.single().points.first()
        compose.onNodeWithTag("annotation-移动").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { swipe(location(.3f, .3f), location(.4f, .4f), 400) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.marks.single().points.first() != original }
        val moved = annotations.state.value.marks.single().points.first()
        compose.onNodeWithTag("annotation-橡皮").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { click(location(moved.x, moved.y)) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.marks.isEmpty() }
        compose.onNodeWithTag("annotation-撤销").performClick()
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.marks.size == 1 }
        val before = annotations.state.value.marks
        compose.onNodeWithText("适合宽度").performClick()
        compose.waitUntil(20_000) { (reader.state.value as? PdfState.Ready)?.mode == FitMode.WIDTH }
        assertEquals(before, annotations.state.value.marks)
        compose.runOnIdle { contentWidth.value = 800.dp }
        compose.waitUntil(20_000) { (reader.state.value as? PdfState.Ready)?.frame?.bitmap?.width == 800 }
        assertEquals(before, annotations.state.value.marks)
    }
    @Test fun symbolsAreSelectableAndEditLockCanBeReleasedWithoutLosingInk() {
        val (annotations, reader) = open(800, 1200)
        compose.onNodeWithTag("annotation-音乐符号").performClick()
        compose.onNodeWithTag("music-symbol-picker").performClick()
        compose.onNodeWithTag("symbol-mp").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { click(Offset(width * .4f, height * .4f)) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.marks.size == 1 }
        assertEquals("mp", annotations.state.value.marks.single().symbol)
        compose.onNodeWithTag("annotation-锁定编辑").performClick()
        compose.onNodeWithTag("annotation-手写").assertIsNotEnabled()
        compose.onNodeWithTag("annotation-解除编辑锁定").performClick()
        compose.onNodeWithTag("annotation-手写").assertIsEnabled()
        assertEquals("mp", annotations.state.value.marks.single().symbol)
        compose.onNodeWithTag("annotation-手写").performClick()
        val t = (reader.state.value as PdfState.Ready).frame.transform
        val start = t.normalizedToScreen(.8f, .5f); val end = t.normalizedToScreen(.2f, .5f)
        compose.onNodeWithTag("annotation-canvas").performTouchInput { swipe(Offset(start.x, start.y), Offset(end.x, end.y), 400) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.marks.size == 2 }
        assertEquals(2, (reader.state.value as PdfState.Ready).sourcePage)
    }
    @Test fun sharedPublishAndConcertFreezeAreRealUiActions() {
        val (annotations, reader) = open(800, 1200)
        compose.onNodeWithText("共享草稿").performClick()
        compose.onNodeWithTag("annotation-手写").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { swipe(Offset(width * .25f, height * .45f), Offset(width * .75f, height * .45f), 400) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.draft?.size == 1 }
        compose.onNodeWithTag("annotation-发布当前页").performClick()
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.shared?.size == 1 }
        val published = annotations.state.value.data!!.shared
        val score = runBlocking {
            val dao = db!!.libraryDao()
            dao.insertConcert(ConcertEntity(id = "c", title = "Concert"))
            dao.insertConcertItem(ConcertItemEntity(id = "i", concertId = "c", pieceId = "p", sortOrder = 0))
            (RoomScoreRepository(db!!).resolveConcert("a", "c") as DomainResult.Success).value.items.single()
        }
        compose.runOnIdle { currentScore.value = score }
        compose.waitUntil(20_000) { reader.state.value is PdfState.Ready && annotations.state.value.data?.concert == "c" && !annotations.state.value.transitionBlocked }
        compose.onNodeWithTag("annotation-冻结本场共享版本").performClick()
        compose.onNodeWithText("冻结本场", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.locked == true }
        val libraryScope = annotations.state.value.scope!!.copy(item = null)
        runBlocking {
            val repo = AnnotationRepository(db!!)
            repo.save(libraryScope, AnnotationLayer.SHARED, emptyList(), 1)
            repo.publish(libraryScope)
        }
        compose.runOnIdle { annotations.retry() }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.locked == true }
        assertEquals(published, annotations.state.value.data!!.shared)
        compose.onNodeWithText("共享批注").performClick()
        compose.onNodeWithTag("annotation-手写").assertIsNotEnabled()
        compose.onNodeWithTag("annotation-发布当前页").assertDoesNotExist()
    }
    @Test fun roleDowngradeHidesCachedDraftWithoutWaitingForAnotherSave() {
        val (annotations, _) = open(800, 1200)
        compose.onNodeWithText("共享草稿").performClick()
        compose.onNodeWithTag("annotation-手写").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput { swipe(Offset(width * .25f, height * .45f), Offset(width * .75f, height * .45f), 400) }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.draft?.size == 1 }
        runBlocking {
            val member = db!!.resolutionDao().members().single()
            db!!.libraryDao().updateMember(member.copy(role = MemberRole.MEMBER))
        }
        compose.runOnIdle { canManage.value = false }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.admin == false }
        assertTrue(annotations.state.value.data!!.draft.isEmpty())
        assertTrue(annotations.state.value.data!!.shared.isEmpty())
        compose.onNodeWithText("共享草稿").assertDoesNotExist()
        compose.onNodeWithTag("annotation-发布当前页").assertDoesNotExist()
        compose.onNodeWithTag("annotation-手写").assertIsNotEnabled()
    }
    @Test fun corruptInkErrorRemainsVisibleWithoutAViewportRenderLoop() {
        val (annotations, reader) = open(800, 1200)
        runBlocking { db!!.annotationDao().insertPage(AnnotationPageEntity(
            versionId = "v", pageIndex = 1, ownerKey = "a", payload = "invalid-json")) }
        compose.runOnIdle { annotations.retry() }
        compose.waitUntil(10_000) { annotations.state.value.error != null && reader.state.value is PdfState.Ready }
        compose.waitForIdle()
        val count = renderCount.get()
        Thread.sleep(1500)
        compose.waitForIdle()
        assertTrue("Error state must settle, not trigger repeated native renders", renderCount.get() - count <= 1)
        assertTrue(reader.state.value is PdfState.Ready)
        compose.onNodeWithTag("annotation-error").assertExists()
        compose.onNodeWithTag("annotation-手写").assertIsNotEnabled()
    }
    @Test fun playModeDoesNotRenderAdministratorUnpublishedDraft() {
        val (annotations, reader) = open(800, 1200)
        compose.onNodeWithText("共享草稿").performClick()
        compose.onNodeWithTag("annotation-手写").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            swipe(Offset(width * .25f, height * .45f), Offset(width * .75f, height * .45f), 400)
        }
        compose.waitUntil(10_000) { !annotations.state.value.transitionBlocked && annotations.state.value.data?.draft?.size == 1 }
        assertFalse(annotations.state.value.gestureActive)
        assertTrue(annotations.state.value.data!!.shared.isEmpty())
        compose.onNodeWithTag("play-mode").performClick()
        compose.waitUntil(20_000) { reader.playMode.value && reader.state.value is PdfState.Ready &&
            annotations.state.value.tool == AnnotationTool.READ }
        compose.onNodeWithTag("annotation-手写").assertDoesNotExist()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        var red = 0
        for (y in 180 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val color = bitmap.getPixel(x,y)
            if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 100 && android.graphics.Color.blue(color) < 100) red++
        }
        assertEquals("Unpublished red draft must not appear in play mode", 0, red)
        compose.onNodeWithTag("play-mode").performClick()
        compose.onNodeWithTag("annotation-手写").assertExists()
        assertEquals(1, annotations.state.value.data!!.draft.size)
    }
    @Test fun enteringPerformanceRefreshesCachedRehearsalInkToFrozenSnapshot() {
        val (annotations, reader) = open(800,1200)
        val red = InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.2f,.3f),InkPoint(.8f,.3f)))
        val blue = red.copy(color = 0xff1565c0)
        val concert = runBlocking {
            val repo = AnnotationRepository(db!!)
            val scope = AnnotationScope("a","p","part","v",1)
            repo.save(scope,AnnotationLayer.SHARED,listOf(red),0); repo.publish(scope)
            db!!.libraryDao().insertConcert(ConcertEntity(id="c",title="Concert"))
            db!!.libraryDao().insertConcertItem(ConcertItemEntity(id="i",concertId="c",pieceId="p",sortOrder=0))
            (RoomScoreRepository(db!!).resolveConcert("a","c") as DomainResult.Success).value
        }
        compose.runOnIdle { currentConcert.value=concert; currentScore.value=concert.items.single() }
        compose.waitUntil(20_000) { reader.state.value is PdfState.Ready && annotations.state.value.data?.concert=="c" && !annotations.state.value.transitionBlocked }
        assertEquals(listOf(red),annotations.state.value.data!!.shared)
        runBlocking {
            val repo=AnnotationRepository(db!!); val scope=AnnotationScope("a","p","part","v",1)
            repo.save(scope,AnnotationLayer.SHARED,listOf(blue),1); repo.publish(scope)
            repo.freeze(scope.copy(item="i"))
        }
        assertFalse(annotations.state.value.data!!.locked)
        compose.onNodeWithTag("play-mode").performClick()
        compose.waitUntil(20_000) { reader.playMode.value && reader.state.value is PdfState.Ready &&
            annotations.state.value.data?.locked==true && !annotations.state.value.transitionBlocked }
        assertEquals(listOf(blue),annotations.state.value.data!!.shared)
        compose.onNodeWithTag("annotation-手写").assertDoesNotExist()
    }
}
