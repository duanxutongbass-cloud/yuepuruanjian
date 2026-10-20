package com.ensemble.scorelibrary.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class TeamPublicationChunk(val jobs: List<TeamPublishJob>, val requestId: String)

/** Stable boundaries and IDs include finished jobs, so partial retries cannot regroup receipts. */
internal fun teamPublicationChunks(jobs: List<TeamPublishJob>): List<TeamPublicationChunk> {
    require(jobs.isNotEmpty())
    val empty = UUID(0, 0).toString()
    fun fits(batch: List<TeamPublishJob>): Boolean = batch.size <= 50 && JSONObject().put("requestId", empty)
        .put("assignments", JSONArray(batch.map { job -> JSONObject().put("artifactId", job.uploadRequest)
            .put("accountIds", JSONArray(job.details.recipients.sorted())) }))
        .toString().toByteArray(Charsets.UTF_8).size <= 16384
    val batches = mutableListOf<List<TeamPublishJob>>()
    var current = mutableListOf<TeamPublishJob>()
    jobs.forEach { job ->
        require(fits(listOf(job))) { "单份乐谱的接收清单超过服务容量，请减少该份接收成员" }
        if (current.isNotEmpty() && !fits(current + job)) {
            batches += current.toList()
            current = mutableListOf()
        }
        current += job
    }
    if (current.isNotEmpty()) batches += current.toList()
    return batches.map { batch ->
        // The old single-request protocol must retain its original durable ID.
        val request = if (batches.size == 1) jobs.first().publicationRequest else UUID.nameUUIDFromBytes(
            ("team-publication-queue-v2|" + jobs.first().publicationRequest + "|" + batch.joinToString("|") { it.uploadRequest })
                .toByteArray(Charsets.UTF_8)).toString()
        TeamPublicationChunk(batch, request)
    }
}
