package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest

object PdfFixture {
    fun write(file: File): String {
        InstrumentationRegistry.getInstrumentation().context.assets.open("score.pdf").use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
        return hash(file)
    }
    fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    fun segment(file: File, hash: String) = ResolvedScoreSegment("binding", "movement", "pdf", "v", file.path, hash, 2, 3, CacheStatus.NOT_CACHED)
}

@RunWith(AndroidJUnit4::class)
class NativePdfEngineTest {
    @Test fun roomActiveVersionSwitchDoesNotReadExistingOldPdf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "version-switch-test.pdf")
        val hash = PdfFixture.write(file)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase::class.java).build()
        try { runBlocking {
            val dao = db.libraryDao()
            dao.insertPiece(com.ensemble.scorelibrary.core.database.entity.PieceEntity(id = "p", title = "Piece"))
            dao.insertPartDefinition(com.ensemble.scorelibrary.core.database.entity.PartDefinitionEntity(id = "d", name = "I"))
            dao.insertPiecePart(com.ensemble.scorelibrary.core.database.entity.PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
            dao.insertMember(com.ensemble.scorelibrary.core.database.entity.MemberEntity(id = "m", displayName = "Member", defaultPartDefinitionId = "d"))
            dao.insertPermission(com.ensemble.scorelibrary.core.database.entity.MemberPartPermissionEntity(memberId = "m", partDefinitionId = "d"))
            val pdf = com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity(id = "pdf", title = "Score")
            dao.insertScorePdf(pdf)
            val v1 = com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity(id = "v1", scorePdfId = "pdf", versionNumber = 1,
                managedFilePath = file.path, originalUri = "content://fixture", fileHash = hash, fileSizeBytes = file.length(), pageCount = 4)
            dao.insertScorePdfVersion(v1)
            dao.insertScorePdfVersion(v1.copy(id = "v2", versionNumber = 2, managedFilePath = "missing-v2.pdf"))
            dao.insertPartScoreBinding(com.ensemble.scorelibrary.core.database.entity.PartScoreBindingEntity(piecePartId = "part", scorePdfId = "pdf", startPage = 2, endPage = 3))
            dao.updateScorePdf(pdf.copy(activeVersionId = "v1"))
            val repository = com.ensemble.scorelibrary.data.RoomScoreRepository(db)
            val engine = NativePdfEngine(context)
            val first = (repository.resolvePiece("m", "p") as com.ensemble.scorelibrary.domain.DomainResult.Success).value
            engine.inspect(first.segments)
            dao.updateScorePdf(pdf.copy(activeVersionId = "v2"))
            val current = (repository.resolvePiece("m", "p") as com.ensemble.scorelibrary.domain.DomainResult.Success).value
            assertEquals("v2", current.segments.single().versionId)
            try { engine.inspect(current.segments); fail("Must not fall back to old file") }
            catch (error: PdfFailure) { assertEquals(ReaderError.FILE_MISSING, error.reason) }
        } } finally { db.close(); file.delete() }
    }
    @Test fun opensRealPdfAndRendersDifferentPageSizesAtNativeViewportResolution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "real-pdf-test.pdf")
        val hash = PdfFixture.write(file)
        try { runBlocking {
            val engine = NativePdfEngine(context)
            val segment = PdfFixture.segment(file, hash)
            assertEquals(PageSize(600, 900), engine.inspect(listOf(segment)).single())
            listOf(800 to 1000, 2160 to 3500).forEach { (w, h) ->
                val frame = engine.render(PdfPageRef(segment, 2), w, h, FitMode.PAGE, 0f)
                assertEquals(w, frame.bitmap.width); assertEquals(h, frame.bitmap.height)
                assertEquals(PageSize(600, 1200), frame.transform.page)
                var dark = 0
                for (y in 0 until h step 4) for (x in 0 until w step 4) if (Color.red(frame.bitmap.getPixel(x, y)) < 100) dark++
                assertTrue("Real PDF must render visible music", dark > 50)
                frame.bitmap.recycle()
            }
        } } finally { file.delete() }
    }
    @Test fun missingCorruptedWrongHashAndOutOfRangeAreExplicitFailures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "error-pdf-test.pdf")
        val hash = PdfFixture.write(file)
        try { runBlocking {
            val engine = NativePdfEngine(context)
            val segment = PdfFixture.segment(file, hash)
            suspend fun failure(expected: ReaderError, segment: ResolvedScoreSegment) {
                try { engine.inspect(listOf(segment)); fail("Expected PDF failure") }
                catch (error: PdfFailure) { assertEquals(expected, error.reason) }
            }
            failure(ReaderError.FILE_MISSING, segment.copy(managedFilePath = "missing"))
            failure(ReaderError.CORRUPTED, segment.copy(fileHash = "wrong"))
            failure(ReaderError.ACTIVE_VERSION_MISSING, segment.copy(versionId = ""))
            failure(ReaderError.INVALID_PAGE_RANGE, segment.copy(endPage = 5))
            assertEquals(1, engine.inspect(listOf(segment.copy(managedFilePath = "missing", cacheStatus = CacheStatus.CACHED, cachedFilePath = file.path))).size)
            failure(ReaderError.FILE_MISSING, segment.copy(managedFilePath = "missing", cacheStatus = CacheStatus.OUTDATED, cachedFilePath = file.path))
            try { engine.render(PdfPageRef(segment, 0), 800, 1000, FitMode.PAGE, 0f); fail("Unauthorized page must fail") }
            catch (error: PdfFailure) { assertEquals(ReaderError.INVALID_PAGE_RANGE, error.reason) }
            try { engine.render(PdfPageRef(segment, 1), 20_000, 20_000, FitMode.PAGE, 0f); fail("Oversized viewport must fail") }
            catch (error: PdfFailure) { assertEquals(ReaderError.LOAD_FAILED, error.reason) }
            file.writeText("not a PDF")
            failure(ReaderError.CORRUPTED, segment.copy(fileHash = PdfFixture.hash(file)))
        } } finally { file.delete() }
    }
}
