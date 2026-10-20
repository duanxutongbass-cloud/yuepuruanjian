package com.ensemble.scorelibrary.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TeamPermanentSelectionTest {
    private fun piece(key:String,count:Int=1,removed:Boolean=true)=EnsemblePiece(key,key,"001","01",removed,false,
        List(count) { EnsemblePart(UUID.randomUUID().toString(),"声部","0101001") })
    private fun reject(state:EnsembleState,keys:Set<String>) {
        try { permanentArtifacts(state,keys);fail("Selection must be rejected") } catch(_:IllegalArgumentException) {} catch(_:NoSuchElementException) {}
    }
    @Test fun combinesCompleteSelectedPiecesWithoutIncludingOtherRecycleOrLiveFiles() {
        val a=piece("a",2);val b=piece("b",3);val keep=piece("keep");val live=piece("live",removed=false)
        val state=EnsembleState(revision=7,pieces=listOf(a,b,keep,live))
        assertEquals((a.parts+b.parts).map { it.id }.toSet(),permanentArtifacts(state,setOf(a.key,b.key)))
        reject(state,setOf("live"));reject(state,setOf("a","foreign"));reject(state,emptySet())
        reject(state.copy(revision=-1),setOf("a"))
        reject(state.copy(pieces=listOf(a.copy(removed=false))),setOf("a"))
        reject(state.copy(pieces=listOf(a.copy(conflict=true))),setOf("a"))
    }
    @Test fun neverSplitsOverLimitOrAcceptsDuplicateOrMalformedArtifactIdentity() {
        val a=piece("a",150);val b=piece("b",51)
        reject(EnsembleState(revision=1,pieces=listOf(a,b)),setOf("a","b"))
        assertEquals(200,permanentArtifacts(EnsembleState(revision=1,pieces=listOf(piece("a",200))),setOf("a")).size)
        reject(EnsembleState(revision=1,pieces=listOf(a,a.copy(key="other"))),setOf("a","other"))
        reject(EnsembleState(revision=1,pieces=listOf(piece("a").copy(parts=listOf(EnsemblePart("../escape","v","001"))))),setOf("a"))
    }
}
