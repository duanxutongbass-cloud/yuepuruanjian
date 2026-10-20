package com.ensemble.scorelibrary.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.data.LocalAdministration
import com.ensemble.scorelibrary.domain.Dashboard
import com.ensemble.scorelibrary.domain.MemberChoice
import com.ensemble.scorelibrary.domain.Profile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-mdpi")
class PdfImportEntryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
    private val vm = AdministrationViewModel(LocalAdministration(context, db))
    private val viewModels = androidx.lifecycle.ViewModelStore().apply { put("admin", vm) }
    private val dashboard = mutableStateOf(Dashboard(listOf(MemberChoice("a", "Admin")), Profile("a", "Admin", null, true)))
    private var launches = 0
    private var resultUri: Uri? = Uri.parse("content://import-test/Sample.Pdf")
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            val intent = contract.createIntent(context, input)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertEquals(listOf("application/pdf"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
            dispatchResult(requestCode, if (resultUri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                Intent().setData(resultUri))
        }
    }
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { MaterialTheme { AdministrationScreen(dashboard.value, vm) } }
        }
    }
    @After fun close() { viewModels.clear(); db.close() }

    @Test fun emptyFormCanSelectPdfAndAutoFillTitleBeforeChoosingPart() {
        show()
        compose.onNodeWithTag("import-select-pdf").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, launches)
        compose.onNodeWithTag("import-selected-file").performScrollTo().assertTextEquals("已选择：Sample.Pdf")
        compose.onNodeWithText("Sample").assertExists()
        compose.onNodeWithTag("import-confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("import-required-fields").assertTextEquals("请填写声部名称。")
        compose.onNodeWithText("声部名称").performScrollTo().performTextInput("Violin I")
        compose.onNodeWithTag("import-confirm").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("结束页（空为末页）").performScrollTo().performTextInput("invalid")
        compose.onNodeWithTag("import-confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("import-select-pdf").performScrollTo().assertIsEnabled()
    }

    @Test fun reselectionRefreshesSuggestedTitleButPreservesManualTitleAndCancelKeepsFile() {
        show()
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        resultUri = Uri.parse("content://import-test/Second.pdf")
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        compose.onNodeWithText("Second").assertExists()
        compose.onNodeWithText("新曲目名称").performScrollTo().performTextReplacement("Custom title")
        resultUri = Uri.parse("content://import-test/Third.pdf")
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        compose.onNodeWithText("Custom title").assertExists()
        resultUri = null
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        compose.onNodeWithTag("import-selected-file").performScrollTo().assertTextEquals("已选择：Third.pdf")
        compose.onNodeWithText("Custom title").assertExists()
    }

    @Test fun switchingAdministratorDropsSelectedFileAndMemberCannotImport() {
        show()
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        compose.runOnIdle { dashboard.value = Dashboard(listOf(MemberChoice("b", "Other")), Profile("b", "Other", null, true)) }
        compose.onNodeWithTag("import-selected-file").assertDoesNotExist()
        compose.onNodeWithTag("import-confirm").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { dashboard.value = Dashboard(listOf(MemberChoice("m", "Member")), Profile("m", "Member", null, false)) }
        compose.onNodeWithTag("import-select-pdf").assertDoesNotExist()
        compose.onNodeWithTag("import-confirm").assertDoesNotExist()
    }
    @Test fun numberedPdfSuggestsInstrumentCategoryAndNumberWithoutChangingManualFields() {
        resultUri = Uri.parse("content://import-test/0201007天空之城.pdf")
        show()
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        compose.onNodeWithText("天空之城").assertExists()
        compose.onNodeWithText("第二小提琴").assertExists()
        compose.onNodeWithText("久石让").assertExists()
        compose.onNodeWithText("007").assertExists()
        compose.onNodeWithTag("import-confirm").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("声部名称").performScrollTo().performTextReplacement("Manual instrument")
        resultUri = Uri.parse("content://import-test/0402008其他曲目.pdf")
        compose.onNodeWithTag("import-select-pdf").performScrollTo().performClick()
        compose.onNodeWithText("Manual instrument").assertExists()
        compose.onNodeWithText("电影音乐").assertExists()
    }
}
