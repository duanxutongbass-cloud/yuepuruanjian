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
class FolderBackupViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=ViewModelStore()
    private val saved=SavedStateHandle()
    private var previews=0;private var restores=0;private var exports=0
    private var barrier:CompletableDeferred<Unit>?=null
    private var failure=false
    private var result=FolderArchivePreview(2,1,1,emptyList(),1,"source-hash","local-hash")
    private val store=object:FolderBackupStore {
        override suspend fun preview(member:String,source:Uri):FolderArchivePreview {
            previews++;withContext(NonCancellable) { barrier?.await() };return result
        }
        override suspend fun export(member:String,destination:Uri) { exports++;if(failure)error("Storage failure") }
        override suspend fun restore(member:String,source:Uri,preview:FolderArchivePreview) {
            assertEquals(result,preview);restores++;if(failure)error("本机文件夹已变化，请重新预览")
        }
    }
    private lateinit var model:FolderBackupViewModel
    private val uri=Uri.parse("content://synthetic-backup/folders.zip")
    @Before fun setup() { Dispatchers.setMain(dispatcher);model=FolderBackupViewModel(store,saved);models.put("initial",model);model.bind("m") }
    @After fun cleanup() { models.clear();Dispatchers.resetMain() }
    @Test fun restoreRequiresExplicitConfirmationAndForwardsBothFingerprints()=runTest(dispatcher) {
        assertTrue(model.request("m",FolderBackupAction.RESTORE));model.picked("m",uri);advanceUntilIdle()
        assertEquals(1,previews);assertEquals(0,restores);assertEquals(result,model.state.value.preview)
        model.restore("m");advanceUntilIdle();assertEquals(1,restores)
        assertEquals("文件夹恢复完成；原乐谱与批注保留",model.state.value.message)
        assertFalse(model.state.value.pending);assertFalse(model.state.value.busy)
    }
    @Test fun pickerCancelAndFailedExportNeverShowSuccess()=runTest(dispatcher) {
        model.request("m",FolderBackupAction.EXPORT);model.picked("m",null);advanceUntilIdle()
        assertEquals(0,exports);assertFalse(model.state.value.pending)
        failure=true;model.request("m",FolderBackupAction.EXPORT);model.picked("m",uri);advanceUntilIdle()
        assertEquals(1,exports);assertFalse(model.state.value.message!!.contains("已导出"));assertFalse(model.state.value.busy)
        failure=false;model.request("m",FolderBackupAction.EXPORT);model.picked("m",uri);advanceUntilIdle()
        assertEquals("文件夹备份已导出并回读校验",model.state.value.message)
    }
    @Test fun staleOwnerPickerCannotConsumeNewOwnersRequest()=runTest(dispatcher) {
        model.request("m",FolderBackupAction.EXPORT);model.bind("other")
        assertTrue(model.request("other",FolderBackupAction.EXPORT))
        model.picked("m",uri);advanceUntilIdle();assertEquals(0,exports);assertTrue(model.state.value.pending)
        model.picked("other",uri);advanceUntilIdle();assertEquals(1,exports)
    }
    @Test fun lateCancelledPreviewCannotResurrectAfterSwitchAndReturn()=runTest(dispatcher) {
        barrier=CompletableDeferred();model.request("m",FolderBackupAction.RESTORE);model.picked("m",uri);runCurrent()
        model.bind("other");model.bind("m");barrier!!.complete(Unit);advanceUntilIdle()
        assertNull(model.state.value.preview);assertNull(model.state.value.message);assertEquals(0,restores)
    }
    @Test fun processLossKeepsUndeliveredPickerButDropsInterruptedPreview()=runTest(dispatcher) {
        model.request("m",FolderBackupAction.RESTORE)
        val restarted=FolderBackupViewModel(store,saved);models.put("restart",restarted)
        assertTrue(restarted.state.value.pending);restarted.picked("m",uri);advanceUntilIdle()
        assertNotNull(restarted.state.value.preview)
        val interrupted=FolderBackupViewModel(store,saved);models.put("interrupted",interrupted)
        assertNull(interrupted.state.value.preview);assertFalse(interrupted.state.value.pending)
        assertTrue(interrupted.state.value.message!!.contains("重新选择"));assertTrue(interrupted.request("m",FolderBackupAction.EXPORT))
    }
    @Test fun conflictsAndLocalChangesNeverReachSuccessfulRestoreState()=runTest(dispatcher) {
        result=result.copy(conflicts=listOf("排练"))
        model.request("m",FolderBackupAction.RESTORE);model.picked("m",uri);advanceUntilIdle()
        model.restore("m");advanceUntilIdle();assertEquals(0,restores)
        model.dismiss("m");result=result.copy(conflicts=emptyList());failure=true
        model.request("m",FolderBackupAction.RESTORE);model.picked("m",uri);advanceUntilIdle();model.restore("m");advanceUntilIdle()
        assertEquals("本机文件夹已变化，请重新预览",model.state.value.message);assertNull(model.state.value.preview)
    }
    @Test fun cancelledLatePreviewKeepsRequestReusable()=runTest(dispatcher) {
        barrier=CompletableDeferred();model.request("m",FolderBackupAction.RESTORE);model.picked("m",uri);runCurrent()
        model.cancel("m");barrier!!.complete(Unit);advanceUntilIdle()
        assertNull(model.state.value.preview);assertFalse(model.state.value.busy)
        assertTrue(model.request("m",FolderBackupAction.EXPORT))
    }
}
