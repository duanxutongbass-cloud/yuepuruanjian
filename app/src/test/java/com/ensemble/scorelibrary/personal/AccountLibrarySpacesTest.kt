package com.ensemble.scorelibrary.personal

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.account.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers="w800dp-h1200dp-mdpi")
class AccountLibrarySpacesTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
        "测试当前乐团","player","演奏员","member",true,"joined",true)
    @Test fun joinedAccountChoosesPersonalOrNamedCurrentTeamAndKeepsChoiceAcrossTeamRefresh() {
        var state by mutableStateOf(TeamAccountState(principal=principal))
        val choice=mutableStateOf(false)
        ui.setContent { MaterialTheme { AccountLibrarySpaces(state,choice,
            personal={Text("本人文件",Modifier.testTag("own-files"))},team={Text("授权团队谱",Modifier.testTag("team-files"))}) } }
        ui.onNodeWithTag("library-space-team").assertTextEquals(principal.teamName)
        ui.onNodeWithTag("team-files").assertExists();ui.onNodeWithTag("own-files").assertDoesNotExist()
        ui.onNodeWithTag("library-space-personal").performClick()
        ui.runOnUiThread { com.ensemble.scorelibrary.ui.saveUiProof(ui.activity,"0126-library-spaces.png") }
        ui.onNodeWithTag("own-files").assertExists();ui.onNodeWithTag("team-files").assertDoesNotExist()
        ui.runOnIdle { state=state.copy(busy=true,libraryBlocked=true,libraryMember=null) }
        ui.onNodeWithTag("own-files").assertExists()
        ui.runOnIdle { state=state.copy(busy=false,libraryBlocked=false,libraryMember="new-handle") }
        ui.onNodeWithTag("own-files").assertExists()
        ui.onNodeWithTag("library-space-team").performClick();ui.onNodeWithTag("team-files").assertExists()
    }
    @Test fun unjoinedPendingRejectedOrSignedOutNeverComposePreviousTeamContent() {
        var state by mutableStateOf(TeamAccountState(principal=principal.copy(membership="unjoined")))
        val choice=mutableStateOf(false)
        ui.setContent { MaterialTheme { AccountLibrarySpaces(state,choice,
            personal={Text("本人文件",Modifier.testTag("own-files"))},team={Assert.fail("Unjoined account must not compose team content")}) } }
        for(membership in listOf("unjoined","pending","rejected")) {
            ui.runOnIdle { state=state.copy(principal=principal.copy(membership=membership)) }
            ui.onNodeWithTag("own-files").assertExists();ui.onNodeWithTag("library-space-team").assertDoesNotExist()
        }
        ui.runOnIdle { state=state.copy(principal=null) }
        ui.onNodeWithTag("own-files").assertDoesNotExist();ui.onNodeWithTag("account-library-spaces").assertDoesNotExist()
    }
}
