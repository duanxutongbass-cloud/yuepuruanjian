package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.annotation.InkCodec
import com.ensemble.scorelibrary.annotation.InkMark
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun syntheticTeamFreeze(program:TeamProgram,catalog:TeamCatalog,marks:List<InkMark> = emptyList()):TeamProgramSnapshot {
    val artifacts=catalog.artifacts.filter { it.id in program.frozenArtifacts }
    val payload=JSONObject().put("protocolVersion",1).put("programId",program.id).put("freezeId",program.freezeId)
        .put("programRevision",1).put("title",program.title).put("pieceKeys",JSONArray(program.pieceKeys))
        .put("artifacts",JSONArray(artifacts.map { a -> JSONObject().put("artifactId",a.id).put("hash",a.hash).put("size",a.size)
            .put("pageCount",a.pages).put("title",a.title).put("partName",a.partName).put("scoreNumber",a.number).put("category",a.category).put("pieceKey",a.pieceKey) }))
        .put("pages",JSONArray(artifacts.map { a -> JSONObject().put("artifactId",a.id).put("pageIndex",0).put("revision",1)
            .put("marks",JSONArray(InkCodec.encode(marks))) })).toString()
    val checksum=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    return TeamProgramSnapshot.parse(JSONObject().put("protocolVersion",1).put("programId",program.id).put("freezeId",program.freezeId)
        .put("sha256",checksum).put("payload",payload),program,catalog)
}
