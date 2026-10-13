package com.ensemble.scorelibrary.data

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.Dashboard
import com.ensemble.scorelibrary.domain.Profile
import com.ensemble.scorelibrary.ui.AdministrationScreen
import com.ensemble.scorelibrary.ui.AdministrationViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdministrationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun administratorFormHasValidatedImportControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val vm = AdministrationViewModel(LocalAdministration(context, db))
        val store = androidx.lifecycle.ViewModelStore().apply { put("admin", vm) }
        try {
            compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                AdministrationScreen(Dashboard(listOf(com.ensemble.scorelibrary.domain.MemberChoice("a", "Admin")),
                    Profile("a", "Admin", null, true)), vm)
            } } }
            compose.onNodeWithText("选择 PDF 并导入").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("新曲目名称").performScrollTo().performTextInput("Test score")
            compose.onNodeWithText("声部名称").performScrollTo().performTextInput("Violin I")
            compose.onNodeWithText("选择 PDF 并导入").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("结束页（空为末页）").performScrollTo().performTextInput("invalid")
            compose.onNodeWithText("选择 PDF 并导入").performScrollTo().assertIsNotEnabled()
        } finally { store.clear(); db.close() }
    }
    @Test fun freshInstallationCanCreateAdministratorThroughForm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val vm = AdministrationViewModel(LocalAdministration(context, db))
        val store = androidx.lifecycle.ViewModelStore().apply { put("admin", vm) }
        try {
            compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                AdministrationScreen(Dashboard(emptyList(), null, emptyList()), vm)
            } } }
            compose.onNodeWithText("创建管理员").assertIsNotEnabled()
            compose.onNodeWithText("成员姓名").performTextInput("Pad Admin")
            compose.onNodeWithText("创建管理员").performClick()
            compose.waitUntil(10_000) { vm.state.value.createdMember != null }
            assertEquals("Pad Admin", runBlocking { db.resolutionDao().members().single().displayName })
        } finally { store.clear(); db.close() }
    }
    @Test fun nonAdministratorCannotSeeImportOrMemberWriteForm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val vm = AdministrationViewModel(LocalAdministration(context, db))
        val store = androidx.lifecycle.ViewModelStore().apply { put("admin", vm) }
        try {
            compose.setContent { MaterialTheme { AdministrationScreen(Dashboard(
                listOf(com.ensemble.scorelibrary.domain.MemberChoice("m", "Member")),
                Profile("m", "Member", null, false), emptyList()), vm) } }
            compose.onNodeWithText("选择 PDF 并导入").assertDoesNotExist()
            compose.onNodeWithText("添加成员").assertDoesNotExist()
            compose.onNodeWithText("请在“我的”中选择管理员").assertExists()
        } finally { store.clear(); db.close() }
    }
}
