package com.ensemble.scorelibrary.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TeamDeletionProtocolTest {
    private val team = "11111111-1111-4111-8111-111111111111"
    private val account = "22222222-2222-4222-8222-222222222222"
    private val storage = "33333333-3333-4333-8333-333333333333"
    private val request = "44444444-4444-4444-8444-444444444444"
    private val artifact = "55555555-5555-4555-8555-555555555555"
    private fun page() = JSONObject("""{"protocolVersion":1,"storageId":"$storage","teamId":"$team","accountId":"$account",
        "after":"0","through":"1","nextAfter":"1","more":false,
        "deletions":[{"sequence":"1","artifactId":"$artifact","requestId":"$request","deletedAt":1792350000}],
        "jobs":[{"requestId":"$request","programIds":[],"freezeIds":[],"pieceReferences":[]}]}""")
    private fun parse(value: JSONObject, expected: String? = null, after: Long = 0) = TeamDeletionPage.parse(value, "https://scores.example", team, account, expected, after)
    private fun reject(change: (JSONObject) -> Unit) {
        val value = page();change(value)
        assertThrows(Exception::class.java) { parse(value) }
    }
    @Test fun verifiedPageAndEmptyAcknowledgementPreserveExactCursor() {
        val first = parse(page());assertEquals(artifact, first.deletions.single().artifact)
        assertEquals(1L, first.next);assertFalse(first.more)
        val empty = page().put("after", "1").put("deletions", org.json.JSONArray()).put("jobs", org.json.JSONArray())
        assertTrue(parse(empty, storage, 1).deletions.isEmpty())
        val large = page().put("after", "9223372036854775806").put("through", Long.MAX_VALUE.toString()).put("nextAfter", Long.MAX_VALUE.toString())
        large.getJSONArray("deletions").getJSONObject(0).put("sequence", Long.MAX_VALUE.toString())
        assertEquals(Long.MAX_VALUE, parse(large, storage, Long.MAX_VALUE - 1).next)
    }
    @Test fun identityCursorAndJsonTypeConfusionNeverBecomeDeletionAuthority() {
        reject { it.put("teamId", account) };reject { it.put("accountId", team) }
        reject { it.put("protocolVersion", "1") };reject { it.put("nextAfter", 1) }
        reject { it.put("through", "01") };reject { it.put("through", "9223372036854775808") }
        reject { it.put("after", "1") };reject { it.put("more", "false") }
        reject { it.put("storageId", "../other-team") }
        assertThrows(Exception::class.java) { parse(page(), team) }
        assertThrows(Exception::class.java) { parse(page(), null, 1) }
    }
    @Test fun duplicateMissingOrUnorderedTargetsAndIncompleteImpactMapAreRejected() {
        reject { it.getJSONArray("deletions").put(it.getJSONArray("deletions").getJSONObject(0)) }
        reject { it.getJSONArray("deletions").getJSONObject(0).put("sequence", "0") }
        reject { it.getJSONArray("deletions").getJSONObject(0).put("deletedAt", 1.5) }
        reject { it.getJSONArray("deletions").getJSONObject(0).put("artifactId", "../../source.pdf") }
        reject { it.put("jobs", org.json.JSONArray()) }
        reject { it.getJSONArray("jobs").getJSONObject(0).getJSONArray("programIds").put(team).put(team) }
        reject { it.put("nextAfter", "0") };reject { it.put("more", true) }
        reject { it.put("through", "2") };reject { it.put("deletions", org.json.JSONArray()) }
        reject { it.put("unexpectedOwner", account) }
    }
}
