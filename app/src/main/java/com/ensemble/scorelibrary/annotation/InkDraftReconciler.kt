package com.ensemble.scorelibrary.annotation

internal sealed interface InkDraftDecision {
    data class Restore(val writes:List<InkDraftWrite>):InkDraftDecision
    data object Conflict:InkDraftDecision
    data object DifferentSource:InkDraftDecision
    data object Hidden:InkDraftDecision
}

/** Pure comparison only: no rebase, publication, grant changes or writes. */
internal object InkDraftReconciler {
    fun compare(record:InkDraftRecord,source:RecoverySource,marks:List<InkMark>,generation:Long,allowed:Boolean):InkDraftDecision {
        if(!allowed)return InkDraftDecision.Hidden
        if(record.source!=source)return InkDraftDecision.DifferentSource
        // A transaction may have committed just before process loss. A later identical generation
        // is deliberately insufficient proof; only the exact acknowledged next revision counts.
        val acknowledged=record.writes.indexOfLast { it.generation<Long.MAX_VALUE && generation==it.generation+1 && marks==it.marks }
        val remaining=record.writes.drop(acknowledged+1)
        if(remaining.isEmpty())return InkDraftDecision.Restore(emptyList())
        val next=remaining.first()
        return if(next.generation==generation && next.beforeHash==InkDraftStore.digest(InkCodec.encode(marks)))
            InkDraftDecision.Restore(remaining) else InkDraftDecision.Conflict
    }
}
