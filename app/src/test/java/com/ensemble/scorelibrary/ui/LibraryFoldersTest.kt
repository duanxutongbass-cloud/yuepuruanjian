package com.ensemble.scorelibrary.ui

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryFoldersTest {
    private val dispatcher=StandardTestDispatcher()
    private val store=ViewModelStore()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val repository=object : PresentationRepository {
        override fun observe(memberId: String?,request: ScreenRequest)=flowOf<DomainResult<Presentation>>(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"")))
    }
    private val engine=object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> = error("Not used")
        override suspend fun render(page: PdfPageRef,width: Int,height: Int,mode: FitMode,scroll: Float): PdfFrame = error("Not used")
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher);context.getSharedPreferences("personal-library-folders",0).edit().clear().commit() }
    @After fun close() { store.clear();Dispatchers.resetMain() }
    private fun model()=LibraryFolderViewModel(context,repository,engine).also { store.put("folders",it) }
    @Test fun folderCodecRetainsMultipleMembershipsAndEitherCoverChoice() {
        val folders=listOf(LibraryFolder(id="one",name="电影",pieces=setOf("p","q"),coverPiece="p"),
            LibraryFolder(id="two",name="排练",pieces=setOf("p"),image="base64"))
        assertEquals(folders,FolderCodec.decode(FolderCodec.encode(folders)))
    }
    @Test fun remoteChangesRefreshAndRejectStaleEditor()=runTest(dispatcher) {
        val storage=LibraryFolderStore(context)
        val model=LibraryFolderViewModel(context,repository,engine,storage).also { store.put("watched",it) }
        model.bind("m");model.state.first { !it.busy }
        val original=LibraryFolder(id="f",name="原文件夹")
        storage.edit("m",original);model.state.first { it.folders==listOf(original) && !it.busy }
        val remote=original.copy(name="另一台设备的新内容")
        assertTrue(storage.replace("m",com.ensemble.scorelibrary.sync.FolderPayload.encode(listOf(remote)),storage.fingerprint("m")) { it() })
        model.state.first { it.folders==listOf(remote) && !it.busy }
        model.save("m",original.copy(name="旧编辑器保存"),baseline=FolderEditBaseline(original.id,original))
        model.state.first { !it.busy && it.error!=null }
        assertEquals(listOf(remote),storage.read("m"));assertEquals(listOf(remote),model.state.value.folders)
    }
    @Test fun personalFoldersPersistAcrossReopenAndCannotBeEditedThroughAnotherIdentity() = runTest(dispatcher) {
        val model=model();model.bind("m");model.state.first { !it.busy }
        val folder=LibraryFolder(id="f",name="我的排练",pieces=setOf("p"))
        model.save("m",folder);model.state.first { !it.busy };assertEquals(listOf(folder),model.state.value.folders)
        model.bind("other");model.state.first { !it.busy };assertTrue(model.state.value.folders.isEmpty())
        model.save("m",folder.copy(name="wrong"));advanceUntilIdle();assertTrue(model.state.value.folders.isEmpty())
        val reopened=model();reopened.bind("m");reopened.state.first { !it.busy };assertEquals(listOf(folder),reopened.state.value.folders)
        reopened.save("m",null,"f");reopened.state.first { !it.busy };assertTrue(reopened.state.value.folders.isEmpty())
    }
}
