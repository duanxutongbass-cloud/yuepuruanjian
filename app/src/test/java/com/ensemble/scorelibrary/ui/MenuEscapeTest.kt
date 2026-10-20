package com.ensemble.scorelibrary.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.annotation.InkInputModePicker
import com.ensemble.scorelibrary.annotation.InkInputMode
import com.ensemble.scorelibrary.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w360dp-h640dp-mdpi")
class MenuEscapeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun emptyChoiceMenuCanCloseWithoutMakingASelectionAndReopen() {
        var changes=0
        compose.setContent { MaterialTheme { AdminChoice("分类",null,emptyList(),tag="escape") {changes++} } }
        repeat(2) {
            compose.onNodeWithTag("choice-escape").performClick()
            compose.onNodeWithText("关闭菜单").assertIsDisplayed().performClick()
            compose.onNodeWithText("关闭菜单").assertDoesNotExist()
        }
        assertEquals(0,changes)
    }
    @Test fun memberMenuCanCloseWithoutChangingMember() {
        var changes=0
        compose.setContent { MaterialTheme { MemberPicker(Dashboard(listOf(MemberChoice("m","成员")),null)) {changes++} } }
        compose.onNodeWithText("选择成员").performClick()
        compose.onNodeWithText("关闭菜单").performClick()
        assertEquals(0,changes)
        compose.onNodeWithText("选择成员").performClick()
        compose.onNodeWithText("成员").performClick()
        assertEquals(1,changes)
    }
    @Test fun inputModeMenuCanCloseWithoutChangingWritingMode() {
        var changes=0
        compose.setContent { MaterialTheme { InkInputModePicker(InkInputMode.entries.first(),true) {changes++} } }
        compose.onNodeWithTag("ink-input-mode").performClick()
        compose.onNodeWithText("关闭菜单").assertIsDisplayed().performClick()
        assertEquals(0,changes)
        compose.onNodeWithText("关闭菜单").assertDoesNotExist()
    }
}
