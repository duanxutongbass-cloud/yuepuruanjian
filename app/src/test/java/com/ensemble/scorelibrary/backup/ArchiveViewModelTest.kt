package com.ensemble.scorelibrary.backup

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val saved = SavedStateHandle()
    private var barrier: CompletableDeferred<Unit>? = null
    private var fail = false
    private var exports = 0
    private var restores = 0
    private val store = object : AssetArchiveStore {
        override suspend fun export(member: String, kind: ArchiveKind, destination: Uri) { exports++; if (fail) error("Storage failure") }
        override suspend fun preview(source: Uri): ArchivePreview {
            withContext(NonCancellable) { barrier?.await() }
            return ArchivePreview(ArchiveKind.PERSONAL, "m", 0, 0, 1, "hash")
        }
        override suspend fun restore(member: String?, source: Uri, expectedHash: String) { restores++; assertEquals("hash", expectedHash) }
    }
    private lateinit var model: ArchiveViewModel
    @Before fun setup() { Dispatchers.setMain(dispatcher); model = ArchiveViewModel(store, saved); viewModels.put("archive", model); model.bind("m") }
    @After fun cleanup() { viewModels.clear(); Dispatchers.resetMain() }
    @Test fun pickerCancelAndExportFailureClearPendingAndNeverReportSuccess() = runTest(dispatcher) {
        assertTrue(model.request(ArchiveAction.EXPORT_PERSONAL)); model.picked(null)
        assertFalse(model.state.value.pending); assertEquals(0, exports)
        fail = true; model.request(ArchiveAction.EXPORT_PERSONAL); model.picked(Uri.parse("content://archive")); advanceUntilIdle()
        assertFalse(model.state.value.busy); assertFalse(model.state.value.pending)
        assertEquals("备份文件无效或操作未完成，请重新检查；原有数据未覆盖", model.state.value.message)
    }
    @Test fun previewNeedsExplicitConfirmationAndKeepsExactHash() = runTest(dispatcher) {
        model.request(ArchiveAction.RESTORE_PERSONAL); model.picked(Uri.parse("content://archive")); advanceUntilIdle()
        assertEquals(0, restores); assertNotNull(model.state.value.preview)
        model.restore(); advanceUntilIdle()
        assertEquals(1, restores); assertEquals("恢复完成", model.state.value.message); assertFalse(model.state.value.pending)
    }
    @Test fun cancelledLatePreviewCannotReappearAfterMemberSwitchAndReturn() = runTest(dispatcher) {
        barrier = CompletableDeferred()
        model.request(ArchiveAction.RESTORE_PERSONAL); model.picked(Uri.parse("content://archive")); runCurrent()
        model.bind("other"); model.bind("m"); barrier!!.complete(Unit); advanceUntilIdle()
        assertNull(model.state.value.preview); assertFalse(model.state.value.busy); assertFalse(model.state.value.pending)
        assertNull(model.state.value.message)
    }
    @Test fun pickerRequestRestoresAcrossInstanceButSwitchingOwnerRejectsResult() = runTest(dispatcher) {
        model.request(ArchiveAction.EXPORT_PERSONAL)
        val recreated = ArchiveViewModel(store, saved); viewModels.put("recreated", recreated)
        assertTrue(recreated.state.value.pending)
        recreated.bind("other"); recreated.picked(Uri.parse("content://stale")); advanceUntilIdle()
        assertEquals(0, exports); assertFalse(recreated.state.value.pending)
    }
    @Test fun processLossAfterPreviewDoesNotLeavePickerPermanentlyPending() = runTest(dispatcher) {
        model.request(ArchiveAction.RESTORE_PERSONAL); model.picked(Uri.parse("content://archive")); advanceUntilIdle()
        assertNotNull(model.state.value.preview)
        val restarted = ArchiveViewModel(store, saved); viewModels.put("restarted", restarted)
        assertFalse(restarted.state.value.pending); assertNull(restarted.state.value.preview)
        assertTrue(restarted.request(ArchiveAction.RESTORE_PERSONAL))
    }
}
