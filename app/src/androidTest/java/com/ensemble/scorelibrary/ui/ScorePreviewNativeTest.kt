package com.ensemble.scorelibrary.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.data.RoomPresentationRepository
import com.ensemble.scorelibrary.pdf.NativePdfEngine
import com.ensemble.scorelibrary.pdf.PdfFixture
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real Room -> Domain -> native PDF preview boundary without an Activity/IME. */
@RunWith(AndroidJUnit4::class)
class ScorePreviewNativeTest {
    @Test fun realAuthorizedPageMissingFileRetryAndRevocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(context.filesDir, "preview-synthetic.pdf")
        val hash = PdfFixture.write(file)
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val store = ViewModelStore()
        val permission = MemberPartPermissionEntity(memberId = "m", partDefinitionId = "d")
        try {
            val dao = db.libraryDao()
            dao.insertPiece(PieceEntity(id = "p", title = "Synthetic preview"))
            dao.insertPartDefinition(PartDefinitionEntity(id = "d", name = "Synthetic part I"))
            dao.insertPiecePart(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
            dao.insertMember(MemberEntity(id = "m", displayName = "Synthetic member", defaultPartDefinitionId = "d"))
            dao.insertPermission(permission)
            val pdf = ScorePdfEntity(id = "pdf", title = "Synthetic")
            dao.insertScorePdf(pdf)
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
                managedFilePath = file.path, originalUri = "content://synthetic", fileHash = hash,
                fileSizeBytes = file.length(), pageCount = 4))
            dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = "part", scorePdfId = "pdf", startPage = 2, endPage = 3))
            dao.updateScorePdf(pdf.copy(activeVersionId = "v"))
            lateinit var model: ScorePreviewViewModel
            instrumentation.runOnMainSync {
                model = ScorePreviewViewModel(RoomPresentationRepository(db), NativePdfEngine(context))
                store.put("preview", model)
                model.bind("m", "p", 800, 1000)
            }
            suspend fun settled() = withTimeout(90_000) {
                model.state.filter { it is ScorePreviewState.Ready || it is ScorePreviewState.Failure }.first()
            }
            val state = settled()
            assertTrue("Real native preview must render: $state", state is ScorePreviewState.Ready)
            state as ScorePreviewState.Ready
            assertEquals("part", state.score.part.id)
            assertEquals(2, state.score.segments.first().startPage)
            var dark = 0
            for (y in 0 until state.frame.bitmap.height step 4) for (x in 0 until state.frame.bitmap.width step 4) {
                if (android.graphics.Color.red(state.frame.bitmap.getPixel(x, y)) < 100) dark++
            }
            assertTrue("Must contain actual synthetic PDF marks", dark > 50)
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/authorized-preview-native.png")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { state.frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            assertTrue(file.delete())
            instrumentation.runOnMainSync { model.retry() }
            assertEquals("本地曲谱文件缺失", (settled() as ScorePreviewState.Failure).message)
            PdfFixture.write(file)
            instrumentation.runOnMainSync { model.retry() }
            assertTrue(settled() is ScorePreviewState.Ready)
            dao.updatePermission(permission.copy(isArchived = true))
            withTimeout(90_000) { model.state.filter { it is ScorePreviewState.Failure }.first() }
            instrumentation.runOnMainSync { model.clear() }
            assertEquals(ScorePreviewState.Empty, model.state.value)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            db.close(); file.delete()
        }
    }
}
