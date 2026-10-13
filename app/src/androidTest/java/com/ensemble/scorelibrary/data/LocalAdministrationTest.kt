package com.ensemble.scorelibrary.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.model.MemberRole
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
class LocalAdministrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(corrupt: Boolean = false): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "score-${UUID.randomUUID()}.pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            }))
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            if (corrupt) output.write("not a PDF".toByteArray())
            else InstrumentationRegistry.getInstrumentation().context.assets.open("score.pdf").use { it.copyTo(output) }
        }
        return uri
    }
    @Test fun bootstrapIsOneTimeAndMemberCannotWrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        try {
            val service = LocalAdministration(context, db)
            val admin = service.createMember(null, "Admin")
            val member = service.createMember(admin, "Member")
            assertEquals(MemberRole.ADMIN, db.resolutionDao().members().first { it.id == admin }.role)
            assertEquals(MemberRole.MEMBER, db.resolutionDao().members().first { it.id == member }.role)
            for (actor in listOf(null, member)) {
                try { service.createMember(actor, "Forbidden"); fail("Must reject") } catch (_: IllegalArgumentException) {
                } catch (_: IllegalStateException) { }
            }
            assertEquals(2, db.resolutionDao().members().size)
        } finally { db.close() }
    }
    @Test fun realContentImportGrantsOnlyRecipientAndResolvesOrderedConcert() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val uri = fixture()
        val before = File(context.filesDir, "scores").listFiles()?.toSet().orEmpty()
        try {
            val service = LocalAdministration(context, db)
            val admin = service.createMember(null, "Admin")
            val member = service.createMember(admin, "Member")
            val other = service.createMember(admin, "Other")
            val first = service.importScore(admin, uri, ImportScoreRequest("First", "Violin I", member, 2, 3, "Concert"))
            val second = service.importScore(admin, uri, ImportScoreRequest("Second", "Violin I", member, concertTitle = "Concert"))
            val repository = RoomScoreRepository(db)
            val score = (repository.resolvePiece(member, first) as DomainResult.Success).value
            assertEquals(2, score.segments.single().startPage)
            assertEquals(3, score.segments.single().endPage)
            NativePdfEngine(context).inspect(score.segments)
            assertTrue((repository.visibleParts(other, first) as DomainResult.Success).value.isEmpty())
            assertTrue((repository.visibleParts(admin, first) as DomainResult.Success).value.isEmpty())
            assertTrue(db.resolutionDao().permissions().isEmpty())
            val concert = db.resolutionDao().concerts().single()
            assertEquals(listOf(first, second), (repository.resolveConcert(member, concert.id) as DomainResult.Success).value.items.map { it.pieceId })
            val version = db.resolutionDao().versions().first()
            assertEquals(64, version.fileHash.length)
            assertEquals(4, version.pageCount)
            assertEquals(version.id, db.resolutionDao().pdfs().first().activeVersionId)
            assertTrue(File(version.managedFilePath).length() > 0)
            val both = service.importScore(admin, uri, ImportScoreRequest("Shared test", "Violin I", member,
                concertTitle = "Locked concert", grantAdministrator = true))
            assertEquals(1, (repository.visibleParts(admin, both) as DomainResult.Success).value.size)
            assertEquals(1, (repository.visibleParts(member, both) as DomainResult.Success).value.size)
            assertTrue((repository.visibleParts(other, both) as DomainResult.Success).value.isEmpty())
            assertTrue(db.resolutionDao().permissions().isEmpty())
            val bothScore = (repository.resolvePiece(admin, both) as DomainResult.Success).value
            val bothItem = db.resolutionDao().items().first { it.pieceId == both }
            com.ensemble.scorelibrary.annotation.AnnotationRepository(db).freeze(
                com.ensemble.scorelibrary.annotation.AnnotationScope(admin, both, bothScore.part.id,
                    bothScore.segments.single().versionId, 0, bothItem.id))
            val count = db.resolutionDao().pieces().size
            val files = File(context.filesDir, "scores").listFiles()?.toSet().orEmpty()
            try {
                service.importScore(admin, uri, ImportScoreRequest("Late", "Violin I", member, concertTitle = "Locked concert"))
                fail("Locked concert must reject import append")
            } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("已锁定")) }
            assertEquals(count, db.resolutionDao().pieces().size)
            assertEquals(files, File(context.filesDir, "scores").listFiles()?.toSet().orEmpty())
        } finally {
            db.close(); context.contentResolver.delete(uri, null, null)
            File(context.filesDir, "scores").listFiles()?.filter { it !in before }?.forEach { it.delete() }
        }
    }
    @Test fun corruptRangeAndMissingRecipientLeaveNoRowsOrOrphanFiles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val good = fixture(); val bad = fixture(true)
        val before = File(context.filesDir, "scores").listFiles()?.toSet().orEmpty()
        try {
            val service = LocalAdministration(context, db)
            val admin = service.createMember(null, "Admin")
            val cases = listOf(bad to ImportScoreRequest("Bad", "I", admin),
                good to ImportScoreRequest("Range", "I", admin, 2, 5),
                good to ImportScoreRequest("Member", "I", "missing"))
            for ((uri, request) in cases) {
                try { service.importScore(admin, uri, request); fail("Must reject") }
                catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            }
            assertTrue(db.resolutionDao().pieces().isEmpty())
            assertTrue(db.resolutionDao().versions().isEmpty())
            assertTrue(db.resolutionDao().definitions().isEmpty())
            assertEquals(before, File(context.filesDir, "scores").listFiles()?.toSet().orEmpty())
        } finally {
            db.close(); context.contentResolver.delete(good, null, null); context.contentResolver.delete(bad, null, null)
        }
    }
}
