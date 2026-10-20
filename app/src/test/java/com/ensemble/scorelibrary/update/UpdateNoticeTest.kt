package com.ensemble.scorelibrary.update

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateNoticeTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun newVersionIsVisibleAndOpensUpdateDetails() {
        var requested = false
        ui.setContent { MaterialTheme { UpdateNotice(AppUpdateState(release = AppRelease(16, "0.10.4", 20000000, "a".repeat(64), "批量上传与节目单整曲选择")), { requested = true }, {}) } }
        ui.onNodeWithTag("app-update-banner").assertExists()
        ui.onNodeWithText("查看并更新").performClick()
        ui.onNodeWithText("批量上传与节目单整曲选择").assertExists()
        ui.onNodeWithText("下载更新").performClick(); assertTrue(requested)
    }
    @Test fun temporaryHistoryWorksOffline() {
        ui.setContent { MaterialTheme { TemporaryReleaseNotesButton() } }
        ui.onNodeWithTag("release-notes-button").performClick()
        ui.onNodeWithText("版本更新内容（测试期）").assertExists()
        ui.onNodeWithText("关闭").performClick()
    }
}
