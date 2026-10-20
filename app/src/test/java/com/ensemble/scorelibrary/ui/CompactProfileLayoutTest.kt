package com.ensemble.scorelibrary.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactProfileLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entries = listOf("account" to "账号与乐团", "management" to "管理中心", "part-grants" to "成员与声部",
        "archive" to "备份与恢复", "pedal" to "脚踏与外设", "updates" to "应用更新")
    private fun grid(open: (String) -> Unit = {}) {
        compose.setContent { MaterialTheme { ProfileShortcutGrid(entries.map { Triple(it.first, it.second, Icons.Outlined.Settings) }, open) } }
    }
    private fun picture(name: String) {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val folder = File("build/reports/screenshots").apply { check(isDirectory || mkdirs()) }
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun narrowGridHasTwoColumnsAndAllActionsRemainClickable() {
        var opened = ""
        grid { opened = it }
        val first = compose.onNodeWithTag("profile-account").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("profile-management").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("profile-part-grants").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, .1f)
        assertTrue(second.left >= first.right)
        assertTrue(third.top >= first.bottom)
        entries.forEach { (key, _) -> compose.onNodeWithTag("profile-$key").assertIsDisplayed().performClick(); assertEquals(key, opened) }
        picture("profile-grid-compact.png")
    }
    @Test @Config(qualifiers = "w1000dp-h1000dp-mdpi")
    fun wideGridUsesFourColumnsInsteadOfStretchingOneVerticalList() {
        grid()
        val first = compose.onNodeWithTag("profile-account").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("profile-archive").fetchSemanticsNode().boundsInRoot
        val fifth = compose.onNodeWithTag("profile-pedal").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, fourth.top, .1f)
        assertTrue(fifth.top >= first.bottom)
        picture("profile-grid-wide.png")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun overviewKeepsAccountDetailsAndLogoutBehindExplicitEntry() {
        val dashboard = Dashboard(listOf(MemberChoice("m", "Member")), Profile("m", "Member", "Violin", false))
        var left = false
        compose.setContent { MaterialTheme { CompactProfilePage(dashboard, { left = true }, { _, _, _ -> }) } }
        compose.onNodeWithText("选择成员").assertDoesNotExist()
        compose.onNodeWithText("退出当前成员").assertDoesNotExist()
        compose.onNodeWithTag("profile-account").performClick()
        compose.onNodeWithText("选择成员").assertExists()
        compose.onNodeWithTag("profile-detail-back").performClick()
        compose.onNodeWithTag("profile-account-menu").performClick()
        compose.onNodeWithText("退出当前成员").performClick()
        assertTrue(left)
    }
}
