package com.ensemble.scorelibrary.sync

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class TeamBatchPlan internal constructor(val key:String,val revision:Long,val removed:Boolean,
    val pieces:List<EnsemblePiece>,val request:String=UUID.randomUUID().toString()) {
    val artifacts:Set<String> get()=pieces.flatMap { it.parts }.map { it.id }.toSet()
    fun body()=JSONObject().put("requestId",request).put("expectedRevision",revision).put("removed",removed)
        .put("artifactIds",JSONArray(artifacts.sorted()))
}

internal fun batchPlan(state:EnsembleState,selected:Set<String>,removed:Boolean):TeamBatchPlan {
    require(state.key!=null && state.revision>=0 && selected.isNotEmpty())
    val pieces=selected.sorted().map { key->state.pieces.single { it.key==key } }
    require(pieces.all { !it.conflict && it.removed!=removed && it.parts.isNotEmpty() })
    val ids=pieces.flatMap { it.parts }.map { it.id }
    require(ids.size in 1..200 && ids.toSet().size==ids.size && ids.all { UUID.fromString(it).toString()==it })
    return TeamBatchPlan(checkNotNull(state.key),state.revision,removed,pieces)
}
