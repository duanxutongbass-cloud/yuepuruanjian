package com.ensemble.scorelibrary.data

import android.content.ContentValues
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.DomainResult
import com.ensemble.scorelibrary.pdf.NativePdfEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ManagementImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(pages: Int = 4): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "management-${UUID.randomUUID()}.pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            }))
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            if (pages == 4) InstrumentationRegistry.getInstrumentation().context.assets.open("score.pdf").use { it.copyTo(output) }
            else if (pages == 0) output.write("damaged PDF".toByteArray())
            else {
                val document = PdfDocument()
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    document.finishPage(page); document.writeTo(output)
                } finally { document.close() }
            }
        }
        return uri
    }
    private fun files() = File(context.filesDir, "scores").listFiles()?.toSet().orEmpty()
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Must reject") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun realReplacementRollbackKeepsOldFilesAndVersionScopedInkWithoutGrants() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val uri = fixture(); val before = files()
        try {
            val quick = LocalAdministration(context, db)
            val admin = quick.createMember(null, "Admin"); val member = quick.createMember(admin, "Member")
            val piece = quick.importScore(admin, uri, ImportScoreRequest("Piece", "Violin I", member, 2, 3))
            val repository = RoomScoreRepository(db); val engine = NativePdfEngine(context)
            val management = LibraryManagement(context, db, engine)
            val original = (repository.resolvePiece(member, piece) as DomainResult.Success).value
            val segment = original.segments.single()
            val scope = AnnotationScope(member, piece, original.part.id, segment.versionId, 1)
            val ink = InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.2f, .3f)))
            val annotations = AnnotationRepository(db)
            annotations.save(scope, AnnotationLayer.PERSONAL, listOf(ink), 0)
            management.importPdf(admin, uri, "Replacement", segment.scorePdfId, db.resolutionDao().snapshot())
            val replacement = (repository.resolvePiece(member, piece) as DomainResult.Success).value.segments.single()
            assertNotEquals(segment.versionId, replacement.versionId)
            assertNotEquals(segment.managedFilePath, replacement.managedFilePath)
            assertEquals(2, replacement.startPage); assertEquals(3, replacement.endPage)
            engine.inspect(listOf(replacement))
            assertTrue(File(segment.managedFilePath).exists())
            assertTrue(annotations.load(scope.copy(version = replacement.versionId)).personal.isEmpty())
            rejected { annotations.load(scope) }
            assertTrue((repository.visibleParts(admin, piece) as DomainResult.Success).value.isEmpty())
            management.activateVersion(admin, segment.scorePdfId, segment.versionId, db.resolutionDao().snapshot())
            engine.inspect((repository.resolvePiece(member, piece) as DomainResult.Success).value.segments)
            assertEquals(listOf(ink), annotations.load(scope).personal)
            assertEquals(listOf(1, 2), db.resolutionDao().versions().map { it.versionNumber }.sorted())
        } finally {
            db.close(); context.contentResolver.delete(uri, null, null)
            files().filter { it !in before }.forEach { it.delete() }
        }
    }

    @Test fun invalidStaleOrUnauthorizedReplacementLeavesNoRowsOrOrphanFiles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val good = fixture(); val short = fixture(1); val bad = fixture(0); val before = files()
        try {
            val quick = LocalAdministration(context, db)
            val admin = quick.createMember(null, "Admin"); val member = quick.createMember(admin, "Member")
            quick.importScore(admin, good, ImportScoreRequest("Piece", "I", member, 2, 3))
            val management = LibraryManagement(context, db, NativePdfEngine(context))
            val original = db.resolutionDao().snapshot(); val pdf = original.pdfs.single().id; val owned = files()
            for (uri in listOf(short, bad)) {
                rejected { management.importPdf(admin, uri, "Invalid", pdf, original) }
                assertEquals(original, db.resolutionDao().snapshot()); assertEquals(owned, files())
            }
            rejected { management.importPdf(member, good, "Forbidden", pdf) }
            assertEquals(owned, files())
            management.edit(admin, ManagementCommand.Piece(original.pieces.single().copy(title = "Fresh")))
            val fresh = db.resolutionDao().snapshot()
            rejected { management.importPdf(admin, good, "Stale", pdf, original) }
            assertEquals(fresh, db.resolutionDao().snapshot()); assertEquals(owned, files())
            val version = fresh.versions.single(); val file = File(version.managedFilePath)
            val bytes = file.readBytes()
            file.writeBytes("corrupted after import".toByteArray())
            try {
                try { management.activateVersion(admin, pdf, version.id); fail("Corrupted version must reject") }
                catch (_: Exception) { }
                assertEquals(fresh, db.resolutionDao().snapshot())
            } finally { file.writeBytes(bytes) }
        } finally {
            db.close(); listOf(good, short, bad).forEach { context.contentResolver.delete(it, null, null) }
            files().filter { it !in before }.forEach { it.delete() }
        }
    }
}
