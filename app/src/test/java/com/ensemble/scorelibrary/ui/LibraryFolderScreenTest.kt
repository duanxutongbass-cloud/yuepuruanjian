package com.ensemble.scorelibrary.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
class LibraryFolderScreenTest {
    @get:Rule val compose=createComposeRule()
    private val store=ViewModelStore()
    @After fun close() { store.clear() }
    @Test fun createsFolderWithChosenScoresAndFiltersWithoutChangingSourceCatalog() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("personal-library-folders",0).edit().clear().commit()
        val repository=object : PresentationRepository {
            override fun observe(memberId:String?,request:ScreenRequest)=flowOf<DomainResult<Presentation>>(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"")))
        }
        val engine=object : PdfEngine {
            override suspend fun inspect(segments:List<ResolvedScoreSegment>):List<PageSize> = error("Not used")
            override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame = error("Not used")
        }
        val model=LibraryFolderViewModel(context,repository,engine);store.put("folders",model)
        val pieces=listOf(PieceSummary("p","Sky",null,"电影","007",listOf("小提琴")),PieceSummary("q","Sea",null))
        compose.setContent { CompositionLocalProvider(LocalFolders provides model) { MaterialTheme { PieceWorkspace(pieces,"m",onPiece={}) } } }
        compose.waitUntil(5000) { model.state.value.member=="m" && !model.state.value.busy }
        compose.onNodeWithTag("library-folders").performClick()
        compose.onNodeWithText("新建文件夹").performClick()
        compose.onNodeWithTag("folder-name").performTextInput("排练")
        compose.onNodeWithTag("folder-piece-p").performClick()
        compose.onNodeWithText("保存").performClick()
        try { compose.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            model.state.value.folders.size==1 && !model.state.value.busy
        } }
        catch(e: ComposeTimeoutException) { throw AssertionError("Folders: ${model.state.value}\n${compose.onRoot().printToString()}",e) }
        val folder=model.state.value.folders.single()
        assertEquals(setOf("p"),folder.pieces)
        compose.onNodeWithTag("folder-${folder.id}").performClick()
        compose.onNodeWithTag("piece-p").assertExists();compose.onNodeWithTag("piece-q").assertDoesNotExist()
        compose.onNodeWithTag("library-folders").performClick()
        compose.onNodeWithText("全部曲谱").performClick()
        compose.onNodeWithTag("piece-q").assertExists();assertEquals(2,pieces.size)
    }
}
