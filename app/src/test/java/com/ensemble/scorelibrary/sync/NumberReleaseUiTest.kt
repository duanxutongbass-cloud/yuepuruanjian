package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberReleaseUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val inventory=NumberReleaseInventory("owner",UUID.randomUUID().toString(),UUID.randomUUID().toString(),7,
        listOf(ReleasableNumber("001",3),ReleasableNumber("005",2)))
    @Test fun onlySelectedRetiredNumbersArePreviewedAndConsentRequired() {
        var model by mutableStateOf(EnsembleState(key="owner",revision=7,numberInventory=TeamNumberInventory(7,setOf(1,3,5),0),numberRelease=inventory))
        var submitted:Set<String>?=null
        ui.setContent{MaterialTheme{NumberInventoryDialog(model,{},
            {wanted->model=model.copy(numberReleasePreview=NumberReleasePreview("owner",inventory.storage,inventory.account,7,wanted,3,"a".repeat(64)))},
            {p->submitted=p.sequences;model=model.copy(numberReleasePreview=null,numberRelease=inventory.copy(revision=8,eligible=listOf(ReleasableNumber("005",2))),
                numberInventory=TeamNumberInventory(8,setOf(3,5),0),changes=1)},
            {model=model.copy(numberReleasePreview=null)})}}
        ui.onNodeWithTag("number-release-preview").assertIsNotEnabled()
        ui.onNodeWithTag("number-release-select-003").assertDoesNotExist()
        ui.onNodeWithTag("number-release-select-001").performClick()
        ui.onNodeWithTag("number-release-preview").performClick()
        ui.onNodeWithTag("number-release-confirm").assertIsNotEnabled()
        ui.onNodeWithTag("number-release-consent").performClick()
        ui.onNodeWithTag("number-release-confirm").performClick()
        assertEquals(setOf("001"),submitted)
        ui.onNodeWithTag("number-next-free").assertTextContains("001",substring=true)
        ui.onNodeWithTag("number-release-select-005").assertExists()
        ui.onNodeWithTag("number-release-select-001").assertDoesNotExist()
        ui.onNodeWithTag("number-release-preview").assertIsNotEnabled()
        ui.runOnUiThread {
            val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView);val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap));val path=java.io.File("build/reports/screenshots/0128-number-release.png");path.parentFile!!.mkdirs()
            path.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    @Test fun accountChangeClearsSelectionAndBusyConfirmationCannotSubmit() {
        var model by mutableStateOf(EnsembleState(key="owner",revision=7,numberInventory=TeamNumberInventory(7,setOf(1,3,5),0),numberRelease=inventory))
        var submitted=false
        ui.setContent{MaterialTheme{NumberInventoryDialog(model,{}, {},{submitted=true},{})}}
        ui.onNodeWithTag("number-release-select-all").performClick()
        ui.onNodeWithTag("number-release-preview").assertIsEnabled()
        ui.runOnIdle{model=model.copy(key="another-owner",numberRelease=inventory.copy(key="another-owner"))}
        ui.onNodeWithTag("number-release-preview").assertIsNotEnabled()
        ui.runOnIdle{model=model.copy(busy=true,numberReleasePreview=NumberReleasePreview("another-owner",inventory.storage,inventory.account,7,setOf("001"),3,"a".repeat(64)))}
        ui.onNodeWithTag("number-release-consent").assertIsNotEnabled()
        ui.onNodeWithTag("number-release-confirm").assertIsNotEnabled()
        assertFalse(submitted)
    }
}
