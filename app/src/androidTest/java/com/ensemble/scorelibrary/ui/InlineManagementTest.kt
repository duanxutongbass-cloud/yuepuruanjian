package com.ensemble.scorelibrary.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.data.*
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.NativePdfEngine
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineManagementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun libraryConcertAndProfileOpenExistingFormsWithoutSeparateAdminTab() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            runBlocking {
                db.libraryDao().insertMember(MemberEntity(id = "a", displayName = "Admin", role = MemberRole.ADMIN))
                db.libraryDao().insertMember(MemberEntity(id = "m", displayName = "Member"))
                db.libraryDao().insertPartDefinition(PartDefinitionEntity(id = "d", name = "Violin I"))
            }
            val admin = AdministrationViewModel(LocalAdministration(context, db))
            val management = ManagementViewModel(LibraryManagement(context, db, NativePdfEngine(context)))
            store.put("admin", admin); store.put("management", management)
            val root = mutableStateOf(RootPage.Library)
            val profile = mutableStateOf(Profile("a", "Admin", null, true))
            compose.setContent { MaterialTheme { CompositionLocalProvider(
                LocalAdministrationViewModel provides admin, LocalManagement provides management) {
                LibraryScreen(UiState.Ready(Presentation(Dashboard(
                    listOf(MemberChoice("a", "Admin"), MemberChoice("m", "Member")), profile.value),
                    DomainResult.Success(ScreenContent.Home)), false), root.value)
            } } }
            compose.onNodeWithTag("library-import").performClick()
            compose.onNodeWithText("新曲目名称").performScrollTo().assertExists()
            compose.onNodeWithTag("close-inline-management").performClick()
            compose.runOnIdle { root.value = RootPage.Concerts }
            compose.onNodeWithTag("concert-create").performClick()
            compose.waitUntil(10_000) { management.state.value.catalog != null }
            compose.onNodeWithTag("editor-name").performTextInput("New concert")
            compose.onNodeWithTag("manage-save").performClick()
            compose.waitUntil(10_000) { management.state.value.revision > 0 && !management.state.value.busy }
            assertEquals("New concert", runBlocking { db.resolutionDao().concerts().single().title })
            compose.onNodeWithTag("close-inline-management").performClick()
            compose.runOnIdle { root.value = RootPage.Profile }
            compose.onNodeWithTag("profile-part-grants").performClick()
            compose.onNodeWithTag("managed-m").performClick()
            compose.onNodeWithText("按声部授权 · 全部曲目").assertExists()
            compose.onNodeWithTag("global-d").performClick()
            compose.waitUntil(10_000) { management.state.value.revision > 1 && !management.state.value.busy }
            assertTrue(runBlocking { db.resolutionDao().permissions().any { it.memberId == "m" && it.partDefinitionId == "d" && !it.isArchived } })
            compose.onNodeWithTag("close-inline-management").performClick()
            compose.runOnIdle { profile.value = Profile("m", "Member", null, false) }
            compose.onNodeWithTag("profile-management").assertDoesNotExist()
            compose.onNodeWithTag("profile-part-grants").assertDoesNotExist()
            compose.runOnIdle { root.value = RootPage.Library }
            compose.onNodeWithTag("library-import").assertDoesNotExist()
            compose.runOnIdle { root.value = RootPage.Concerts }
            compose.onNodeWithTag("concert-create").assertDoesNotExist()
        } finally { store.clear(); db.close() }
    }
}
