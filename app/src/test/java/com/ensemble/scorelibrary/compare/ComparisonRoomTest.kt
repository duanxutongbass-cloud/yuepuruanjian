package com.ensemble.scorelibrary.compare

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.data.RoomPresentationRepository
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComparisonRoomTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val db by lazy { Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build() }
    private val observed = mutableListOf<PdfPageRef>()
    private val originals = mutableMapOf<File, String>()
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); db.close(); Dispatchers.resetMain(); originals.forEach { (file, hash) -> assertEquals(hash, digest(file)) } }
    private suspend fun python(script: String, vararg args: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python", "-c", script) + args)
            .redirectErrorStream(true).redirectOutput(File(temporary.root, "pypdf.log")).start()
        try { check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { "Independent pypdf validation failed" } }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
    private suspend fun seed(grant: Boolean = true) {
        val root = temporary.root
        python("from pypdf import PdfWriter,PdfReader; import pathlib,sys; r=pathlib.Path(sys.argv[1]);\nfor n in ['a','b']:\n w=PdfWriter();[w.add_blank_page(400,600) for _ in range(3)];w.add_metadata({'/Title':n});w.write(r/(n+'.pdf'));assert len(PdfReader(r/(n+'.pdf'),strict=True).pages)==3", root.path)
        val dao = db.libraryDao()
        dao.insertPartDefinition(PartDefinitionEntity(id = "violin", name = "小提琴"))
        dao.insertMember(MemberEntity(id = "m", displayName = "合成管理员", role = MemberRole.ADMIN, defaultPartDefinitionId = "violin"))
        for ((index, id) in listOf("a", "b").withIndex()) {
            val file = File(root, "$id.pdf"); val hash = digest(file); originals[file] = hash
            dao.insertPiece(PieceEntity(id = id, title = "曲目$id", organizerSequence = if (index == 0) "001" else "037", categoryName = "电影"))
            dao.insertPiecePart(PiecePartEntity(id = "$id-part", pieceId = id, partDefinitionId = "violin"))
            dao.insertScorePdf(ScorePdfEntity(id = "$id-pdf", title = id))
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "$id-v1", scorePdfId = "$id-pdf", versionNumber = 1,
                managedFilePath = file.path, originalUri = file.toURI().toString(), fileHash = hash, fileSizeBytes = file.length(), pageCount = 3))
            dao.updateScorePdf(ScorePdfEntity(id = "$id-pdf", title = id, activeVersionId = "$id-v1"))
            dao.insertPartScoreBinding(PartScoreBindingEntity(id = "$id-bind", piecePartId = "$id-part", scorePdfId = "$id-pdf", startPage = 2, endPage = 3))
            if (grant) dao.insertExtraPermission(MemberPiecePartPermissionEntity(id = "$id-grant", memberId = "m", piecePartId = "$id-part"))
        }
    }
    private fun model(): ScoreComparisonViewModel {
        val engine = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
                for (segment in segments) {
                    val file = File(segment.managedFilePath)
                    if (!file.isFile) throw PdfFailure(ReaderError.FILE_MISSING)
                    if (digest(file) != segment.fileHash) throw PdfFailure(ReaderError.CORRUPTED)
                    python("from pypdf import PdfReader;import sys;r=PdfReader(sys.argv[1],strict=True);assert 1<=int(sys.argv[2])<=int(sys.argv[3])<=len(r.pages)",
                        file.path, segment.startPage.toString(), segment.endPage.toString())
                }
                return segments.map { PageSize(400, 600) }
            }
            // Desktop Robolectric cannot run Android's native PDF renderer; bitmap rendering is a fixture.
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
                observed += page
                return PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(400, 600), width, height, mode))
            }
        }
        return ScoreComparisonViewModel(RoomPresentationRepository(db), engine).also {
            models.put("compare", it); it.open("m", Any()); it.viewport(0, 100, 150); it.viewport(1, 100, 150)
        }
    }
    private suspend fun settle(vm: ScoreComparisonViewModel, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            dispatcher.scheduler.runCurrent()
            check(System.nanoTime() < deadline) { "Comparison state: ${vm.state.value}" }
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
        dispatcher.scheduler.runCurrent()
    }
    private suspend fun select(vm: ScoreComparisonViewModel, side: Int, piece: String) {
        vm.choosePiece(side, piece); settle(vm) { !vm.state.value.panes[side].loading }
        vm.choosePart(side, "$piece-part"); settle(vm) { vm.state.value.panes[side].image != null || vm.state.value.panes[side].error != null }
    }
    @Test fun actualRoomRevocationRemovesOnlyItsSideAndLeavesPdfAndNumbersUnchanged() = runTest(dispatcher) {
        seed(); val vm = model(); select(vm, 0, "a"); select(vm, 1, "b")
        val old = vm.state.value.panes[0].image!!
        db.libraryDao().updateExtraPermission(MemberPiecePartPermissionEntity(id = "a-grant", memberId = "m", piecePartId = "a-part", isArchived = true))
        settle(vm) { vm.state.value.panes[0].image == null && vm.state.value.panes[0].error != null }
        assertFalse(old.canDisplay()); assertNotNull(vm.state.value.panes[1].image)
        assertEquals(listOf("001", "037"), db.resolutionDao().pieces().sortedBy { it.id }.map { it.organizerSequence })
        assertTrue(observed.all { it.pageIndex == 1 })
    }
    @Test fun adminMetadataAloneCannotReadPdfWithoutSpecificGrant() = runTest(dispatcher) {
        seed(grant = false); val vm = model(); vm.choosePiece(0, "a")
        settle(vm) { !vm.state.value.panes[0].loading }
        assertTrue(vm.state.value.panes[0].parts.isEmpty())
        vm.choosePart(0, "a-part"); dispatcher.scheduler.runCurrent()
        assertTrue(observed.isEmpty()); assertNull(vm.state.value.panes[0].image)
    }
    @Test fun activeMissingNewVersionNeverFallsBackToReadableHistoricalPdf() = runTest(dispatcher) {
        seed(); val vm = model(); select(vm, 0, "a")
        val old = vm.state.value.panes[0].image!!
        val dao = db.libraryDao()
        dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "a-v2", scorePdfId = "a-pdf", versionNumber = 2,
            managedFilePath = File(temporary.root, "missing-v2.pdf").path, originalUri = "content://fixture/missing", fileHash = "f".repeat(64), fileSizeBytes = 1, pageCount = 3))
        dao.updateScorePdf(ScorePdfEntity(id = "a-pdf", title = "a", activeVersionId = "a-v2"))
        settle(vm) { vm.state.value.panes[0].error != null }
        assertEquals("本地曲谱文件缺失", vm.state.value.panes[0].error)
        assertFalse(old.canDisplay()); assertEquals(1, observed.size); assertTrue(File(temporary.root, "a.pdf").isFile)
    }
    @Test fun changedDeclaredHashRejectsOldFrameAndRetainsOtherSide() = runTest(dispatcher) {
        seed(); val vm = model(); select(vm, 0, "a"); select(vm, 1, "b")
        val old = vm.state.value.panes[0].image!!
        val version = db.libraryDao().getScorePdfVersions("a-pdf").single()
        db.libraryDao().updateVersion(version.copy(fileHash = "0".repeat(64)))
        settle(vm) { vm.state.value.panes[0].error != null }
        assertEquals("曲谱文件损坏或内容与当前版本不一致", vm.state.value.panes[0].error)
        assertFalse(old.canDisplay()); assertNotNull(vm.state.value.panes[1].image)
        assertEquals(2, observed.size)
    }
}
