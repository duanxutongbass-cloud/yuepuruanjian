package com.ensemble.scorelibrary.backup

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.ensemble.scorelibrary.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ArchivePanelTest {
    @get:Rule val compose = createComposeRule()
    private val model = ArchiveViewModel(object : AssetArchiveStore {
        override suspend fun export(member: String, kind: ArchiveKind, destination: Uri) = Unit
        override suspend fun preview(source: Uri) = error("Not opened")
        override suspend fun restore(member: String?, source: Uri, expectedHash: String) = Unit
    }, SavedStateHandle())
    @Test @Config(qualifiers = "w800dp-h1200dp-mdpi") fun ordinaryMemberOnlySeesOwnAnnotationActions() {
        compose.setContent { MaterialTheme { ArchivePanel(Dashboard(listOf(MemberChoice("m", "Member")), Profile("m", "Member", null, false)), model) } }
        compose.onNodeWithTag("archive-export-library").assertDoesNotExist()
        compose.onNodeWithTag("archive-export-personal").assertIsDisplayed()
        compose.onNodeWithTag("archive-restore-personal").assertIsDisplayed()
        compose.onNodeWithTag("archive-restore-library").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w1280dp-h1800dp-mdpi") fun administratorExportRequiresPrivacyConfirmation() {
        compose.setContent { MaterialTheme { ArchivePanel(Dashboard(listOf(MemberChoice("a", "Admin")), Profile("a", "Admin", null, true)), model) } }
        compose.onNodeWithTag("archive-export-library").performClick()
        compose.onNodeWithText("包含所有原始 PDF、历史版本和共享批注，不含个人批注。备份未加密，请妥善保管。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("archive-export-library").assertIsEnabled()
    }
    @Test fun freshInstallationExposesMotherRecoveryWithoutCreatingAdministrator() {
        compose.setContent { MaterialTheme { ArchivePanel(Dashboard(emptyList()), model) } }
        compose.onNodeWithTag("archive-restore-library").assertIsDisplayed()
        compose.onNodeWithTag("archive-export-personal").assertDoesNotExist()
    }
}
