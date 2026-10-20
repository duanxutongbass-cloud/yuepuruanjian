package com.ensemble.scorelibrary.sync

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import com.ensemble.scorelibrary.pdf.NativePdfEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Must run on Android: desktop Robolectric cannot execute the platform PDF decoder. */
@RunWith(AndroidJUnit4::class)
class TeamPdfDeviceTest {
    @Test fun privatePdfUsesRealDecoder() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.noBackupFilesDir,"team-libraries/device-test-${UUID.randomUUID()}").apply{check(mkdirs())}
        val file=File(root,"score.pdf")
        try {
            val document=PdfDocument()
            try {
                val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                document.finishPage(page)
                file.outputStream().use{document.writeTo(it)}
            } finally { document.close() }
            val verifier=AndroidTeamPdfVerifier()
            verifier.verify(file,1)
            assertTrue(runCatching{verifier.verify(file,2)}.exceptionOrNull() is TeamSyncFailure)
            val segment=ResolvedScoreSegment("b",null,"pdf","v",file.path,TeamLibrarySync.hash(file),1,1,CacheStatus.CACHED)
            assertEquals(1,NativePdfEngine(context).inspect(listOf(segment)).size)
            file.writeText("not a PDF")
            assertTrue(runCatching{verifier.verify(file,1)}.exceptionOrNull() is TeamSyncFailure)
        } finally {
            check(root.canonicalFile.parentFile==File(context.noBackupFilesDir,"team-libraries").canonicalFile)
            root.deleteRecursively()
        }
    }
}
