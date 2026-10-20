package com.ensemble.scorelibrary.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class TeamDeletion(val sequence: Long, val artifact: String, val request: String, val deletedAt: Long)
internal data class TeamDeletionImpact(val request: String, val programs: Set<String>, val freezes: Set<String>, val pieces: Set<String> = emptySet())
internal data class TeamDeletionPage(val server: String, val storage: String, val team: String, val account: String, val after: Long, val through: Long, val next: Long,
    val more: Boolean, val deletions: List<TeamDeletion>, val impacts: List<TeamDeletionImpact>) {
    companion object {
        fun parse(json: JSONObject, server: String, team: String, account: String, expectedStorage: String?, after: Long): TeamDeletionPage {
            require(server.isNotBlank() && server.length <= 2048 && server.none(Char::isISOControl))
            fun keys(value: JSONObject, expected: Set<String>) = require(value.keys().asSequence().toSet() == expected)
            fun uuid(value: Any): String = (value as? String).also { require(it != null && UUID.fromString(it).toString() == it) }!!
            fun decimal(value: Any): Long {
                require(value is String && value.matches(Regex("0|[1-9][0-9]{0,18}")))
                return value.toLong().also { require(it >= 0) }
            }
            fun integer(value: Any): Long {
                require(value is Int || value is Long)
                return (value as Number).toLong().also { require(it >= 0) }
            }
            fun ids(array: JSONArray): Set<String> {
                require(array.length() <= 5000)
                return (0 until array.length()).map { uuid(array.get(it)) }.also { require(it.distinct().size == it.size) }.toSet()
            }
            keys(json, setOf("protocolVersion", "storageId", "teamId", "accountId", "after", "through", "nextAfter", "more", "deletions", "jobs"))
            require(json.get("protocolVersion") == 1 && uuid(json.get("teamId")) == team && uuid(json.get("accountId")) == account)
            val storage = uuid(json.get("storageId"))
            require(after >= 0 && (expectedStorage == null && after == 0L || expectedStorage == storage))
            require(decimal(json.get("after")) == after)
            val through = decimal(json.get("through"));val next = decimal(json.get("nextAfter"))
            require(after <= next && next <= through && json.get("more") is Boolean)
            val more = json.getBoolean("more")
            val list = json.getJSONArray("deletions").also { require(it.length() <= 100) }
            val deletions = (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                keys(item, setOf("sequence", "artifactId", "requestId", "deletedAt"))
                TeamDeletion(decimal(item.get("sequence")), uuid(item.get("artifactId")), uuid(item.get("requestId")), integer(item.get("deletedAt")))
            }
            require(deletions.map { it.artifact }.distinct().size == deletions.size)
            require(deletions.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
            require(deletions.all { it.sequence > after && it.sequence <= through })
            require(if (deletions.isEmpty()) !more && after == through && next == after else next == deletions.last().sequence && more == (next < through))
            val jobs = json.getJSONArray("jobs").also { require(it.length() <= 100) }
            val impacts = (0 until jobs.length()).map { index ->
                val item = jobs.getJSONObject(index)
                keys(item, setOf("requestId", "programIds", "freezeIds", "pieceReferences"))
                TeamDeletionImpact(uuid(item.get("requestId")), ids(item.getJSONArray("programIds")), ids(item.getJSONArray("freezeIds")),
                    ids(item.getJSONArray("pieceReferences")))
            }
            require(impacts.map { it.request }.distinct().size == impacts.size && impacts.map { it.request }.toSet() == deletions.map { it.request }.toSet())
            return TeamDeletionPage(server, storage, team, account, after, through, next, more, deletions, impacts)
        }
    }
}
