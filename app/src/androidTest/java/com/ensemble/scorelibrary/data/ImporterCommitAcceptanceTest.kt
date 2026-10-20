package com.ensemble.scorelibrary.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ImporterCommitAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "commit-${UUID.randomUUID()}.pdf"); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf") }))
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("score.pdf").use { it.copyTo(output) }
        }
        return uri
    }
    private fun files() = File(context.filesDir, "scores").listFiles()?.toSet().orEmpty()
    @Test fun failedRoomCommitRollsBackRowsAndDeletesStagedFile() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val uri = fixture(); val before = files()
        try {
            try {
                ManagedPdfImporter(context).publish(uri) { staged -> db.withTransaction {
                    db.libraryDao().insertScorePdf(ScorePdfEntity(id = "pdf", title = "Rollback"))
                    db.libraryDao().insertScorePdfVersion(ScorePdfVersionEntity(scorePdfId = "missing-parent", versionNumber = 1,
                        managedFilePath = staged.file.path, originalUri = staged.uri, fileHash = staged.hash, fileSizeBytes = staged.size, pageCount = staged.pages))
                } }
                fail("Foreign-key failure must abort commit")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertTrue(db.resolutionDao().pdfs().isEmpty()); assertTrue(db.resolutionDao().versions().isEmpty())
            assertEquals(before, files())
        } finally { db.close(); context.contentResolver.delete(uri, null, null); files().filter { it !in before }.forEach { it.delete() } }
    }
    @Test fun cancellationInsideCommitCannotLeaveReferencedFileDeleted() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val uri = fixture(); val before = files()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work = launch(Dispatchers.Default) {
            ManagedPdfImporter(context).publish(uri) { staged ->
                entered.complete(Unit); release.await()
                db.withTransaction {
                    val pdf = ScorePdfEntity(id = "pdf", title = "Atomic commit")
                    db.libraryDao().insertScorePdf(pdf)
                    val version = ScorePdfVersionEntity(scorePdfId = pdf.id, versionNumber = 1, managedFilePath = staged.file.path,
                        originalUri = staged.uri, fileHash = staged.hash, fileSizeBytes = staged.size, pageCount = staged.pages)
                    db.libraryDao().insertScorePdfVersion(version); db.libraryDao().updateScorePdf(pdf.copy(activeVersionId = version.id))
                }
            }
        }
        try {
            withTimeout(15_000) { entered.await() }
            work.cancel(); release.complete(Unit); withTimeout(15_000) { work.join() }
            assertTrue(work.isCancelled)
            val version = db.resolutionDao().versions().single()
            assertEquals(version.id, db.resolutionDao().pdfs().single().activeVersionId)
            assertTrue(File(version.managedFilePath).isFile)
            assertEquals(version.fileSizeBytes, File(version.managedFilePath).length())
        } finally {
            release.complete(Unit); work.cancelAndJoin(); db.close(); context.contentResolver.delete(uri, null, null)
            files().filter { it !in before }.forEach { it.delete() }
        }
    }
}
