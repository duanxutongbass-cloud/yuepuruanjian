package com.ensemble.scorelibrary.sync

import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TeamBatchActionsTest {
    private fun piece(key:String,removed:Boolean=false,count:Int=1)=EnsemblePiece(key,key,"001","01",removed,false,
        List(count){EnsemblePart(UUID.randomUUID().toString(),"Violin","0101001")})
    @Test fun selectedCompletePiecesAndStableRequestOnly() {
        val one=piece("one",count=2);val two=piece("two");val state=EnsembleState("team-account",listOf(one,two),revision=7)
        val plan=batchPlan(state,setOf("one"),true)
        assertEquals(one.parts.map {it.id}.toSet(),plan.artifacts);assertEquals(2,plan.body().getJSONArray("artifactIds").length())
        assertEquals(plan.body().toString(),plan.body().toString());assertEquals(7L,plan.body().getLong("expectedRevision"))
        assertFalse(two.parts[0].id in plan.artifacts)
    }
    @Test fun staleMissingMixedConflictAndOverCapacityRefused() {
        val live=piece("live");val removed=piece("removed",true)
        val state=EnsembleState("owner",listOf(live,removed),revision=1)
        for(selected in listOf(emptySet(),setOf("missing"),setOf("live","removed")))assertTrue(runCatching {batchPlan(state,selected,true)}.isFailure)
        assertTrue(runCatching {batchPlan(state.copy(revision=-1),setOf("live"),true)}.isFailure)
        assertTrue(runCatching {batchPlan(state.copy(pieces=listOf(live.copy(conflict=true))),setOf("live"),true)}.isFailure)
        assertTrue(runCatching {batchPlan(state.copy(pieces=listOf(piece("many",count=201))),setOf("many"),true)}.isFailure)
        assertEquals(removed.parts.map {it.id}.toSet(),batchPlan(state,setOf("removed"),false).artifacts)
    }
}
