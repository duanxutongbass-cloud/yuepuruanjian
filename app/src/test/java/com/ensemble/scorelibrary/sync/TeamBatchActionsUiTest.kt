package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
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
@Config(qualifiers="w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamBatchActionsUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun wholePieceBatchRemoveAndRestoreKeepTheManagerOpenAndRequireConsent() {
        val a=EnsemblePiece("a","天空之城","001","01",false,false,listOf(EnsemblePart(UUID.randomUUID().toString(),"小提琴","0101001")))
        val b=EnsemblePiece("b","未选曲目","002","01",false,false,listOf(EnsemblePart(UUID.randomUUID().toString(),"大提琴","0201002")))
        var state by mutableStateOf(EnsembleState("owner",listOf(a,b),revision=1))
        var submitted:TeamBatchPlan?=null
        ui.setContent {MaterialTheme {Column {TeamEnsembleManager(state,emptyList(),false,{}, {},
            previewBatch={keys,removed->state=state.copy(batchPreview=batchPlan(state,keys,removed))},
            confirmBatch={plan->submitted=plan;state=state.copy(pieces=state.pieces.map {if(it.key in plan.pieces.map {p->p.key})it.copy(removed=plan.removed) else it},
                batchPreview=null,revision=state.revision+1)},dismissBatch={state=state.copy(batchPreview=null)})}}}
        ui.onNodeWithTag("team-select-a").performClick();ui.onNodeWithTag("team-batch-selected").assertIsEnabled().performClick()
        ui.runOnIdle {assertNotNull(state.batchPreview)}
        ui.onNodeWithTag("team-batch-confirm").assertIsNotEnabled();assertNull(submitted)
        ui.onNodeWithTag("team-batch-consent").performClick();ui.onNodeWithTag("team-batch-confirm").performClick()
        assertEquals(setOf(a.parts[0].id),submitted!!.artifacts);assertFalse(state.pieces.single {it.key=="b"}.removed)
        ui.onNodeWithTag("team-score-management").assertExists();ui.onNodeWithText("查看回收站").performClick()
        ui.onNodeWithTag("team-select-a").performClick();ui.onNodeWithTag("team-batch-selected").performClick()
        ui.onNodeWithTag("team-batch-confirm").assertIsNotEnabled()
        ui.onNodeWithTag("team-batch-consent").performClick();ui.onNodeWithTag("team-batch-confirm").performClick()
        assertFalse(submitted!!.removed);assertTrue(state.pieces.none {it.removed})
        ui.onNodeWithText("回收站").assertExists();ui.onNodeWithTag("team-score-management").assertExists()
    }
}
