package com.ensemble.scorelibrary.ui

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.data.*
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class ManagementViewModelTest {
    private lateinit var db: ScoreLibraryDatabase
    private lateinit var service: LibraryManagement
    private lateinit var model: ManagementViewModel
    private val store = ViewModelStore()
    private val main = newSingleThreadContext("ManagementTestMain")
    private val inspected = CompletableDeferred<Unit>()
    private val barrier = CompletableDeferred<Unit>()
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(main)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val dao = db.libraryDao()
        dao.insertMember(MemberEntity(id = "admin", displayName = "Admin", role = MemberRole.ADMIN))
        dao.insertMember(MemberEntity(id = "member", displayName = "Member"))
        dao.insertPiece(PieceEntity(id = "piece", title = "Original"))
        dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "PDF"))
        for (n in 1..2) dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v$n", scorePdfId = "pdf",
            versionNumber = n, managedFilePath = "fixture", originalUri = "content://fixture", fileHash = "hash", fileSizeBytes = 1, pageCount = 1))
        dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "PDF", activeVersionId = "v1"))
        service = LibraryManagement(context, db, object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> {
                inspected.complete(Unit); barrier.await(); return listOf(PageSize(600, 900))
            }
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame = error("Not used")
        })
        model = ManagementViewModel(service); store.put("management", model)
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain(); main.close() }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(20) }
    @Test fun switchingMemberImmediatelyClearsCatalogAndRejectsCapturedAdminAction() = runBlocking {
        withContext(main) { model.bind("admin") }; await { model.state.value.catalog != null }
        val token = model.state.value.token
        withContext(main) {
            model.bind("member")
            assertNull(model.state.value.catalog); assertEquals("", model.state.value.token)
            model.edit("admin", token, ManagementCommand.Piece(PieceEntity(id = "piece", title = "Leaked")))
        }
        await { model.state.value.message != null }
        assertNull(model.state.value.catalog)
        assertEquals("Original", db.resolutionDao().pieces().single().title)
    }
    @Test fun staleEditorTokenCannotOverwriteNewRoomData() = runBlocking {
        withContext(main) { model.bind("admin") }; await { model.state.value.catalog != null }
        val token = model.state.value.token
        service.edit("admin", ManagementCommand.Piece(PieceEntity(id = "piece", title = "Fresh")))
        await { model.state.value.catalog?.data?.pieces?.single()?.title == "Fresh" }
        withContext(main) { model.edit("admin", token, ManagementCommand.Piece(PieceEntity(id = "piece", title = "Stale"))) }
        assertEquals("Fresh", db.resolutionDao().pieces().single().title)
        assertTrue(model.state.value.message!!.contains("数据已更新")); assertFalse(model.state.value.busy)
    }
    @Test fun actorChangeCancelsPendingInspectionWithoutSwitchingVersionOrPublishingOldResult() = runBlocking {
        withContext(main) { model.bind("admin") }; await { model.state.value.catalog != null }
        withContext(main) { model.activate("admin", model.state.value.token, "pdf", "v2") }
        withTimeout(10_000) { inspected.await() }
        withContext(main) { model.bind(null) }; barrier.complete(Unit)
        await { !model.state.value.busy }
        assertEquals("v1", db.resolutionDao().pdfs().single().activeVersionId)
        assertNull(model.state.value.catalog); assertNull(model.state.value.message); assertEquals(0L, model.state.value.revision)
    }
    @Test fun switchingActorBeforeQueuedOperationStartsCannotLeaveBusyStuck() = runBlocking {
        withContext(main) { model.bind("admin") }; await { model.state.value.catalog != null }
        withContext(main) {
            model.activate("admin", model.state.value.token, "pdf", "v2")
            model.bind(null)
        }
        await { !model.state.value.busy }
        assertEquals("v1", db.resolutionDao().pdfs().single().activeVersionId)
        assertNull(model.state.value.message)
    }
}
