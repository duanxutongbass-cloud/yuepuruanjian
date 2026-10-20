package com.ensemble.scorelibrary.backup

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderBackupUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore()
    @After fun cleanup() { models.clear() }
    @Test fun actionsExplainContentsAndBlockOverlappingPickerRequests() {
        var state by mutableStateOf(FolderBackupState("m"))
        var exports=0;var restores=0;var cancels=0
        ui.setContent { MaterialTheme { Surface { FolderBackupActions(state,{exports++},{restores++},{cancels++}) } } }
        ui.onNodeWithText("PDF、批注和账号密码不在这个备份中",substring=true).assertIsDisplayed()
        ui.onNodeWithTag("folder-backup-export").performClick();ui.onNodeWithTag("folder-backup-restore").performClick()
        assertEquals(1,exports);assertEquals(1,restores)
        ui.runOnIdle { state=state.copy(pending=true) }
        ui.onNodeWithTag("folder-backup-export").assertIsNotEnabled();ui.onNodeWithTag("folder-backup-restore").assertIsNotEnabled()
        ui.onNodeWithTag("folder-backup-cancel").performClick();assertEquals(1,cancels)
    }
    @Test fun conflictPreviewBlocksRestoreAndShowsMissingReferences() {
        val preview=FolderArchivePreview(3,1,1,listOf("本周排练"),2,"hash","local")
        var cancelled=0
        ui.setContent { MaterialTheme { FolderRestoreConfirmation(preview,true,{fail("Conflict must block")},{cancelled++}) } }
        ui.onNodeWithTag("folder-backup-confirm").assertIsNotEnabled()
        ui.onNodeWithText("2 个曲目引用目前缺失或无授权",substring=true).assertIsDisplayed()
        ui.onNodeWithText("存在冲突：本周排练",substring=true).assertIsDisplayed()
        saveScreenshot("folder-backup-preview.png")
        ui.onNodeWithText("取消",useUnmergedTree=true).performClick();assertEquals(1,cancelled)
    }
    @Test fun validPreviewNeedsConfirmClick() {
        var restored=0
        ui.setContent { MaterialTheme { FolderRestoreConfirmation(FolderArchivePreview(2,2,0,emptyList(),0,"hash","local"),true,{restored++},{}) } }
        assertEquals(0,restored);ui.onNodeWithTag("folder-backup-confirm").performClick();assertEquals(1,restored)
    }
    @Test fun confirmedRestoreRefreshesActualFolderBrowser() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("personal-library-folders",0).edit().clear().commit()
        val storage=LibraryFolderStore(context)
        val repository=object:PresentationRepository {
            override fun observe(memberId:String?,request:ScreenRequest)=flowOf<DomainResult<Presentation>>(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,"")))
        }
        val engine=object:PdfEngine {
            override suspend fun inspect(segments:List<ResolvedScoreSegment>):List<PageSize> = error("Unused")
            override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame = error("Unused")
        }
        val folders=LibraryFolderViewModel(context,repository,engine,storage).also { models.put("folders",it) }
        val incoming=LibraryFolder(id="restored",name="恢复的排练",pieces=setOf("p"))
        val gateway=object:FolderBackupStore {
            override suspend fun preview(member:String,source:android.net.Uri)=FolderArchivePreview(1,1,0,emptyList(),0,"hash","local")
            override suspend fun restore(member:String,source:android.net.Uri,preview:FolderArchivePreview) { storage.edit(member,incoming) }
            override suspend fun export(member:String,destination:android.net.Uri) { error("Unused") }
        }
        val backups=FolderBackupViewModel(gateway,SavedStateHandle()).also { models.put("backup",it) }
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalFolderBackups provides backups) {
            FolderBrowser("m",listOf(PieceSummary("p","合成曲目",null)),null,{},folders)
        } } }
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();!folders.state.value.busy && backups.state.value.member=="m" }
        ui.onNodeWithTag("folder-backup-entry").performClick()
        ui.runOnIdle { assertTrue(backups.request("m",FolderBackupAction.RESTORE));backups.picked("m",android.net.Uri.parse("content://synthetic/folders.zip")) }
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();backups.state.value.preview!=null && !backups.state.value.busy }
        assertTrue(storage.read("m").isEmpty())
        ui.onNodeWithTag("folder-backup-confirm").performClick()
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();folders.state.value.folders==listOf(incoming) && !folders.state.value.busy }
        ui.onNodeWithText("关闭",useUnmergedTree=true).performClick()
        ui.onNodeWithTag("folder-restored").assertIsDisplayed()
    }
    private fun saveScreenshot(name:String) {
        ui.waitForIdle();lateinit var bitmap:android.graphics.Bitmap
        ui.runOnUiThread { val view=requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window).decorView
            bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap)) }
        val file=java.io.File("build/reports/screenshots/$name");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
