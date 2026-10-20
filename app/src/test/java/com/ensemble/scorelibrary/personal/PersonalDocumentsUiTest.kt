package com.ensemble.scorelibrary.personal

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.account.TEAM_SERVICE_URL
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers="w800dp-h1200dp-mdpi")
class PersonalDocumentsUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val owner=PersonalOwner(TEAM_SERVICE_URL,UUID.randomUUID().toString())
    private val doc=PersonalDocument(UUID.randomUUID().toString(),"01-01-9223372036854775807-本人文件","a".repeat(64),65536,2,"content://personal-ui/source.pdf")
    private var state by mutableStateOf(PersonalFilesState(owner,PersonalSnapshot(owner,1,listOf(doc))))
    private var confirmed:Boolean?=null
    private var imports=0
    private var refreshes=0
    private fun show() {
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            PersonalDocumentsContent(state,onImport={imports++},refresh={refreshes++},
                trash={id,removed->state=state.copy(snapshot=PersonalSnapshot(owner,state.snapshot!!.revision+1,
                    state.snapshot!!.documents.map { if(it.id==id)it.copy(state=if(removed)"trash" else "active") else it }))},
                preview={},open={},cancel={state=state.copy(plan=null)},permanent={confirmed=it})
        } } }
    }
    @Test fun batchPurgePreservesRecycleSearchAndAllowsNextSelection() {
        val first=doc.copy(state="trash",title="待删一")
        val second=first.copy(id=UUID.randomUUID().toString(),title="待删二")
        val keep=first.copy(id=UUID.randomUUID().toString(),title="保留")
        state=state.copy(snapshot=PersonalSnapshot(owner,3,listOf(first,second,keep)))
        var selected=emptySet<String>()
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            PersonalDocumentsContent(state,onImport={},refresh={},trash={_,_->},preview={},open={},cancel={},
                previewSelected={selected=it;state=state.copy(plan=PersonalDeletePlan(owner.key,3,state.snapshot!!.documents.filter { it.id in selected }))},
                permanent={confirmed=it;state=state.copy(plan=null,snapshot=PersonalSnapshot(owner,4,listOf(keep)))})
        } } }
        compose.onNodeWithTag("personal-recycle").performClick()
        compose.onNodeWithTag("personal-search").performTextInput("待删")
        compose.onNodeWithTag("personal-select-visible").performClick()
        compose.runOnUiThread { com.ensemble.scorelibrary.ui.saveUiProof(compose.activity,"0126-personal-batch.png") }
        compose.onNodeWithTag("personal-permanent-selected").performClick()
        assertEquals(setOf(first.id,second.id),selected)
        compose.onNodeWithTag("personal-permanent-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("personal-permanent-check").performScrollTo().performClick()
        compose.onNodeWithTag("personal-permanent-confirm").performClick()
        assertEquals(true,confirmed)
        compose.onNodeWithTag("personal-search").assertTextContains("待删")
        compose.onNodeWithTag("personal-permanent-selected").assertIsNotEnabled()
        compose.onNodeWithTag("personal-search").performTextClearance()
        compose.onNodeWithTag("personal-select-${keep.id}").assertExists().performClick()
        compose.onNodeWithTag("personal-permanent-selected").assertIsEnabled()
    }
    @Test fun ownFileDeleteAndRestoreAreAvailableWithoutAdministratorFlag() {
        show();compose.onNodeWithTag("personal-import").assertIsEnabled().performClick();assertEquals(1,imports)
        compose.onNodeWithText(doc.title).assertExists()
        compose.onNodeWithTag("personal-trash-${doc.id}").performClick();compose.onNodeWithTag("personal-trash-confirm").performClick()
        compose.onNodeWithText(doc.title).assertDoesNotExist()
        compose.onNodeWithTag("personal-recycle").performClick();compose.onNodeWithText(doc.title).assertExists()
        compose.onNodeWithTag("personal-trash-${doc.id}").performClick();compose.onNodeWithTag("personal-trash-confirm").performClick()
        compose.onNodeWithTag("personal-recycle").performClick();compose.onNodeWithText(doc.title).assertExists()
    }
    @Test fun permanentDeletionRequiresImpactPreviewAndExplicitCheckbox() {
        val trash=doc.copy(state="trash");state=state.copy(snapshot=PersonalSnapshot(owner,2,listOf(trash)),plan=PersonalDeletePlan(owner.key,2,listOf(trash)))
        show();compose.onNodeWithTag("personal-permanent-confirm").assertIsNotEnabled();assertNull(confirmed)
        compose.onNodeWithText("彻底删除本人个人文件").assertExists()
        compose.onNodeWithTag("personal-permanent-check").performClick()
        compose.onNodeWithTag("personal-permanent-confirm").assertIsEnabled().performClick();assertEquals(true,confirmed)
        compose.waitForIdle()
        lateinit var bitmap:Bitmap
        compose.runOnUiThread {
            val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        }
        val colors=mutableSetOf<Int>();for(y in 0 until bitmap.height step 6)for(x in 0 until bitmap.width step 6)colors+=bitmap.getPixel(x,y)
        assertTrue(colors.size>3)
        System.getenv("PERSONAL_UI_PROOF_DIR")?.let { directory ->
            File(directory).mkdirs();File(directory,"personal-permanent-confirmation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun accountSwitchRemovesStaleDeleteConfirmationAndPreviousTitles() {
        show();compose.onNodeWithTag("personal-trash-${doc.id}").performClick()
        compose.runOnUiThread { val other=PersonalOwner(TEAM_SERVICE_URL,UUID.randomUUID().toString());state=PersonalFilesState(other,PersonalSnapshot(other,0,emptyList())) }
        compose.onNodeWithTag("personal-trash-confirm").assertDoesNotExist();compose.onNodeWithText(doc.title).assertDoesNotExist()
    }
    @Test fun interruptedPermanentEraseCannotBeRestoredOrReconfirmedButCanRetry() {
        state=state.copy(snapshot=PersonalSnapshot(owner,3,listOf(doc.copy(state="retiring")),1,"清理未完成，请重试"))
        show();compose.onNodeWithTag("personal-recycle").performClick()
        compose.onNodeWithTag("personal-trash-${doc.id}").assertIsNotEnabled()
        compose.onNodeWithTag("personal-permanent-${doc.id}").assertIsNotEnabled()
        compose.onNodeWithTag("personal-refresh").assertIsEnabled().performClick();assertEquals(1,refreshes)
    }
}
