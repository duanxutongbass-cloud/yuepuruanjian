package com.ensemble.scorelibrary.annotation

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class InkDraftReconcilerTest {
    private val source=RecoverySource("local","a".repeat(64))
    private val base=listOf(InkMark(id="base",kind=MarkKind.PEN,points=listOf(InkPoint(.1f,.2f))))
    private val first=base+base.single().copy(id="first")
    private val last=first+base.single().copy(id="last")
    private fun write(marks:List<InkMark>,generation:Long,before:List<InkMark>)=
        InkDraftWrite(marks,generation,InkDraftStore.digest(InkCodec.encode(before)))
    private val record get()=InkDraftRecord("token",source,listOf(write(first,3,base),write(last,4,first)))
    @Test fun untouchedBaselineRestoresBothPendingWrites() {
        assertEquals(InkDraftDecision.Restore(record.writes),InkDraftReconciler.compare(record,source,base,3,true))
    }
    @Test fun firstCommittedButLostAcknowledgementRetainsOnlyLatestEdit() {
        assertEquals(InkDraftDecision.Restore(record.writes.drop(1)),InkDraftReconciler.compare(record,source,first,4,true))
    }
    @Test fun bothCommittedCanClearJournalWithoutSavingAgain() {
        assertEquals(InkDraftDecision.Restore(emptyList<InkDraftWrite>()),InkDraftReconciler.compare(record,source,last,5,true))
    }
    @Test fun changedMarksEvenAtSameGenerationAreConflict() {
        assertEquals(InkDraftDecision.Conflict,InkDraftReconciler.compare(record,source,emptyList(),3,true))
    }
    @Test fun sameMarksAtLaterGenerationCannotProveAcknowledgement() {
        assertEquals(InkDraftDecision.Conflict,InkDraftReconciler.compare(record,source,last,6,true))
    }
    @Test fun sourceHashAndSpaceChangesAreIsolated() {
        assertEquals(InkDraftDecision.DifferentSource,InkDraftReconciler.compare(record,source.copy(sha256="b".repeat(64)),base,3,true))
        assertEquals(InkDraftDecision.DifferentSource,InkDraftReconciler.compare(record,source.copy(space="team:other"),base,3,true))
    }
    @Test fun lostRoleTakesPrecedenceOverAnyDraftContents() {
        assertEquals(InkDraftDecision.Hidden,InkDraftReconciler.compare(record,source,last,5,false))
    }
}
