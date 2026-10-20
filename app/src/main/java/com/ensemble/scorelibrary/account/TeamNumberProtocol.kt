package com.ensemble.scorelibrary.account

import org.json.JSONObject

/** Routing comes from the authenticated principal and is never guessed or retried as v1. */
fun TeamSession.scorePath(path: String): String {
    require(path.startsWith("/v1/") && principal.numberingProtocol in 1..2)
    return if (principal.numberingProtocol == 2) path.replaceFirst("/v1/", "/v2/") else path
}

fun TeamAccountGateway.scoreRequest(session: TeamSession, path: String, body: JSONObject? = null): JSONObject {
    if (body != null) requireScoreWrite(session)
    return request(session, session.scorePath(path), body)
}

fun TeamAccountGateway.requireScoreWrite(session: TeamSession) {
    if (session.principal.numberingProtocol == 2) {
        val reply = request(session, "/v2/admin/capabilities")
        require(reply.get("protocolVersion") == 2 && reply.get("teamId") == session.principal.teamId &&
            reply.get("candidateOnly") == false && reply.get("expandedPublicationReady") == true) { "服务扩位发布尚未就绪，请重新核验" }
        val numbers = reply.getJSONObject("numbering")
        val versions = numbers.getJSONArray("namingVersions")
        require(versions.length() == 2 && (0..1).map { versions.get(it) }.toSet() == setOf(1,2) &&
            numbers.get("maximumSequence") == Long.MAX_VALUE.toString() &&
            numbers.get("sequenceEncoding") == "decimal-string" && numbers.get("expandedSeparator") == "-") { "服务编号契约不匹配" }
    }
}
